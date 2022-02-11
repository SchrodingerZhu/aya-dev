// Copyright (c) 2020-2022 Yinsen (Tesla) Zhang.
// Use of this source code is governed by the MIT license that can be found in the LICENSE.md file.
package org.aya.core.def;

import kala.collection.Seq;
import kala.collection.SeqLike;
import kala.collection.immutable.ImmutableSeq;
import org.aya.concrete.stmt.Decl;
import org.aya.core.sort.Sort;
import org.aya.core.term.FormTerm;
import org.aya.core.term.Term;
import org.aya.core.visitor.Substituter;
import org.aya.distill.CoreDistiller;
import org.aya.pretty.doc.Doc;
import org.aya.ref.DefVar;
import org.aya.util.distill.AyaDocile;
import org.aya.util.distill.DistillerOptions;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * @author ice1000
 */
public sealed interface Def extends AyaDocile permits SubLevelDef, TopLevelDef {
  static @NotNull Term defType(@NotNull DefVar<?, ?> defVar) {
    return FormTerm.Pi.make(defTele(defVar), defResult(defVar));
  }

  static @NotNull ImmutableSeq<Term.Param> defTele(@NotNull DefVar<?, ?> defVar) {
    if (defVar.core != null) return defVar.core.telescope();
      // guaranteed as this is already a core term
    else return Objects.requireNonNull(defVar.concrete.signature).param;
  }
  static @NotNull Seq<CtorDef> dataBody(@NotNull DefVar<? extends DataDef, ? extends Decl.DataDecl> defVar) {
    if (defVar.core != null) return defVar.core.body;
      // guaranteed as this is already a core term
    else return defVar.concrete.checkedBody;
  }
  static @NotNull ImmutableSeq<Sort.LvlVar> defLevels(@NotNull DefVar<?, ?> defVar) {
    return switch (defVar.core) {
      case TopLevelDef topLevel -> topLevel.levels;
      case CtorDef ctor -> defLevels(ctor.dataRef);
      case FieldDef field -> defLevels(field.structRef);
      // guaranteed as this is already a core term
      case null -> {
        var signature = defVar.concrete.signature;
        assert signature != null : defVar.name() + " is not checked";
        yield signature.sortParam();
      }
    };
  }
  static @NotNull Term defResult(@NotNull DefVar<?, ?> defVar) {
    if (defVar.core != null) return defVar.core.result();
      // guaranteed as this is already a core term
    else return Objects.requireNonNull(defVar.concrete.signature).result;
  }
  static @NotNull ImmutableSeq<Term.Param>
  substParams(@NotNull SeqLike<Term.@NotNull Param> param, Substituter.@NotNull TermSubst subst) {
    return param.view().drop(1).map(p -> p.subst(subst)).toImmutableSeq();
  }

  @NotNull Term result();
  @NotNull DefVar<?, ?> ref();
  @NotNull ImmutableSeq<Term.Param> telescope();

  <P, R> R accept(@NotNull Visitor<P, R> visitor, P p);
  @Override default @NotNull Doc toDoc(@NotNull DistillerOptions options) {
    return new CoreDistiller(options).def(this);
  }

  /**
   * @author re-xyr
   */
  interface Visitor<P, R> {
    R visitFn(@NotNull FnDef def, P p);
    R visitData(@NotNull DataDef def, P p);
    R visitCtor(@NotNull CtorDef def, P p);
    R visitStruct(@NotNull StructDef def, P p);
    R visitField(@NotNull FieldDef def, P p);
    R visitPrim(@NotNull PrimDef def, P p);
  }

  /**
   * Signature of a definition, used in concrete and tycking.
   *
   * @author ice1000
   */
  record Signature(
    @NotNull ImmutableSeq<Sort.@NotNull LvlVar> sortParam,
    @NotNull ImmutableSeq<Term.@NotNull Param> param,
    @NotNull Term result
  ) implements AyaDocile {
    @Contract("_ -> new") public @NotNull Signature inst(@NotNull Substituter.TermSubst subst) {
      return new Signature(sortParam, substParams(param, subst), result.subst(subst));
    }

    @Override public @NotNull Doc toDoc(@NotNull DistillerOptions options) {
      return Doc.sep(Doc.sep(param.view().map(p -> p.toDoc(options))), Doc.symbol("->"), result.toDoc(options));
    }
  }
}
