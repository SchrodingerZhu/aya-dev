// Copyright (c) 2020-2022 Tesla (Yinsen) Zhang.
// Use of this source code is governed by the MIT license that can be found in the LICENSE.md file.
package org.aya.util.error;

import kala.control.Option;
import org.aya.util.StringUtil;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Unified source file representation for error reporting only.
 *
 * @param display Usually constructed with {@link SourceFileLocator#displayName(Path)}
 */
public record SourceFile(
  @NotNull String display,
  @NotNull Option<Path> underlying,
  @NotNull String sourceCode
) {
  public static @NotNull SourceFile from(@NotNull SourceFileLocator locator, @NotNull Path path) throws IOException {
    return from(locator, path, StringUtil.trimCRLF(Files.readString(path)));
  }

  public static @NotNull SourceFile from(@NotNull SourceFileLocator locator, @NotNull Path path, @NotNull String sourceCode) {
    return new SourceFile(locator.displayName(path).toString(), path, sourceCode);
  }

  public SourceFile(@NotNull String display, @NotNull Path underlying, @NotNull String sourceCode) {
    this(display, Option.some(underlying), sourceCode);
  }

  public static final SourceFile NONE = new SourceFile("<unknown-file>", Option.none(), "");
  public static final SourceFile SER = new SourceFile("<serialized-core>", Option.none(), "");

  public boolean isSomeFile() {
    return underlying.isDefined();
  }

  public @NotNull Path resolveSibling(@NotNull Path sibling) {
    return underlying().getOrElse(() -> Path.of(".")).resolveSibling(sibling);
  }
}
