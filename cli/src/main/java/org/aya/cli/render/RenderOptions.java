// Copyright (c) 2020-2022 Tesla (Yinsen) Zhang.
// Use of this source code is governed by the MIT license that can be found in the LICENSE.md file.
package org.aya.cli.render;

import com.google.gson.JsonParseException;
import kala.control.Either;
import org.aya.cli.render.vscode.ColorTheme;
import org.aya.pretty.backend.html.DocHtmlPrinter;
import org.aya.pretty.backend.html.Html5Stylist;
import org.aya.pretty.backend.latex.DocTeXPrinter;
import org.aya.pretty.backend.latex.TeXStylist;
import org.aya.pretty.backend.md.DocMdPrinter;
import org.aya.pretty.backend.md.MdStylist;
import org.aya.pretty.backend.string.DebugStylist;
import org.aya.pretty.backend.string.StringPrinterConfig;
import org.aya.pretty.backend.string.StringStylist;
import org.aya.pretty.backend.terminal.AdaptiveCliStylist;
import org.aya.pretty.backend.terminal.DocTermPrinter;
import org.aya.pretty.backend.terminal.UnixTermStylist;
import org.aya.pretty.doc.Doc;
import org.aya.pretty.printer.ColorScheme;
import org.aya.pretty.printer.StyleFamily;
import org.aya.pretty.style.AyaColorScheme;
import org.aya.pretty.style.AyaStyleFamily;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.UnknownNullability;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/** Multi-target {@link org.aya.pretty.printer.Stylist}, usually created from json files. */
public class RenderOptions {
  public enum OutputTarget {
    Terminal(".txt"),
    LaTeX(".tex"),
    AyaMd(".md"),
    HTML(".html"),
    Plain(".txt");

    public final @NotNull @NonNls String fileExt;

    OutputTarget(@NotNull String fileExt) {
      this.fileExt = fileExt;
    }
  }

  public enum ColorSchemeName {
    Emacs,
    IntelliJ,

    /**
     * import color scheme from the {@link RenderOptions#path} which in vscode color theme format.
     */
    Custom
  }

  public enum StyleFamilyName {
    Default
  }

  public final static @NotNull ColorSchemeName DEFAULT_COLOR_SCHEME = ColorSchemeName.Emacs;
  public final static @NotNull StyleFamilyName DEFAULT_STYLE_FAMILY = StyleFamilyName.Default;

  public @UnknownNullability ColorSchemeName colorScheme = DEFAULT_COLOR_SCHEME;
  public @UnknownNullability StyleFamilyName styleFamily = DEFAULT_STYLE_FAMILY;
  public @Nullable String path = null;

  /** creating stylist is expensive, so we memorize them */
  private transient @Nullable ColorScheme colorSchemeCache = null;
  private transient @Nullable StyleFamily styleFamilyCache = null;

  public void checkDeserialization() {
    if (colorScheme == null) colorScheme = DEFAULT_COLOR_SCHEME;
    if (styleFamily == null) styleFamily = DEFAULT_STYLE_FAMILY;
  }

  public boolean isDefault() {
    return colorScheme.equals(DEFAULT_COLOR_SCHEME) && styleFamily.equals(DEFAULT_STYLE_FAMILY);
  }

  public @NotNull String prettyColorScheme() {
    return colorScheme == ColorSchemeName.Custom ? Objects.requireNonNull(path) : colorScheme.toString();
  }

  public @NotNull String prettyStyleFamily() {
    return styleFamily.toString();
  }

  public void updateColorScheme(@NotNull Either<ColorSchemeName, Path> nameOrPath) throws IllegalArgumentException {
    if (nameOrPath.isLeft()) {
      var color = nameOrPath.getLeftValue();
      if (color == ColorSchemeName.Custom) throw new IllegalArgumentException(
        "To set a custom color scheme, just give the path to it :)");
      this.colorScheme = color;
    } else {
      this.colorScheme = ColorSchemeName.Custom;
      this.path = nameOrPath.getRightValue().toAbsolutePath().toString();
    }
    invalidate();
  }

