// Copyright (c) 2020-2022 Tesla (Yinsen) Zhang.
// Use of this source code is governed by the MIT license that can be found in the LICENSE.md file.
package org.aya.util.reporter;

import kala.collection.Seq;
import kala.collection.SeqView;
import kala.collection.immutable.ImmutableSeq;
import kala.tuple.Tuple;
import org.aya.pretty.backend.terminal.UnixTermStyle;
import org.aya.pretty.doc.Doc;
import org.aya.pretty.doc.Style;
import org.aya.pretty.doc.Styles;
import org.aya.pretty.error.PrettyError;
import org.aya.util.distill.DistillerOptions;
import org.aya.util.error.SourcePos;
import org.aya.util.error.WithPos;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * @author ice1000
 */
public interface Problem {
  enum Severity {
    ERROR,
    GOAL,
    WARN,
    INFO,
  }

  enum Stage {
    TERCK,
    TYCK,
    RESOLVE,
    PARSE,
    OTHER
  }

  @NotNull SourcePos sourcePos();
  @NotNull Doc describe(@NotNull DistillerOptions options);
  @NotNull Severity level();
  default @NotNull Stage stage() {
    return Stage.OTHER;
  }
  default @NotNull Doc hint(@NotNull DistillerOptions options) {
    return Doc.empty();
  }
  default @NotNull SeqView<WithPos<Doc>> inlineHints(@NotNull DistillerOptions options) {
    return SeqView.empty();
  }

  default boolean isError() {
    return level() == Severity.ERROR;
  }

  default @NotNull PrettyError toPrettyError(@NotNull DistillerOptions options) {
    var sourcePos = sourcePos();
    return new PrettyError(
      sourcePos.file().display(),
      sourcePos.toSpan(),
      brief(options),
      inlineHints(options).stream()
        .collect(Collectors.groupingBy(WithPos::sourcePos,
          Collectors.mapping(WithPos::data, Seq.factory())))
        .entrySet()
        .stream()
        .sorted(Map.Entry.comparingByKey())
        .map(kv -> Tuple.of(kv.getKey().toSpan(), Doc.commaList(kv.getValue())))
        .collect(ImmutableSeq.factory())
    );
  }

  default @NotNull Doc brief(@NotNull DistillerOptions options) {
    var tag = switch (level()) {
      case WARN -> Doc.plain("Warning:");
      case GOAL -> Doc.plain("Goal:");
      case INFO -> Doc.plain("Info:");
      case ERROR -> Doc.styled(ERROR, "Error:");
    };
    var doc = Doc.sep(tag, Doc.styled(TEXT, Doc.align(describe(options))));
    var hint = hint(options);
    return hint instanceof Doc.Empty ? doc : Doc.vcat(
      doc,
      Doc.sep(Doc.styled(NOTE, "note:"), Doc.styled(TEXT, Doc.align(hint)))
    );
  }

  @NotNull Styles ERROR = Style.bold().and().custom(UnixTermStyle.TerminalRed);
  @NotNull Styles NOTE = Style.bold().and().custom(UnixTermStyle.TerminalGreen);
  @NotNull Styles TEXT = Style.bold().and();
}
