// Copyright (c) 2020-2022 Yinsen (Tesla) Zhang.
// Use of this source code is governed by the MIT license that can be found in the LICENSE.md file.
package org.aya.tyck;

import kala.collection.immutable.ImmutableMap;
import kala.collection.mutable.DynamicSeq;
import kala.collection.mutable.MutableMap;
import kala.tuple.Tuple;
import kala.tuple.Tuple2;
import kala.tuple.Tuple3;
import kala.value.LazyValue;
import org.aya.concrete.Expr;
import org.aya.concrete.stmt.Decl;
import org.aya.concrete.stmt.Signatured;
import org.aya.core.def.*;
import org.aya.core.term.*;
import org.aya.core.visitor.Substituter;
import org.aya.core.visitor.Unfolder;
import org.aya.core.visitor.Zonker;
import org.aya.generic.Arg;
import org.aya.generic.Constants;
import org.aya.generic.Modifier;
import org.aya.generic.util.InternalException;
import org.aya.generic.util.NormalizeMode;
import org.aya.pretty.doc.Doc;
import org.aya.ref.DefVar;
import org.aya.ref.LocalVar;
import org.aya.ref.Var;
import org.aya.tyck.env.LocalCtx;
import org.aya.tyck.env.MapLocalCtx;
import org.aya.tyck.error.*;
import org.aya.tyck.trace.Trace;
import org.aya.tyck.unify.DefEq;
import org.aya.util.Ordering;
import org.aya.util.distill.AyaDocile;
import org.aya.util.error.SourcePos;
import org.aya.util.reporter.Problem;
import org.aya.util.reporter.Reporter;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * @apiNote make sure to instantiate this class once for each {@link Decl}.
 * Do <em>not</em> use multiple instances in the tycking of one {@link Decl}
 * and do <em>not</em> reuse instances of this class in the tycking of multiple {@link Decl}s.
 */
public final class ExprTycker {
  public final @NotNull Reporter reporter;
  public @NotNull LocalCtx localCtx = new MapLocalCtx();
  public final @Nullable Trace.Builder traceBuilder;
  public final @NotNull TyckState state = new TyckState();

  private void tracing(@NotNull Consumer<Trace.@NotNull Builder> consumer) {
    if (traceBuilder != null) consumer.accept(traceBuilder);
  }

  public @NotNull Zonker newZonker() {
    return new Zonker(state, reporter);
  }

