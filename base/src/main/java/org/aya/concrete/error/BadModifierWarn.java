// Copyright (c) 2020-2022 Yinsen (Tesla) Zhang.
// Use of this source code is governed by the MIT license that can be found in the LICENSE.md file.
package org.aya.concrete.error;

import org.aya.distill.BaseDistiller;
import org.aya.generic.Modifier;
import org.aya.pretty.doc.Doc;
import org.aya.util.distill.DistillerOptions;
import org.aya.util.error.SourcePos;
import org.aya.util.reporter.Problem;
import org.jetbrains.annotations.NotNull;

public record BadModifierWarn(
  @Override @NotNull SourcePos sourcePos,
  @NotNull Modifier modifier
) implements Problem {
  @Override public @NotNull Doc describe(@NotNull DistillerOptions options) {
    return Doc.sep(Doc.plain("Ignoring"), Doc.styled(BaseDistiller.KEYWORD, modifier.keyword));
  }

  @Override public @NotNull Severity level() {
    return Severity.WARN;
  }
}
