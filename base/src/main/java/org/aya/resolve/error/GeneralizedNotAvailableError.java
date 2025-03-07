// Copyright (c) 2020-2022 Tesla (Yinsen) Zhang.
// Use of this source code is governed by the MIT license that can be found in the LICENSE.md file.
package org.aya.resolve.error;

import org.aya.distill.BaseDistiller;
import org.aya.pretty.doc.Doc;
import org.aya.ref.AnyVar;
import org.aya.util.distill.DistillerOptions;
import org.aya.util.error.SourcePos;
import org.aya.util.reporter.Problem;
import org.jetbrains.annotations.NotNull;

public record GeneralizedNotAvailableError(
  @Override @NotNull SourcePos sourcePos, @NotNull AnyVar var
) implements Problem {
  @Override public @NotNull Severity level() {return Severity.ERROR;}

  @Override public @NotNull Stage stage() {return Stage.RESOLVE;}

  @Override public @NotNull Doc describe(@NotNull DistillerOptions options) {
    return Doc.sep(
      Doc.english("The generalized variable"),
      Doc.code(BaseDistiller.varDoc(var)),
      Doc.english("is not available here")
    );
  }
}