  private @NotNull Result doSynthesize(@NotNull Expr expr) {
    return switch (expr) {
      case Expr.LamExpr lam -> inherit(lam, generatePi(lam));
      case Expr.UnivExpr univ -> new Result(new FormTerm.Univ(univ.lift()), new FormTerm.Univ(univ.lift() + 1));
      case Expr.RefExpr ref -> switch (ref.resolvedVar()) {
        case LocalVar loc -> {
          var ty = localCtx.get(loc);
          yield new Result(new RefTerm(loc, 0), ty);
        }
        case DefVar<?, ?> defVar -> inferRef(ref.sourcePos(), defVar);
        default -> throw new IllegalStateException("Unknown var: " + ref.resolvedVar().getClass());
      };
      case Expr.PiExpr pi -> {
        var param = pi.param();
        final var var = param.ref();
        var domTy = param.type();
        var domRes = synthesize(domTy);
        var domLvl = ensureUniv(domTy, domRes.type);
        var resultParam = new Term.Param(var, domRes.wellTyped, param.explicit());
        yield localCtx.with(resultParam, () -> {
          var cod = synthesize(pi.last());
          var codLvl = ensureUniv(pi.last(), cod.type);
          return new Result(new FormTerm.Pi(resultParam, cod.wellTyped), new FormTerm.Univ(Math.max(domLvl, codLvl)));
        });
      }
      case Expr.SigmaExpr sigma -> {
        var resultTele = DynamicSeq.<Tuple3<LocalVar, Boolean, Term>>create();
        var maxLevel = 0;
        for (var tuple : sigma.params()) {
          final var type = tuple.type();
          var result = synthesize(type);
          maxLevel = Math.max(maxLevel, ensureUniv(type, result.type));
          var ref = tuple.ref();
          localCtx.put(ref, result.wellTyped);
          resultTele.append(Tuple.of(ref, tuple.explicit(), result.wellTyped));
        }
        localCtx.remove(sigma.params().view().map(Expr.Param::ref));
        yield new Result(new FormTerm.Sigma(Term.Param.fromBuffer(resultTele)), new FormTerm.Univ(maxLevel));
      }
      case Expr.LiftExpr lift -> {
        var result = synthesize(lift.expr());
        var levels = lift.lift();
        yield new Result(result.wellTyped.lift(levels), result.type.lift(levels));
      }
      case Expr.NewExpr newExpr -> {
        var structExpr = newExpr.struct();
        var struct = instImplicits(synthesize(structExpr).wellTyped, structExpr.sourcePos());
        if (!(struct instanceof CallTerm.Struct structCall))
          yield fail(structExpr, struct, BadTypeError.structCon(newExpr, struct));
        var structRef = structCall.ref();

        var subst = new Substituter.TermSubst(MutableMap.from(
          Def.defTele(structRef).view().zip(structCall.args())
            .map(t -> Tuple.of(t._1.ref(), t._2.term()))));

        var fields = DynamicSeq.<Tuple2<DefVar<FieldDef, Decl.StructField>, Term>>create();
        var missing = DynamicSeq.<Var>create();
        var conFields = newExpr.fields();

        for (var defField : structRef.core.fields) {
          var conFieldOpt = conFields.find(t -> t.name().equals(defField.ref().name()));
          if (conFieldOpt.isEmpty()) {
            if (defField.body.isEmpty())
              missing.append(defField.ref()); // no value available, skip and prepare error reporting
            else {
              // use default value from defField
              var field = defField.body.get().subst(subst);
              fields.append(Tuple.of(defField.ref(), field));
              subst.add(defField.ref(), field);
            }
            continue;
          }
          var conField = conFieldOpt.get();
          conFields = conFields.dropWhile(t -> t == conField);
          var type = Def.defType(defField.ref()).subst(subst);
          var telescope = defField.ref().core.selfTele.map(term -> term.subst(subst));
          var bindings = conField.bindings();
          if (telescope.sizeLessThan(bindings.size())) {
            // TODO: Maybe it's better for field to have a SourcePos?
            yield fail(newExpr, structCall, new FieldProblem.ArgMismatchError(newExpr.sourcePos(), defField, bindings.size()));
          }
          var fieldExpr = bindings.zip(telescope).foldRight(conField.body(), (pair, lamExpr) ->
            new Expr.LamExpr(conField.body().sourcePos(), new Expr.Param(pair._1.sourcePos(), pair._1.data(), pair._2.explicit()), lamExpr));
          var field = inherit(fieldExpr, type).wellTyped;
          fields.append(Tuple.of(defField.ref(), field));
          subst.add(defField.ref(), field);
        }

        if (missing.isNotEmpty())
          yield fail(newExpr, structCall, new FieldProblem.MissingFieldError(newExpr.sourcePos(), missing.toImmutableSeq()));
        if (conFields.isNotEmpty())
          yield fail(newExpr, structCall, new FieldProblem.NoSuchFieldError(newExpr.sourcePos(), conFields.map(Expr.Field::name)));
        yield new Result(new IntroTerm.New(structCall, ImmutableMap.from(fields)), structCall);
      }
      case Expr.ProjExpr proj -> {
        var struct = proj.tup();
        var projectee = instImplicits(synthesize(struct), struct.sourcePos());
        yield proj.ix().fold(ix -> {
            if (!(projectee.type instanceof FormTerm.Sigma sigma))
              return fail(struct, projectee.type, BadTypeError.sigmaAcc(struct, ix, projectee.type));
            var telescope = sigma.params();
            var index = ix - 1;
            if (index < 0 || index >= telescope.size())
              return fail(proj, new ProjIxError(proj, ix, telescope.size()));
            var type = telescope.get(index).type();
            var subst = ElimTerm.Proj.projSubst(projectee.wellTyped, index, telescope);
            return new Result(new ElimTerm.Proj(projectee.wellTyped, 0, ix), type.subst(subst));
          }, sp -> {
            var fieldName = sp.justName();
            if (!(projectee.type instanceof CallTerm.Struct structCall))
              return fail(struct, ErrorTerm.unexpected(projectee.type), BadTypeError.structAcc(struct, fieldName, projectee.type));
            var structCore = structCall.ref().core;
            if (structCore == null) throw new UnsupportedOperationException("TODO");
            // TODO[ice]: instantiate the type
            if (!(proj.resolvedIx() instanceof DefVar<?, ?> defVar && defVar.core instanceof FieldDef field))
              return fail(proj, new FieldProblem.UnknownField(proj, fieldName));
            var fieldRef = field.ref();

            var structSubst = Unfolder.buildSubst(structCore.telescope(), structCall.args());
            var tele = Term.Param.subst(fieldRef.core.selfTele, structSubst, 0);
            var teleRenamed = tele.map(Term.Param::rename);
            var access = new CallTerm.Access(projectee.wellTyped, fieldRef, 0,
              structCall.args(), teleRenamed.map(Term.Param::toArg));
            return new Result(IntroTerm.Lambda.make(teleRenamed, access),
              FormTerm.Pi.make(tele, field.result().subst(structSubst)));
          }
        );
      }
      case Expr.TupExpr tuple -> {
        var items = tuple.items().map(this::synthesize);
        yield new Result(new IntroTerm.Tuple(items.map(Result::wellTyped)),
          new FormTerm.Sigma(items.map(item -> new Term.Param(Constants.anonymous(), item.type, true))));
      }
      case Expr.AppExpr appE -> {
        var f = synthesize(appE.function());
        if (f.wellTyped instanceof ErrorTerm || f.type instanceof ErrorTerm) yield f;
        var app = f.wellTyped;
        var argument = appE.argument();
        var fTy = f.type.normalize(state, NormalizeMode.WHNF);
        var argLicit = argument.explicit();
        if (fTy instanceof CallTerm.Hole fTyHole) {
          // [ice] Cannot 'generatePi' because 'generatePi' takes the current contextTele,
          // but it may contain variables absent from the 'contextTele' of 'fTyHole.ref.core'
          var pi = fTyHole.asPi(argLicit);
          unifier(appE.sourcePos(), Ordering.Eq).compare(fTy, pi, null);
          fTy = fTy.normalize(state, NormalizeMode.WHNF);
        }
        if (!(fTy instanceof FormTerm.Pi piTerm))
          yield fail(appE, f.type, BadTypeError.pi(appE, f.type));
        var pi = piTerm;
        var subst = new Substituter.TermSubst(MutableMap.create());
        try {
          while (pi.param().explicit() != argLicit ||
            argument.name() != null && !Objects.equals(pi.param().ref().name(), argument.name())) {
            if (argLicit || argument.name() != null) {
              // that implies paramLicit == false
              var holeApp = mockArg(pi.param().subst(subst), argument.expr().sourcePos());
              app = CallTerm.make(app, holeApp);
              subst.addDirectly(pi.param().ref(), holeApp.term());
              pi = ensurePiOrThrow(pi.body());
            } else yield fail(appE, new ErrorTerm(pi.body()), new LicitProblem.UnexpectedImplicitArgError(argument));
          }
          pi = ensurePiOrThrow(pi.subst(subst).normalize(state, NormalizeMode.WHNF));
        } catch (NotPi notPi) {
          yield fail(expr, ErrorTerm.unexpected(notPi.what), BadTypeError.pi(expr, notPi.what));
        }
        var elabArg = inherit(argument.expr(), pi.param().type()).wellTyped;
        app = CallTerm.make(app, new Arg<>(elabArg, argLicit));
        subst.addDirectly(pi.param().ref(), elabArg);
        yield new Result(app, pi.body().subst(subst));
      }
      case Expr.HoleExpr hole -> inherit(hole, localCtx.freshHole(null,
        Constants.randomName(hole), hole.sourcePos())._2);
      default -> new Result(ErrorTerm.unexpected(expr), new ErrorTerm(Doc.english("no rule"), false));
    };
  }

