// Copyright (c) 2020-2022 Tesla (Yinsen) Zhang.
// Use of this source code is governed by the MIT license that can be found in the LICENSE.md file.
package org.aya.distill;

import kala.collection.immutable.ImmutableSeq;
import kala.collection.mutable.MutableLinkedHashMap;
import kala.collection.mutable.MutableMap;
import org.aya.core.def.FnDef;
import org.aya.core.term.*;
import org.aya.generic.Arg;
import org.aya.guest0x0.cubical.Formula;
import org.aya.guest0x0.cubical.Partial;
import org.aya.guest0x0.cubical.Restr;
import org.aya.ref.LocalVar;
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
      // If this `get` fails, it means we have an incorrectly-scoped
      // term in the core, which should be a bug
      case RefTerm(var var) -> varRef(locals.get(var));
      case ElimTerm.App(var of, var arg) -> {
        builder.append("new ElimTerm.App(");
        term(of);
        builder.append(",");
        arg(arg);
        builder.append(")");
      }
      case ElimTerm.Proj(var of, var ix) -> {
        builder.append("new ElimTerm.Proj(");
        term(of);
        builder.append(",").append(ix).append(")");
      }
      case FormTerm.PartTy(var ty, var restr) -> coePar(ty, restr, "FormTerm.PartTy");
      case FormTerm.Path(FormTerm.Cube(var params, var ty, var par)) -> {
        builder.append("new FormTerm.Path(new FormTerm.Cube(ImmutableSeq.of(");
        commaSep(params, this::varDef);
        builder.append("),");
        term(ty);
        builder.append(",");
        partial(par);
        builder.append("))");
      }
      case FormTerm.Pi(var param, var body) -> piLam(param, body, "FormTerm.Pi");
      case FormTerm.Sigma(var items) -> tupSigma(items, this::param, "FormTerm.Sigma");
      case IntroTerm.Lambda(var param, var body) -> piLam(param, body, "IntroTerm.Lambda");
      case IntroTerm.PartEl(var par, var ty) -> {
        builder.append("new IntroTerm.PartEl(");
        partial(par);
        builder.append(",");
        term(ty);
        builder.append(")");
      }
      case IntroTerm.PathLam(var params, var body) -> {
        builder.append("new IntroTerm.PathLam(ImmutableSeq.of(");
        commaSep(params, this::varDef);
        builder.append("),");
        term(body);
        builder.append(")");
      }
      case IntroTerm.Tuple(var items) -> tupSigma(items, this::term, "IntroTerm.Tuple");
      case PrimTerm.Coe(var ty, var restr) -> coePar(ty, restr, "PrimTerm.Coe");
      case PrimTerm.Mula(var mula) -> formula(mula);
      case ErrorTerm error -> throw new UnsupportedOperationException("Cannot generate error");
      case ErasedTerm erased -> throw new UnsupportedOperationException("Cannot generate erased");
      case CallTerm call -> throw new UnsupportedOperationException("Cannot generate calls");
      case PrimTerm.Interval interval -> builder.append("PrimTerm.Interval.INSTANCE");
      case FormTerm.ISet iSet -> builder.append("FormTerm.ISet.INSTANCE");
      case FormTerm.Prop prop -> builder.append("FormTerm.Prop.INSTANCE");
      case FormTerm.Set(var lift) -> universe("Set", lift);
      case FormTerm.Type(var lift) -> universe("Type", lift);
      case default -> throw new UnsupportedOperationException("TODO: " + term.getClass().getCanonicalName());
    }
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
    return me.builder;
  }

  private void partial(Partial<Term> par) {
    throw new UnsupportedOperationException("TODO");
  }

  private void restr(Restr<Term> restr) {
    throw new UnsupportedOperationException("TODO");
  }

  private void formula(Formula<Term> mula) {
    throw new UnsupportedOperationException("TODO");
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

  private void universe(String s, int lift) {
    builder.append("new FormTerm.").append(s).append("(").append(lift).append(")");
  }
}
