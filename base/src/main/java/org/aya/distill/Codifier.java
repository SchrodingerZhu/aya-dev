// Copyright (c) 2020-2022 Tesla (Yinsen) Zhang.
// Use of this source code is governed by the MIT license that can be found in the LICENSE.md file.
package org.aya.distill;

import com.intellij.openapi.util.text.StringUtil;
import kala.collection.immutable.ImmutableSeq;
import kala.collection.mutable.MutableLinkedHashMap;
import kala.collection.mutable.MutableMap;
import org.aya.core.def.FnDef;
import org.aya.core.term.*;
import org.aya.guest0x0.cubical.Formula;
import org.aya.guest0x0.cubical.Partial;
import org.aya.guest0x0.cubical.Restr;
import org.aya.ref.LocalVar;
import org.aya.util.Arg;
import org.jetbrains.annotations.NotNull;

import java.util.function.Consumer;

/**
 * Do not need to use {@link org.aya.pretty.doc.Doc},
 * because we do not care about output format.
 */
public record Codifier(
  @NotNull MutableMap<LocalVar, Integer> locals,
  @NotNull StringBuilder builder
) {
  private void term(@NotNull Term term) {
    switch (term) {
      case RefTerm(var var) -> {
        builder.append("new RefTerm(");
        // If this `get` fails, it means we have an incorrectly-scoped
        // term in the core, which should be a bug
        varRef(locals.get(var));
        builder.append(")");
      }
      case AppTerm(var of, var arg) -> {
        builder.append("new AppTerm(");
        term(of);
        builder.append(",");
        arg(arg);
        builder.append(")");
      }
      case ProjTerm(var of, var ix) -> {
        builder.append("new ProjTerm(");
        term(of);
        builder.append(",").append(ix).append(")");
      }
      case PartialTyTerm(var ty, var restr) -> coePar(ty, restr, "PartialTyTerm");
      case PathTerm cube -> cube(cube);
      case PiTerm(var param, var body) -> piLam(param, body, "PiTerm");
      case SigmaTerm(var items) -> tupSigma(items, this::param, "SigmaTerm");
      case LamTerm(var param, var body) -> piLam(param, body, "LamTerm");
      case PartialTerm(var par, var ty) -> {
        builder.append("new PartialTerm(");
        partial(par);
        builder.append(",");
        term(ty);
        builder.append(")");
      }
      case PLamTerm(var params, var body) -> {
        builder.append("new PLamTerm(ImmutableSeq.of(");
        commaSep(params, this::varDef);
        builder.append("),");
        term(body);
        builder.append(")");
      }
      case PAppTerm(var of, var args, var cube) -> {
        builder.append("new PAppTerm(");
        term(of);
        builder.append(",ImmutableSeq.of(");
        commaSep(args, this::arg);
        builder.append("),");
        cube(cube);
        builder.append(")");
      }
      case TupTerm(var items) -> tupSigma(items, this::term, "TupTerm");
      case CoeTerm(var ty, var restr) -> coePar(ty, restr, "CoeTerm");
      case FormulaTerm(var mula) -> {
        builder.append("new FormulaTerm(");
        formula(mula);
        builder.append(")");
      }
      case ErrorTerm error -> throw new UnsupportedOperationException("Cannot generate error");
      case Callable call -> throw new UnsupportedOperationException("Cannot generate calls");
      case IntervalTerm interval -> builder.append("IntervalTerm.INSTANCE");
      case SortTerm sort -> {
        builder.append("new SortTerm(SortKind.");
        builder.append(sort.kind().name());
        builder.append(", ");
        builder.append(sort.lift());
        builder.append(")");
      }
      case default -> throw new UnsupportedOperationException("TODO: " + term.getClass().getCanonicalName());
    }
  }

  private void cube(@NotNull PathTerm cube) {
    builder.append("new PathTerm(ImmutableSeq.of(");
    commaSep(cube.params(), this::varDef);
    builder.append("),");
    term(cube.type());
    builder.append(",");
    partial(cube.partial());
    builder.append(")");
  }

  private void name(String name) {
    builder.append("new ").append(name).append("(");
  }

  private void coePar(@NotNull Term ty, @NotNull Restr<Term> restr, String name) {
    name(name);
    term(ty);
    builder.append(",");
    restr(restr);
    builder.append(")");
  }

  private void piLam(Term.@NotNull Param param, @NotNull Term body, String name) {
    name(name);
    param(param);
    builder.append(",");
    term(body);
    builder.append(")");
  }

  private <T> void tupSigma(@NotNull ImmutableSeq<T> items, Consumer<T> f, String name) {
    builder.append("new ").append(name).append("(ImmutableSeq.of(");
    commaSep(items, f);
    builder.append("))");
  }

  private <T> void commaSep(@NotNull ImmutableSeq<T> items, Consumer<T> f) {
    var started = false;
    for (var item : items) {
      if (!started) started = true;
      else builder.append(",");
      f.accept(item);
    }
  }

  public static @NotNull CharSequence sweet(@NotNull FnDef def) {
    var me = new Codifier(new MutableLinkedHashMap<>(), new StringBuilder());
    for (var param : def.telescope) {
      me.locals.put(param.ref(), me.locals.size());
    }
    me.term(def.body.getLeftValue());
    var pre = new StringBuilder(me.builder.length() + me.locals.size() * 36);
    me.locals.forEach((k, v) -> pre.append("var var")
      .append(v).append(" = new LocalVar(\"")
      .append(StringUtil.escapeStringCharacters(k.name())).append("\");"));
    return pre.append(me.builder);
  }

  private void partial(Partial<Term> par) {
    switch (par) {
      case Partial.Split<Term> s -> {
        builder.append("new Partial.Split<>(ImmutableSeq.of(");
        commaSep(s.clauses(), side -> {
          builder.append("new Restr.Side<>(");
          restr(side.cof());
          builder.append(",");
          term(side.u());
          builder.append(")");
        });
        builder.append("))");
      }
      case Partial.Const<Term> c -> {
        builder.append("new Partial.Const<>(");
        term(c.u());
        builder.append(")");
      }
    }
  }

  private void cond(Restr.Cond<Term> cond) {
    builder.append("new Restr.Cond<>(");
    term(cond.inst());
    builder.append(",").append(cond.isOne()).append(")");
  }

  private void restr(Restr.Conj<Term> restr) {
    builder.append("new Restr.Conj<>(ImmutableSeq.of(");
    commaSep(restr.ands(), this::cond);
    builder.append("))");
  }

  private void restr(Restr<Term> restr) {
    switch (restr) {
      case Restr.Disj<Term>(var orz) -> {
        builder.append("new Restr.Disj<>(ImmutableSeq.of(");
        commaSep(orz, this::restr);
        builder.append("))");
      }
      case Restr.Const<Term>(var one) -> builder.append("new Restr.Const<>(").append(one).append(")");
    }
  }

  private void formula(Formula<Term> mula) {
    switch (mula) {
      case Formula.Conn<Term> conn -> {
        builder.append("new Formula.Conn<>(").append(conn.isAnd()).append(",");
        term(conn.l());
        builder.append(",");
        term(conn.r());
        builder.append(")");
      }
      case Formula.Inv<Term> inv -> {
        builder.append("new Formula.Inv<>(");
        term(inv.i());
        builder.append(")");
      }
      case Formula.Lit<Term>(var one) -> builder.append("new Formula.Lit<>(").append(one).append(")");
    }
  }

  private void param(@NotNull Term.Param param) {
    builder.append("new Term.Param(");
    varDef(param.ref());
    builder.append(",");
    term(param.type());
    builder.append(",").append(param.explicit()).append(")");
  }

  private void varDef(@NotNull LocalVar ref) {
    assert !locals.containsKey(ref) : "Duplicate bindings in core!";
    var varId = locals.size();
    locals.put(ref, varId);
    varRef(varId);
  }

  private void varRef(int varId) {
    builder.append("var").append(varId);
  }

  private void arg(@NotNull Arg<Term> arg) {
    builder.append("new Arg<>(");
    term(arg.term());
    builder.append(",").append(arg.explicit()).append(")");
  }
}