  private Term instImplicits(@NotNull Term term, @NotNull SourcePos pos) {
    term = term.normalize(state, NormalizeMode.WHNF);
    while (term instanceof IntroTerm.Lambda intro && !intro.param().explicit()) {
      term = CallTerm.make(intro, mockArg(intro.param(), pos)).normalize(state, NormalizeMode.WHNF);
    }
    return term;
  }

  private Result instImplicits(@NotNull Result result, @NotNull SourcePos pos) {
    var type = result.type.normalize(state, NormalizeMode.WHNF);
    var term = result.wellTyped;
    while (type instanceof FormTerm.Pi pi && !pi.param().explicit()) {
      var holeApp = mockArg(pi.param(), pos);
      term = CallTerm.make(term, holeApp);
      type = pi.substBody(holeApp.term()).normalize(state, NormalizeMode.WHNF);
    }
    return new Result(term, type);
  }

  private static final class NotPi extends Exception {
    private final @NotNull Term what;

    public NotPi(@NotNull Term what) {
      this.what = what;
    }
  }

  private FormTerm.@NotNull Pi ensurePiOrThrow(@NotNull Term term) throws NotPi {
    if (term instanceof FormTerm.Pi pi) return pi;
    else throw new NotPi(term);
  }

  private @NotNull Result doInherit(@NotNull Expr expr, @NotNull Term term) {
    return switch (expr) {
      case Expr.TupExpr tuple -> {
        var items = DynamicSeq.<Term>create();
        var resultTele = DynamicSeq.<Term.@NotNull Param>create();
        var typeWHNF = term.normalize(state, NormalizeMode.WHNF);
        if (typeWHNF instanceof CallTerm.Hole hole) yield unifyTyMaybeInsert(hole, synthesize(tuple), tuple);
        if (!(typeWHNF instanceof FormTerm.Sigma dt))
          yield fail(tuple, term, BadTypeError.sigmaCon(tuple, term));
        var againstTele = dt.params().view();
        var last = dt.params().last().type();
        var subst = new Substituter.TermSubst(MutableMap.create());
        for (var iter = tuple.items().iterator(); iter.hasNext(); ) {
          var item = iter.next();
          var first = againstTele.first().subst(subst);
          var result = inherit(item, first.type());
          items.append(result.wellTyped);
          var ref = first.ref();
          resultTele.append(new Term.Param(ref, result.type, first.explicit()));
          againstTele = againstTele.drop(1);
          if (againstTele.isNotEmpty()) subst.add(ref, result.wellTyped);
          else if (iter.hasNext()) {
            yield fail(tuple, term, new TupleProblem.ElemMismatchError(tuple.sourcePos(), dt.params().size(), tuple.items().size()));
          } else items.append(inherit(item, last.subst(subst)).wellTyped);
        }
        var resTy = new FormTerm.Sigma(resultTele.toImmutableSeq());
        yield new Result(new IntroTerm.Tuple(items.toImmutableSeq()), resTy);
      }
      case Expr.HoleExpr hole -> {
        // TODO[ice]: deal with unit type
        var freshHole = localCtx.freshHole(term, Constants.randomName(hole), hole.sourcePos());
        if (hole.explicit()) reporter.report(new Goal(state, freshHole._1, hole.accessibleLocal().value));
        yield new Result(freshHole._2, term);
      }
      case Expr.UnivExpr univExpr -> {
        var normTerm = term.normalize(state, NormalizeMode.WHNF);
        if (normTerm instanceof FormTerm.Univ univ) {
          if (univExpr.lift() + 1 > univ.lift()) reporter.report(
            new LevelError(univExpr.sourcePos(), univExpr.lift() + 1, univ.lift(), false));
          yield new Result(new FormTerm.Univ(univExpr.lift()), univ);
        } else {
          var succ = new FormTerm.Univ(univExpr.lift());
          unifyTyReported(normTerm, succ, univExpr);
        }
        yield new Result(new FormTerm.Univ(univExpr.lift()), term);
      }
      case Expr.LamExpr lam -> {
        if (term instanceof CallTerm.Hole) unifyTy(term, generatePi(lam), lam.sourcePos());
        if (!(term.normalize(state, NormalizeMode.WHNF) instanceof FormTerm.Pi dt)) {
          yield fail(lam, term, BadTypeError.pi(lam, term));
        }
        var param = lam.param();
        if (param.explicit() != dt.param().explicit()) {
          yield fail(lam, dt, new LicitProblem.LicitMismatchError(lam, dt));
        }
        var var = param.ref();
        var lamParam = param.type();
        var type = dt.param().type();
        var result = synthesize(lamParam).wellTyped;
        var comparison = unifyTy(result, type, lamParam.sourcePos());
        if (comparison != null) {
          // TODO: maybe also report this unification failure?
          yield fail(lam, dt, BadTypeError.lamParam(lam, type, result));
        } else type = result;
        var resultParam = new Term.Param(var, type, param.explicit());
        var body = dt.substBody(resultParam.toTerm());
        yield localCtx.with(resultParam, () -> {
          var rec = inherit(lam.body(), body);
          return new Result(new IntroTerm.Lambda(resultParam, rec.wellTyped), dt);
        });
      }
      default -> unifyTyMaybeInsert(term, synthesize(expr), expr);
    };
  }

