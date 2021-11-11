// Copyright (c) 2020-2021 Yinsen (Tesla) Zhang.
// Use of this source code is governed by the MIT license that can be found in the LICENSE.md file.
package org.aya.concrete.resolve.visitor;

import kala.collection.mutable.DynamicSeq;
import kala.tuple.Tuple;
import kala.tuple.Tuple2;
import kala.tuple.Unit;
import kala.value.Ref;
import org.aya.api.error.Reporter;
import org.aya.api.error.SourcePos;
import org.aya.api.ref.DefVar;
import org.aya.concrete.desugar.BinOpSet;
import org.aya.concrete.remark.Remark;
import org.aya.concrete.resolve.ResolveInfo;
import org.aya.concrete.resolve.context.Context;
import org.aya.concrete.resolve.error.UnknownOperatorError;
import org.aya.concrete.stmt.*;
import org.aya.util.MutableGraph;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Resolves expressions inside stmts, after {@link StmtShallowResolver}
 *
 * @author re-xyr, ice1000, kiva
 * @see StmtShallowResolver
 * @see ExprResolver
 */
public final class StmtResolver implements Stmt.Visitor<ResolveInfo, Unit> {
  public static final @NotNull StmtResolver INSTANCE = new StmtResolver();

  private StmtResolver() {
  }

  @Override public Unit visitModule(Command.@NotNull Module mod, ResolveInfo info) {
    visitAll(mod.contents(), info);
    return Unit.unit();
  }

  @Override public Unit visitImport(Command.@NotNull Import cmd, ResolveInfo info) {
    return Unit.unit();
  }

  @Override public Unit visitOpen(Command.@NotNull Open cmd, ResolveInfo info) {
    return Unit.unit();
  }

  public void visitBind(@NotNull OpDecl self, OpDecl.@Nullable BindBlock bind, ResolveInfo info) {
    if (bind == null) return;
    var ctx = bind.context().value;
    assert ctx != null : "no shallow resolver?";
    bind.resolvedLoosers().value = bind.loosers().map(looser -> bind(looser.sourcePos(), self, info.opSet(), ctx, OpDecl.BindPred.Looser, looser));
    bind.resolvedTighters().value = bind.tighters().map(tighter -> bind(tighter.sourcePos(), self, info.opSet(), ctx, OpDecl.BindPred.Tighter, tighter));
  }

  private @NotNull DefVar<?, ?> bind(@NotNull SourcePos sourcePos, @NotNull OpDecl self, @NotNull BinOpSet opSet, @NotNull Context ctx,
                                     @NotNull OpDecl.BindPred pred, @NotNull QualifiedID id) {
    var target = resolveOp(opSet.reporter(), ctx, id);
    opSet.bind(self, pred, target._2, sourcePos);
    return target._1;
  }

  private @NotNull Tuple2<DefVar<?, ?>, OpDecl> resolveOp(@NotNull Reporter reporter, @NotNull Context ctx, @NotNull QualifiedID id) {
    var var = ctx.get(id);
    if (var instanceof DefVar<?, ?> defVar && defVar.concrete instanceof OpDecl op) return Tuple.of(defVar, op);
    reporter.report(new UnknownOperatorError(id.sourcePos(), id.join()));
    throw new Context.ResolvingInterruptedException();
  }

  @Override public Unit visitCtor(@NotNull Decl.DataCtor ctor, ResolveInfo info) {
    throw new UnsupportedOperationException();
  }

  @Override public Unit visitField(@NotNull Decl.StructField field, ResolveInfo info) {
    throw new UnsupportedOperationException();
  }

  /** @apiNote Note that this function MUTATES the decl. */
  @Override public Unit visitData(Decl.@NotNull DataDecl decl, ResolveInfo info) {
    var reference = DynamicSeq.<Stmt>create();
    var signatureResolver = new ExprResolver(true, DynamicSeq.create(), reference);
    var local = signatureResolver.resolveParams(decl.telescope, decl.ctx);
    decl.telescope = local._1;
    decl.result = decl.result.accept(signatureResolver, local._2);
    var bodyResolver = new ExprResolver(false, signatureResolver.allowedLevels(), reference);
    for (var ctor : decl.body) {
      var localCtxWithPat = new Ref<>(local._2);
      ctor.patterns = ctor.patterns.map(pattern -> PatResolver.INSTANCE.subpatterns(localCtxWithPat, pattern));
      var ctorLocal = bodyResolver.resolveParams(ctor.telescope, localCtxWithPat.value);
      ctor.telescope = ctorLocal._1;
      ctor.clauses = ctor.clauses.map(clause -> PatResolver.INSTANCE.matchy(clause, ctorLocal._2, bodyResolver));
      visitBind(ctor, ctor.bindBlock, info);
    }
    visitBind(decl, decl.bindBlock, info);
    info.declGraph().suc(decl).appendAll(reference);
    return Unit.unit();
  }

