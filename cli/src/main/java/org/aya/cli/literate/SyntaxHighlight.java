// Copyright (c) 2020-2022 Tesla (Yinsen) Zhang.
// Use of this source code is governed by the MIT license that can be found in the LICENSE.md file.
package org.aya.cli.literate;

import kala.collection.SeqView;
import kala.collection.immutable.ImmutableSeq;
import kala.collection.mutable.MutableList;
import kala.control.Option;
import kala.value.LazyValue;
import org.aya.cli.literate.HighlightInfo.LitKind;
import org.aya.cli.parse.AyaGKProducer;
import org.aya.concrete.Expr;
import org.aya.concrete.Pattern;
import org.aya.concrete.stmt.*;
import org.aya.concrete.visitor.StmtFolder;
import org.aya.core.def.*;
import org.aya.core.term.Term;
import org.aya.distill.BaseDistiller;
import org.aya.generic.AyaDocile;
import org.aya.parser.AyaParserDefinitionBase;
import org.aya.pretty.doc.Link;
import org.aya.ref.AnyVar;
import org.aya.ref.DefVar;
import org.aya.ref.GenerateKind;
import org.aya.ref.LocalVar;
import org.aya.util.error.SourceFile;
import org.aya.util.error.SourcePos;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** @implNote Use {@link MutableList} instead of {@link SeqView} for performance consideration. */
public class SyntaxHighlight implements StmtFolder<MutableList<HighlightInfo>> {
  /**
   * @param sourceFile If not null, provide keyword highlights too
   * @return a list of {@link HighlightInfo}, no order was expected, the elements may be duplicated
   */
  public static @NotNull ImmutableSeq<HighlightInfo> highlight(
    @NotNull Option<SourceFile> sourceFile,
    @NotNull ImmutableSeq<Stmt> program
  ) {
    var distiller = new SyntaxHighlight();
    var semantics = program.flatMap(distiller);
    if (sourceFile.isDefined()) {
      var file = sourceFile.get();
      var lexer = AyaParserDefinitionBase.createLexer(false);
      lexer.reset(file.sourceCode(), 0, file.sourceCode().length(), 0);
      var keywords = lexer.allTheWayDown()
        .view()
        .filter(x -> AyaParserDefinitionBase.KEYWORDS.contains(x.type()))
        .map(token -> {
          var sourcePos = AyaGKProducer.sourcePosOf(token, file);
          var type = new HighlightInfo.SymLit(LitKind.Keyword);
          return new HighlightInfo(sourcePos, type);
        });
      semantics = semantics.concat(keywords);
    }
    return semantics;
  }

  private @NotNull MutableList<HighlightInfo> add(@NotNull MutableList<HighlightInfo> x, @NotNull HighlightInfo info) {
    x.append(info);
    return x;
  }

  @Override public @NotNull MutableList<HighlightInfo> init() {
    return MutableList.create();
  }

  @Override public @NotNull MutableList<HighlightInfo> foldVarRef(
    @NotNull MutableList<HighlightInfo> acc,
    @NotNull AnyVar var, @NotNull SourcePos pos,
    @NotNull LazyValue<@Nullable Term> type
  ) {
    return add(acc, linkRef(pos, var, type.get()));
  }

  @Override public @NotNull MutableList<HighlightInfo> foldVarDecl(
    @NotNull MutableList<HighlightInfo> acc,
    @NotNull AnyVar var, @NotNull SourcePos pos,
    @NotNull LazyValue<@Nullable Term> type
  ) {
    if (var instanceof LocalVar v && v.isGenerated()) return acc;
    return add(acc, linkDef(pos, var, type.get()));
  }

  @Override
  public @NotNull MutableList<HighlightInfo> fold(@NotNull MutableList<HighlightInfo> acc, @NotNull Expr expr) {
    return switch (expr) {
      case Expr.LitInt lit -> add(acc, LitKind.Int.toLit(lit.sourcePos()));
      case Expr.LitString lit -> add(acc, LitKind.String.toLit(lit.sourcePos()));
      default -> StmtFolder.super.fold(acc, expr);
    };
  }

