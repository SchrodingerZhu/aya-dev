// Copyright (c) 2020-2022 Tesla (Yinsen) Zhang.
// Use of this source code is governed by the MIT license that can be found in the LICENSE.md file.
package org.aya.pretty.backend.string;

import kala.collection.Seq;
import kala.collection.SeqView;
import kala.collection.immutable.ImmutableSeq;
import org.aya.pretty.doc.Style;
import org.aya.pretty.printer.ColorScheme;
import org.aya.pretty.printer.StyleFamily;
import org.jetbrains.annotations.NotNull;

import java.util.function.Consumer;

public abstract class ClosingStylist extends StringStylist {
  public ClosingStylist(@NotNull ColorScheme colorScheme, @NotNull StyleFamily styleFamily) {
    super(colorScheme, styleFamily);
  }

  public record StyleToken(@NotNull Consumer<Cursor> start, @NotNull Consumer<Cursor> end) {
    public StyleToken(@NotNull String start, @NotNull String end, boolean visible) {
      this(c -> c.content(start, visible), c -> c.content(end, visible));
    }

    public static final @NotNull StyleToken NULL = new StyleToken(c -> {}, c -> {});
  }

  @Override
  public void format(@NotNull Seq<Style> styles, @NotNull Cursor cursor, StringPrinter.Outer outer, @NotNull Runnable inside) {
    formatInternal(styles.view(), cursor, outer, inside);
  }

  private void formatInternal(@NotNull SeqView<Style> styles, @NotNull Cursor cursor, StringPrinter.Outer outer, @NotNull Runnable inside) {
    if (styles.isEmpty()) {
      inside.run();
      return;
    }

    var style = styles.first();
    var formats = style instanceof Style.Preset preset
      ? formatPresetStyle(preset.styleName(), outer)
      : ImmutableSeq.of(formatOne(style, outer));
    formats.forEach(format -> format.start.accept(cursor));
    formatInternal(styles.drop(1), cursor, outer, inside);
    formats.reversed().forEach(format -> format.end.accept(cursor));
  }

  private @NotNull StyleToken formatOne(@NotNull Style style, StringPrinter.Outer outer) {
    return switch (style) {
      case Style.Attr attr -> switch (attr) {
        case Italic -> formatItalic(outer);
        case Bold -> formatBold(outer);
        case Strike -> formatStrike(outer);
        case Underline -> formatUnderline(outer);
      };
      case Style.ColorName color -> formatPresetColor(color.colorName(), color.background());
      case Style.ColorHex hex -> formatColorHex(hex.color(), hex.background());
      case Style.CustomStyle custom -> formatCustom(custom);
      default -> StyleToken.NULL;
    };
  }

  protected @NotNull ImmutableSeq<StyleToken> formatPresetStyle(@NotNull String styleName, StringPrinter.Outer outer) {
    return styleFamily.definedStyles().getOption(styleName)
      .getOrDefault(it -> it.styles().map(sub -> formatOne(sub, outer)), ImmutableSeq.empty());
  }

  protected @NotNull StyleToken formatPresetColor(@NotNull String colorName, boolean background) {
    return colorScheme.definedColors().getOption(colorName)
      .getOrDefault(it -> formatColorHex(it, background), StyleToken.NULL);
  }

  protected @NotNull StyleToken formatCustom(@NotNull Style.CustomStyle style) {
    return StyleToken.NULL;
  }

  protected abstract @NotNull StyleToken formatItalic(StringPrinter.Outer outer);
  protected abstract @NotNull StyleToken formatBold(StringPrinter.Outer outer);
  protected abstract @NotNull StyleToken formatStrike(StringPrinter.Outer outer);
  protected abstract @NotNull StyleToken formatUnderline(StringPrinter.Outer outer);
  protected abstract @NotNull StyleToken formatColorHex(int rgb, boolean background);

  public static class Delegate extends ClosingStylist {
    protected final @NotNull ClosingStylist delegate;

    public Delegate(@NotNull ClosingStylist delegate) {
      super(delegate.colorScheme, delegate.styleFamily);
      this.delegate = delegate;
    }

    @Override protected @NotNull StyleToken formatCustom(Style.@NotNull CustomStyle style) {
      return delegate.formatCustom(style);
    }

    @Override protected @NotNull ImmutableSeq<StyleToken> formatPresetStyle(@NotNull String styleName, StringPrinter.Outer outer) {
      return delegate.formatPresetStyle(styleName, outer);
    }

    @Override protected @NotNull StyleToken formatPresetColor(@NotNull String colorName, boolean background) {
      return delegate.formatPresetColor(colorName, background);
    }

    @Override protected @NotNull StyleToken formatItalic(StringPrinter.Outer outer) {
      return delegate.formatItalic(outer);
    }

    @Override protected @NotNull StyleToken formatBold(StringPrinter.Outer outer) {
      return delegate.formatBold(outer);
    }

    @Override protected @NotNull StyleToken formatStrike(StringPrinter.Outer outer) {
      return delegate.formatStrike(outer);
    }

    @Override protected @NotNull StyleToken formatUnderline(StringPrinter.Outer outer) {
      return delegate.formatUnderline(outer);
    }

    @Override protected @NotNull StyleToken formatColorHex(int rgb, boolean background) {
      return delegate.formatColorHex(rgb, background);
    }
  }
}
