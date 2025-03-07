// Copyright (c) 2020-2022 Tesla (Yinsen) Zhang.
// Use of this source code is governed by the MIT license that can be found in the LICENSE.md file.
package org.aya.pretty.backend.terminal;

import org.aya.pretty.backend.string.Cursor;
import org.aya.pretty.backend.string.StringPrinter;
import org.aya.pretty.backend.string.StringPrinterConfig;
import org.aya.pretty.doc.Doc;
import org.jetbrains.annotations.NotNull;

public class DocTermPrinter extends StringPrinter<DocTermPrinter.Config> {
  @Override protected void renderInlineCode(@NotNull Cursor cursor, Doc.@NotNull InlineCode code, Outer outer) {
    cursor.invisibleContent("`");
    renderDoc(cursor, code.code(), outer);
    cursor.invisibleContent("'");
  }

  public static class Config extends StringPrinterConfig<UnixTermStylist> {
    public Config(@NotNull UnixTermStylist stylist, int pageWidth, boolean unicode) {
      super(stylist, pageWidth, unicode);
    }
  }
}