  private void traceExit(Result result, @NotNull Expr expr) {
    var frozen = LazyValue.of(() -> result.freezeHoles(state));
    tracing(builder -> {
      builder.append(new Trace.TyckT(frozen.get(), expr.sourcePos()));
      builder.reduce();
    });
    // assert validate(result.wellTyped);
    // assert validate(result.type);
    if (expr instanceof Expr.WithTerm withTerm) withTerm.theCore().set(frozen.get());
  }

  /*
  @TestOnly @Contract(pure = true) private boolean validate(Term term) {
    var visitor = new TermConsumer<Unit>() {
      boolean ok = true;

      @Override public void visitSort(@NotNull Sort sort, Unit unit) {
        for (var level : sort.levels())
          if (level instanceof Level.Reference<Sort.LvlVar> r && !r.ref().free() &&
            !(r.ref() == universe || levelMapping.valuesView().contains(r.ref())))
            ok = false;
      }
    };
    term.accept(visitor, Unit.unit());
    return visitor.ok;
  }
  */

  public ExprTycker(@NotNull Reporter reporter, Trace.@Nullable Builder traceBuilder) {
    this.reporter = reporter;
    this.traceBuilder = traceBuilder;
  }

  public void solveMetas() {
    state.solveMetas(reporter, traceBuilder);
  }