  @Override public Unit visitStruct(Decl.@NotNull StructDecl decl, ResolveInfo info) {
    var reference = DynamicSeq.<Stmt>create();
    var signatureResolver = new ExprResolver(true, DynamicSeq.create(), reference);
    var local = signatureResolver.resolveParams(decl.telescope, decl.ctx);
    decl.telescope = local._1;
    decl.result = decl.result.accept(signatureResolver, local._2);
    var bodyResolver = new ExprResolver(false, signatureResolver.allowedLevels(), reference);
    decl.fields.forEach(field -> {
      var fieldLocal = bodyResolver.resolveParams(field.telescope, local._2);
      field.telescope = fieldLocal._1;
      field.result = field.result.accept(bodyResolver, fieldLocal._2);
      field.body = field.body.map(e -> e.accept(bodyResolver, fieldLocal._2));
      field.clauses = field.clauses.map(clause -> PatResolver.INSTANCE.matchy(clause, fieldLocal._2, bodyResolver));
      visitBind(field, field.bindBlock, info);
    });
    visitBind(decl, decl.bindBlock, info);
    info.declGraph().suc(decl).appendAll(reference);
    return Unit.unit();
  }

  /** @apiNote Note that this function MUTATES the decl. */
  @Override public Unit visitFn(Decl.@NotNull FnDecl decl, ResolveInfo info) {
    var reference = DynamicSeq.<Stmt>create();
    var signatureResolver = new ExprResolver(true, DynamicSeq.create(), reference);
    var local = signatureResolver.resolveParams(decl.telescope, decl.ctx);
    decl.telescope = local._1;
    decl.result = decl.result.accept(signatureResolver, local._2);
    var bodyResolver = new ExprResolver(false, signatureResolver.allowedLevels(), reference);
    decl.body = decl.body.map(
      expr -> expr.accept(bodyResolver, local._2),
      pats -> pats.map(clause -> PatResolver.INSTANCE.matchy(clause, local._2, bodyResolver)));
    visitBind(decl, decl.bindBlock, info);
    info.declGraph().suc(decl).appendAll(reference);
    return Unit.unit();
  }

  @Override public Unit visitPrim(@NotNull Decl.PrimDecl decl, ResolveInfo info) {
    var resolver = new ExprResolver(false, DynamicSeq.create(), DynamicSeq.create());
    var local = resolver.resolveParams(decl.telescope, decl.ctx);
    decl.telescope = local._1;
    if (decl.result != null) decl.result = decl.result.accept(resolver, local._2);
    info.declGraph().suc(decl).appendAll(resolver.reference());
    return Unit.unit();
  }

  @Override public Unit visitLevels(Generalize.@NotNull Levels levels, ResolveInfo info) {
    return Unit.unit();
  }

  @Override public Unit visitExample(Sample.@NotNull Working example, ResolveInfo info) {
    visitSample(example, info);
    return Unit.unit();
  }

  @Override public Unit visitCounterexample(Sample.@NotNull Counter example, ResolveInfo info) {
    visitSample(example, info);
    return Unit.unit();
  }

  private void visitSample(@NotNull Sample sample, ResolveInfo info) {
    var delegate = sample.delegate();
    var delegateInfo = new ResolveInfo(info.opSet(), MutableGraph.create(), MutableGraph.create());
    delegate.accept(this, delegateInfo);
    // little hacky: transfer dependencies from `delegate` to `sample`
    info.sampleGraph().suc(sample).appendAll(delegateInfo.declGraph().suc(delegate));
  }

  @Override public Unit visitRemark(@NotNull Remark remark, ResolveInfo info) {
    info.sampleGraph().suc(remark).appendAll(remark.doResolve(info));
    return Unit.unit();
  }
}
