// Copyright (c) 2020-2022 Tesla (Yinsen) Zhang.
// Use of this source code is governed by the MIT license that can be found in the LICENSE.md file.
package org.aya.tyck.error;

import org.aya.core.term.SortTerm;
import org.aya.pretty.doc.Doc;
import org.aya.util.distill.DistillerOptions;
import org.aya.util.error.SourcePos;
import org.jetbrains.annotations.NotNull;

/**
 * @param lower     the smaller level that is expected to be larger.
 * @param upper     the larger level that is expected to be smaller.
 * @param wantEqual if we want the levels to be equal.
 */
public record LevelError(
  @Override @NotNull SourcePos sourcePos,
  SortTerm lower, SortTerm upper, boolean wantEqual
) implements TyckError {
  @Override public @NotNull Doc describe(@NotNull DistillerOptions options) {
    return Doc.sepNonEmpty(Doc.english("The level here is expected to be"),
      Doc.emptyIf(wantEqual, () -> Doc.symbol("<=")),
      lower.toDoc(options),
      Doc.english("but it is actually"),
      upper.toDoc(options));
  }
}