  public @NotNull Result inherit(@NotNull Expr expr, @NotNull Term type) {
    tracing(builder -> builder.shift(new Trace.ExprT(expr, type.freezeHoles(state))));
    Result result;
    if (type instanceof FormTerm.Pi pi && !pi.param().explicit() && needImplicitParamIns(expr)) {
      var implicitParam = new Term.Param(new LocalVar(Constants.ANONYMOUS_PREFIX), pi.param().type(), false);
      var body = localCtx.with(implicitParam, () ->
        inherit(expr, pi.substBody(implicitParam.toTerm()))).wellTyped;
      result = new Result(new IntroTerm.Lambda(implicitParam, body), pi);
    } else result = doInherit(expr, type);
    traceExit(result, expr);
    return result;
  }

  public @NotNull Result synthesize(@NotNull Expr expr) {
    tracing(builder -> builder.shift(new Trace.ExprT(expr, null)));
    var res = doSynthesize(expr);
    traceExit(res, expr);
    return res;
  }

  private static boolean needImplicitParamIns(@NotNull Expr expr) {
    return expr instanceof Expr.LamExpr ex && ex.param().explicit()
      || !(expr instanceof Expr.LamExpr);
  }

  public @NotNull Result zonk(@NotNull Expr expr, @NotNull Result result) {
    solveMetas();
    var pos = expr.sourcePos();
    var zonker = newZonker();
    return new Result(zonker.zonk(result.wellTyped, pos), zonker.zonk(result.type, pos));
  }