  public void updateStyleFamily(@NotNull Either<StyleFamilyName, Path> nameOrPath) throws IllegalArgumentException {
    if (nameOrPath.isRight()) throw new IllegalArgumentException("We don't support custom style family yet :)");
    this.styleFamily = nameOrPath.getLeftValue();
    invalidate();
  }

  public void invalidate() {
    colorSchemeCache = null;
    styleFamilyCache = null;
  }

  public static @NotNull StringStylist defaultStylist(@NotNull OutputTarget output) {
    return switch (output) {
      case Terminal -> AdaptiveCliStylist.INSTANCE;
      case LaTeX -> TeXStylist.DEFAULT;
      case AyaMd -> MdStylist.DEFAULT;
      case HTML -> Html5Stylist.DEFAULT;
      case Plain -> DebugStylist.DEFAULT;
    };
  }

  public @NotNull StringStylist stylist(@NotNull OutputTarget output) throws IOException, JsonParseException {
    if (isDefault()) return defaultStylist(output);
    return switch (output) {
      case Terminal -> new UnixTermStylist(buildColorScheme(), buildStyleFamily());
      case LaTeX -> new TeXStylist(buildColorScheme(), buildStyleFamily());
      case AyaMd -> new MdStylist(buildColorScheme(), buildStyleFamily());
      case HTML -> new Html5Stylist(buildColorScheme(), buildStyleFamily());
      case Plain -> new DebugStylist(buildColorScheme(), buildStyleFamily());
    };
  }

  public @NotNull StringStylist stylistOrDefault(@NotNull OutputTarget output) {
    try {
      return stylist(output);
    } catch (IOException | JsonParseException e) {
      // TODO: report error but don't stop
      return defaultStylist(output);
    }
  }

  public @NotNull String render(@NotNull OutputTarget output, @NotNull Doc doc, boolean witHeader, boolean withStyleDef, boolean unicode) {
    return render(output, doc, witHeader, withStyleDef, unicode, StringPrinterConfig.INFINITE_SIZE);
  }

  public @NotNull String render(
    @NotNull OutputTarget output, @NotNull Doc doc,
    boolean witHeader, boolean withStyleDef, boolean unicode,
    int pageWidth
  ) {
    var stylist = stylistOrDefault(output);
    return switch (output) {
      case Plain -> doc.renderToString(new StringPrinterConfig<>(stylist, pageWidth, unicode));
      case LaTeX -> doc.render(new DocTeXPrinter(), new DocTeXPrinter.Config((TeXStylist) stylist));
      case HTML -> doc.render(new DocHtmlPrinter<>(), new DocHtmlPrinter.Config(
        (Html5Stylist) stylist, witHeader, withStyleDef));
      case AyaMd -> doc.render(new DocMdPrinter(), new DocMdPrinter.Config(
        (MdStylist) stylist, witHeader, withStyleDef, true));
      case Terminal -> doc.render(new DocTermPrinter(), new DocTermPrinter.Config(
        (UnixTermStylist) stylist, pageWidth, unicode));
    };
  }

  private @NotNull ColorScheme buildColorScheme() throws IOException, JsonParseException {
    if (colorSchemeCache != null) return colorSchemeCache;
    colorSchemeCache = switch (colorScheme) {
      case Emacs -> AyaColorScheme.EMACS;
      case IntelliJ -> AyaColorScheme.INTELLIJ;
      case Custom -> {
        if (path == null) throw new IllegalArgumentException("Unable to generate a custom color scheme without a path");
        // IOException from here
        var colorTheme = ColorTheme.loadFrom(Path.of(path)).<IOException>getOrThrow();
        yield colorTheme.buildColorScheme(null);
      }
    };
    return colorSchemeCache;
  }

  private @NotNull StyleFamily buildStyleFamily() {
    if (styleFamilyCache != null) return styleFamilyCache;
    styleFamilyCache = switch (styleFamily) {
      case Default -> AyaStyleFamily.DEFAULT;
    };
    return styleFamilyCache;
  }

  @Override public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof RenderOptions that)) return false;
    return colorScheme == that.colorScheme && styleFamily == that.styleFamily && Objects.equals(path, that.path);
  }
}
