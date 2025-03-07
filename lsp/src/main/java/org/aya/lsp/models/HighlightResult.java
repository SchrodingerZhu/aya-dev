// Copyright (c) 2020-2022 Tesla (Yinsen) Zhang.
// Use of this source code is governed by the MIT license that can be found in the LICENSE.md file.
package org.aya.lsp.models;

import kala.collection.Seq;
import kala.control.Option;
import kala.value.TransientVar;
import org.aya.cli.literate.HighlightInfo;
import org.aya.lsp.utils.LspRange;
import org.aya.util.error.SourcePos;
import org.javacs.lsp.Range;
import org.jetbrains.annotations.NotNull;

import java.net.URI;
import java.util.List;

public record HighlightResult(@NotNull URI uri, @NotNull List<Symbol> symbols) {
  public HighlightResult(@NotNull URI uri, @NotNull Seq<Symbol> symbols) {
    this(uri, symbols.asJava());
  }

  public enum Kind {
    // definitions
    ModuleDef, FnDef, DataDef, StructDef, ConDef, FieldDef, PrimDef, GeneralizeDef,
    // expressions
    FnRef, DataRef, StructRef, ConRef, FieldRef, PrimRef, ModuleRef, GeneralizeRef,
  }

  public record Symbol(
    @NotNull Range range,
    @NotNull HighlightResult.Kind kind,
    @NotNull TransientVar<SourcePos> sourcePos
  ) {
    public static @NotNull Option<Symbol> from(@NotNull HighlightInfo info) {
      return kindOf(info.type())
        .map(k -> {
          var pos = info.sourcePos();
          return new Symbol(LspRange.toRange(pos), k, new TransientVar<>(pos));
        });
    }

    private static @NotNull Option<Kind> kindOf(@NotNull HighlightInfo.HighlightSymbol symbol) {
      return switch (symbol) {
        case HighlightInfo.SymDef symDef -> switch (symDef.kind()) {
          case LocalVar -> Option.none(); // maybe rainbow local variables
          case Generalized -> Option.some(Kind.GeneralizeDef);
          case Module -> Option.some(Kind.ModuleDef);
          case Fn -> Option.some(Kind.FnDef);
          case Data -> Option.some(Kind.DataDef);
          case Struct -> Option.some(Kind.StructDef);
          case Con -> Option.some(Kind.ConDef);
          case Field -> Option.some(Kind.FieldDef);
          case Prim -> Option.some(Kind.PrimDef);
          case Unknown -> Option.none();
        };
        case HighlightInfo.SymRef symRef -> switch (symRef.kind()) {
          case LocalVar -> Option.none(); // maybe rainbow local variables
          case Generalized -> Option.some(Kind.GeneralizeRef);
          case Module -> Option.some(Kind.ModuleRef);
          case Fn -> Option.some(Kind.FnRef);
          case Data -> Option.some(Kind.DataRef);
          case Struct -> Option.some(Kind.StructRef);
          case Con -> Option.some(Kind.ConRef);
          case Field -> Option.some(Kind.FieldRef);
          case Prim -> Option.some(Kind.PrimRef);
          case Unknown -> Option.none();
        };
        case HighlightInfo.SymLit $ -> Option.none();   // handled by client
        case HighlightInfo.SymError $ -> Option.none(); // handled by client
      };
    }
  }
}