  private @NotNull Term generatePi(Expr.@NotNull LamExpr expr) {
    var param = expr.param();
    return generatePi(expr.sourcePos(), param.ref().name(), param.explicit());
  }

  private @NotNull Term generatePi(@NotNull SourcePos pos, @NotNull String name, boolean explicit) {
    var genName = name + Constants.GENERATED_POSTFIX;
    // [ice]: unsure if ZERO is good enough
    var domain = localCtx.freshHole(FormTerm.Univ.ZERO, genName + "ty", pos)._2;
    var codomain = localCtx.freshHole(FormTerm.Univ.ZERO, pos)._2;
    return new FormTerm.Pi(new Term.Param(new LocalVar(genName, pos), domain, explicit), codomain);
  }

  private @NotNull Result fail(@NotNull AyaDocile expr, @NotNull Problem prob) {
    return fail(expr, ErrorTerm.typeOf(expr), prob);
  }

  private @NotNull Result fail(@NotNull AyaDocile expr, @NotNull Term term, @NotNull Problem prob) {
    reporter.report(prob);
    return new Result(new ErrorTerm(expr), term);
  }

  @SuppressWarnings("unchecked")
  private @NotNull Result inferRef(@NotNull SourcePos pos, @NotNull DefVar<?, ?> var) {
    if (var.core instanceof FnDef || var.concrete instanceof Decl.FnDecl) {
      return defCall(pos, (DefVar<FnDef, Decl.FnDecl>) var, CallTerm.Fn::new);
    } else if (var.core instanceof PrimDef) {
      return defCall(pos, (DefVar<PrimDef, Decl.PrimDecl>) var, CallTerm.Prim::new);
    } else if (var.core instanceof DataDef || var.concrete instanceof Decl.DataDecl) {
      return defCall(pos, (DefVar<DataDef, Decl.DataDecl>) var, CallTerm.Data::new);
    } else if (var.core instanceof StructDef || var.concrete instanceof Decl.StructDecl) {
      return defCall(pos, (DefVar<StructDef, Decl.StructDecl>) var, CallTerm.Struct::new);
    } else if (var.core instanceof CtorDef || var.concrete instanceof Decl.DataDecl.DataCtor) {
      var conVar = (DefVar<CtorDef, Decl.DataDecl.DataCtor>) var;
      var tele = Def.defTele(conVar);
      var type = FormTerm.Pi.make(tele, Def.defResult(conVar));
      var telescopes = CtorDef.telescopes(conVar).rename();
      var body = telescopes.toConCall(conVar);
      return new Result(IntroTerm.Lambda.make(telescopes.params(), body), type);
    } else if (var.core instanceof FieldDef || var.concrete instanceof Decl.StructField) {
      // the code runs to here because we are tycking a StructField in a StructDecl
      // there should be two-stage check for this case:
      //  - check the definition's correctness: happens here
      //  - check the field value's correctness: happens in `visitNew` after the body was instantiated
      var field = (DefVar<FieldDef, Decl.StructField>) var;
      return new Result(new RefTerm.Field(field, 0), Def.defType(field));
    } else {
      final var msg = "Def var `" + var.name() + "` has core `" + var.core + "` which we don't know.";
      throw new IllegalStateException(msg);
    }
  }

  private @NotNull <D extends Def, S extends Signatured> ExprTycker.Result
  defCall(@NotNull SourcePos pos, DefVar<D, S> defVar, CallTerm.Factory<D, S> function) {
    var tele = Def.defTele(defVar);
    var teleRenamed = tele.map(Term.Param::rename);
    // unbound these abstracted variables
    Term body = function.make(defVar, 0, teleRenamed.map(Term.Param::toArg));
    var type = FormTerm.Pi.make(tele, Def.defResult(defVar));
    if (defVar.core instanceof FnDef fn && fn.modifiers.contains(Modifier.Inline)) {
      body = body.normalize(state, NormalizeMode.WHNF);
    }
    return new Result(IntroTerm.Lambda.make(teleRenamed, body), type);
  }