  @Override
  public @NotNull MutableList<HighlightInfo> fold(@NotNull MutableList<HighlightInfo> acc, @NotNull Pattern pat) {
    return switch (pat) {
      case Pattern.Number num -> add(acc, LitKind.Int.toLit(num.sourcePos()));
      default -> StmtFolder.super.fold(acc, pat);
    };
  }

  @Override
  public @NotNull MutableList<HighlightInfo> foldModuleRef(@NotNull MutableList<HighlightInfo> acc, @NotNull QualifiedID mod) {
    // TODO: use `LinkId.page` for cross module link
    return add(acc, HighlightInfo.DefKind.Module.toRef(mod.sourcePos(), Link.loc(mod.join()), null));
  }

  @Override
  public @NotNull MutableList<HighlightInfo> foldModuleDecl(@NotNull MutableList<HighlightInfo> acc, @NotNull QualifiedID mod) {
    // TODO: use `LinkId.page` for cross module link
    return add(acc, HighlightInfo.DefKind.Module.toDef(mod.sourcePos(), Link.loc(mod.join()), null));
  }

  private @NotNull HighlightInfo linkDef(@NotNull SourcePos sourcePos, @NotNull AnyVar var, @Nullable AyaDocile type) {
    return kindOf(var).toDef(sourcePos, BaseDistiller.linkIdOf(var), type);
  }

  private @NotNull HighlightInfo linkRef(@NotNull SourcePos sourcePos, @NotNull AnyVar var, @Nullable AyaDocile type) {
    if (var instanceof LocalVar(var $, var $$, GenerateKind.Generalized(var origin)))
      return linkRef(sourcePos, origin, type);
    return kindOf(var).toRef(sourcePos, BaseDistiller.linkIdOf(var), type);
  }

  @SuppressWarnings("DuplicateBranchesInSwitch")
  private @NotNull HighlightInfo.DefKind kindOf(@NotNull AnyVar var) {
    record P(Decl decl, GenericDef def) {}
    return switch (var) {
      case GeneralizedVar ignored -> HighlightInfo.DefKind.Generalized;
      case DefVar<?, ?> defVar -> switch (new P(defVar.concrete, defVar.core)) {
        case P(TeleDecl.FnDecl $, var $$) -> HighlightInfo.DefKind.Fn;
        case P(TeleDecl.StructDecl $, var $$) -> HighlightInfo.DefKind.Struct;
        case P(TeleDecl.StructField $, var $$) -> HighlightInfo.DefKind.Field;
        case P(TeleDecl.DataDecl $, var $$) -> HighlightInfo.DefKind.Data;
        case P(TeleDecl.DataCtor $, var $$) -> HighlightInfo.DefKind.Con;
        case P(TeleDecl.PrimDecl $, var $$) -> HighlightInfo.DefKind.Prim;
        case P(var $, FnDef $$) -> HighlightInfo.DefKind.Fn;
        case P(var $, StructDef $$) -> HighlightInfo.DefKind.Struct;
        case P(var $, FieldDef $$) -> HighlightInfo.DefKind.Field;
        case P(var $, DataDef $$) -> HighlightInfo.DefKind.Data;
        case P(var $, CtorDef $$) -> HighlightInfo.DefKind.Con;
        case P(var $, PrimDef $$) -> HighlightInfo.DefKind.Prim;
        default -> HighlightInfo.DefKind.Unknown;
      };
      case LocalVar(var $, var $$, GenerateKind.Generalized(var $$$)) -> HighlightInfo.DefKind.Generalized;
      case LocalVar ignored -> HighlightInfo.DefKind.LocalVar;
      default -> HighlightInfo.DefKind.Unknown;
    };
  }
}
