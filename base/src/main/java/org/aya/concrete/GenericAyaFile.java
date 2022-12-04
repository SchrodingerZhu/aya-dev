// Copyright (c) 2020-2022 Tesla (Yinsen) Zhang.
// Use of this source code is governed by the MIT license that can be found in the LICENSE.md file.
package org.aya.concrete;

import org.aya.concrete.remark.Literate;
import org.aya.util.error.SourceFile;
import org.aya.util.error.SourceFileLocator;
import org.aya.util.error.SourcePos;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Path;

public interface GenericAyaFile {
  interface Factory {
    @NotNull GenericAyaFile createAyaFile(@NotNull SourceFileLocator locator, @NotNull Path path) throws IOException;
  }

  /** @return the original source file, maybe a literate file */
  @NotNull SourceFile originalFile() throws IOException;
  /**
   * @return the valid aya source file
   * @implNote Literate files should override this method to return the extracted code file.
   */
  default @NotNull SourceFile codeFile() throws IOException {
    return originalFile();
  }
  /**
   * @return the parsed literate output
   * @implNote This method wraps the file in a code block by default. Literate files should override this method.
   */
  default @NotNull Literate literate() throws IOException {
    var code = originalFile().sourceCode();
    var mockPos = new SourcePos(originalFile(), 0, code.length(), -1, -1, -1, -1);
    // ^ we only need index, so it's fine to use a mocked line/column
    return new Literate.CodeBlock(mockPos, "aya", code);
  }
}