  /** @return null if unified successfully */
  private DefEq.FailureData unifyTy(@NotNull Term upper, @NotNull Term lower, @NotNull SourcePos pos) {
    tracing(builder -> builder.append(new Trace.UnifyT(lower, upper, pos)));
    var unifier = unifier(pos, Ordering.Lt);
    if (!unifier.compare(lower, upper, null)) return unifier.getFailure();
    else return null;
  }

  public @NotNull DefEq unifier(@NotNull SourcePos pos, @NotNull Ordering ord) {
    return unifier(pos, ord, localCtx);
  }

  public @NotNull DefEq unifier(@NotNull SourcePos pos, @NotNull Ordering ord, @NotNull LocalCtx ctx) {
    return new DefEq(ord, reporter, false, true, traceBuilder, state, pos, ctx);
  }

  /**
   * Check if <code>lower</code> is a subtype of <code>upper</code>,
   * and report a type error if it's not the case.
   *
   * @see ExprTycker#unifyTyMaybeInsert(Term, Result, Expr)
   */
  void unifyTyReported(@NotNull Term upper, @NotNull Term lower, Expr loc) {
    var unification = unifyTy(upper, lower, loc.sourcePos());
    if (unification != null) reporter.report(new UnifyError(loc, upper, lower, unification));
  }

  /**
   * Check if <code>lower</code> is a subtype of <code>upper</code>,
   * and try to insert implicit arguments to fulfill this goal (if possible).
   *
   * @return the term and type after insertion
   * @see ExprTycker#unifyTyReported(Term, Term, Expr)
   */
  private Result unifyTyMaybeInsert(@NotNull Term upper, @NotNull Result result, Expr loc) {
    var lower = result.type;
    var term = result.wellTyped;
    while (lower.normalize(state, NormalizeMode.WHNF) instanceof FormTerm.Pi pi && !pi.param().explicit()) {
      var mock = mockArg(pi.param(), loc.sourcePos());
      term = CallTerm.make(term, mock);
      lower = pi.substBody(mock.term());
    }
    var failureData = unifyTy(upper, lower, loc.sourcePos());
    if (failureData == null) return new Result(term, lower);
    return fail(term.freezeHoles(state), upper, new UnifyError(loc,
      upper.freezeHoles(state), lower.freezeHoles(state), failureData));
  }

  public int ensureUniv(@NotNull Expr expr, @NotNull Term term) {
    return switch (term.normalize(state, NormalizeMode.WHNF)) {
      case FormTerm.Univ univ -> univ.lift();
      case CallTerm.Hole hole -> {
        // TODO[lift-meta]: should be able to solve a lifted meta
        unifyTyReported(hole, FormTerm.Univ.ZERO, expr);
        yield hole.ulift();
      }
      default -> {
        reporter.report(BadTypeError.univ(expr, term));
        yield 0;
      }
    };
  }

  private @NotNull Term mockTerm(Term.Param param, SourcePos pos) {
    // TODO: maybe we should create a concrete hole and check it against the type
    //  in case we can synthesize this term via its type only
    var genName = param.ref().name().concat(Constants.GENERATED_POSTFIX);
    return localCtx.freshHole(param.type(), genName, pos)._2;
  }

  private @NotNull Arg<Term> mockArg(Term.Param param, SourcePos pos) {
    return new Arg<>(mockTerm(param, pos), param.explicit());
  }

  public static class TyckerException extends InternalException {
    @Override public void printHint() {
      System.err.println("A type error was discovered during type checking.");
    }

    @Override public int exitCode() {
      return 2;
    }
  }

  /**
   * {@link Result#type} is the type of {@link Result#wellTyped}.
   *
   * @author ice1000
   */
  public record Result(@NotNull Term wellTyped, @NotNull Term type) {
    @Contract(value = " -> new", pure = true) public @NotNull Tuple2<Term, Term> toTuple() {
      return Tuple.of(type, wellTyped);
    }

    public @NotNull Result freezeHoles(@NotNull TyckState state) {
      return new Result(wellTyped.freezeHoles(state), type.freezeHoles(state));
    }
  }
}
