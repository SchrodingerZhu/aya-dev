// Copyright (c) 2020-2022 Tesla (Yinsen) Zhang.
// Use of this source code is governed by the MIT license that can be found in the LICENSE.md file.
package org.aya.pretty.backend.md;

import org.aya.pretty.backend.html.DocHtmlPrinter;
import org.aya.pretty.backend.html.HtmlConstants;
import org.aya.pretty.backend.string.Cursor;
import org.aya.pretty.doc.Doc;
import org.jetbrains.annotations.NotNull;

import java.util.regex.Pattern;

public class DocMdPrinter extends DocHtmlPrinter<DocMdPrinter.Config> {
  public static final Pattern MD_ESCAPE = Pattern.compile("[#&()*+\\-;<>\\[\\\\\\]_`|~]");
  public static final Pattern MD_NO_ESCAPE_BACKSLASH = Pattern.compile("(^\\s*\\d+)\\.( |$)", Pattern.MULTILINE);

  @Override protected void renderHeader(@NotNull Cursor cursor) {
  }

  @Override protected void renderFooter(@NotNull Cursor cursor) {
    // put generated styles at the end of the file
    if (config.withHeader) {
      cursor.invisibleContent(HtmlConstants.HOVER_STYLE);
      cursor.invisibleContent(HtmlConstants.HOVER_POPUP_STYLE);
      if (config.ayaFlavored) // TODO: add flag for Vue (server side rendering) and plain HTML
        cursor.invisibleContent(HtmlConstants.HOVER_HIGHLIGHT_ALL_OCCURS_VUE);
      renderCssStyle(cursor);
    }
  }

  // markdown escape: https://spec.commonmark.org/0.30/#backslash-escapes
  @Override protected @NotNull String escapePlainText(@NotNull String content, Outer outer) {
    if (outer == Outer.EnclosingTag) {
      // If we are in HTML tag (like rendered Aya code), use HTML escape settings.
      return super.escapePlainText(content, outer);
    }
    // If we are in Markdown, do not escape text in code block.
    if (outer == Outer.Code) return content;
    // We are not need to call `super.escapePlainText`, we will escape them in markdown way.
    // I wish you can understand this genius regexp
    // What we will escape:
    // .
    // What we won't escape, which are not special characters
    // or don't matter in a plain text (like `:` and `"` work in footnotes only):
    // ":,%$'=@?^{}/
    // What we should escape, but we don't:
    // `!`: `!` is only used in `![]()`, but we already escape `[`, `]`, `(`, `)`, so `!` doesn't work.
    content = MD_ESCAPE
      .matcher(content)
      .replaceAll(result -> {
        var chara = result.group();
        // special characters, see Matcher#appendReplacement
        if (chara.equals("\\")) chara = "\\\\";
        return "\\\\" + chara;
      });

    // avoiding escape `\`.
    content = MD_NO_ESCAPE_BACKSLASH
      .matcher(content)
      .replaceAll("$1\\\\.$2");

    return content;
  }

  @Override protected void renderHardLineBreak(@NotNull Cursor cursor) {
    cursor.lineBreakWith("\n");
  }

  @Override protected void renderHyperLinked(@NotNull Cursor cursor, @NotNull Doc.HyperLinked text, Outer outer) {
    Runnable pureMd = () -> {
      // use markdown typesetting only when the stylist is pure markdown
      var href = text.href();
      cursor.invisibleContent("[");
      renderDoc(cursor, text.doc(), outer);
      cursor.invisibleContent("](");
      cursor.invisibleContent(DocHtmlPrinter.normalizeHref(href));
      cursor.invisibleContent(")");
      // TODO: text.id(), text.hover()
    };
    runSwitch(pureMd, () -> {
      if (outer != Outer.Free) super.renderHyperLinked(cursor, text, outer);
      else pureMd.run();
    });
  }

  @Override protected void renderInlineCode(@NotNull Cursor cursor, @NotNull Doc.InlineCode code, Outer outer) {
    // assumption: inline code cannot be nested in markdown, but don't assert it.
    Runnable pureMd = () -> formatInlineCode(cursor, code.code(), "`", "`", outer);
    runSwitch(pureMd, () -> {
      var isAya = code.language().equalsIgnoreCase("aya");
      if (isAya) formatInlineCode(cursor, code.code(), "<code class=\"Aya\">", "</code>", Outer.EnclosingTag);
      else pureMd.run();
    });
  }

  @Override protected void renderCodeBlock(@NotNull Cursor cursor, @NotNull Doc.CodeBlock block, Outer outer) {
    // assumption: code block cannot be nested in markdown, but don't assert it.
    Runnable pureMd = () -> formatCodeBlock(cursor, block.code(), "```" + block.language(), "```", outer);
    runSwitch(pureMd,
      () -> {
        var isAya = block.language().equalsIgnoreCase("aya");
        if (isAya)
          formatCodeBlock(cursor, block.code(), "<pre class=\"Aya\">", "</pre>", "<code>", "</code>", Outer.EnclosingTag);
        else pureMd.run();
      });
  }

  public void formatCodeBlock(@NotNull Cursor cursor, @NotNull Doc code, @NotNull String begin, @NotNull String end, Outer outer) {
    formatCodeBlock(cursor, code, begin, end, "", "", outer);
  }

  public void formatCodeBlock(
    @NotNull Cursor cursor, @NotNull Doc code,
    @NotNull String begin, @NotNull String end,
    @NotNull String begin2, @NotNull String end2,
    Outer outer
  ) {
    cursor.invisibleContent(begin);
    cursor.lineBreakWith("\n");
    cursor.invisibleContent(begin2);
    renderDoc(cursor, code, outer);
    cursor.invisibleContent(end2);
    cursor.lineBreakWith("\n");
    cursor.invisibleContent(end);
    cursor.lineBreakWith("\n");
  }

  public void formatInlineCode(@NotNull Cursor cursor, @NotNull Doc code, @NotNull String begin, @NotNull String end, Outer outer) {
    cursor.invisibleContent(begin);
    renderDoc(cursor, code, outer);
    cursor.invisibleContent(end);
  }

  private void runSwitch(@NotNull Runnable pureMd, @NotNull Runnable ayaMd) {
    if (config.ayaFlavored) ayaMd.run();
    else pureMd.run();
  }

  public static class Config extends DocHtmlPrinter.Config {
    public boolean ayaFlavored;

    public Config(boolean withHeader, boolean withStyleDef, boolean ayaFlavored) {
      this(MdStylist.DEFAULT, withHeader, withStyleDef, ayaFlavored);
    }

    public Config(@NotNull MdStylist stylist, boolean withHeader, boolean withStyleDef, boolean ayaFlavored) {
      super(stylist, withHeader, withStyleDef);
      this.ayaFlavored = ayaFlavored;
    }
  }
}
