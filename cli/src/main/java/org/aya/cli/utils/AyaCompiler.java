// Copyright (c) 2020-2022 Tesla (Yinsen) Zhang.
// Use of this source code is governed by the MIT license that can be found in the LICENSE.md file.
package org.aya.cli.utils;

import kala.collection.immutable.ImmutableSeq;
import kala.function.CheckedRunnable;
import org.aya.cli.single.CompilerFlags;
import org.aya.core.def.GenericDef;
import org.aya.core.serde.CompiledAya;
import org.aya.core.serde.Serializer;
import org.aya.generic.util.InternalException;
import org.aya.generic.util.InterruptException;
import org.aya.resolve.ResolveInfo;
import org.aya.resolve.module.FileModuleLoader;
import org.aya.util.reporter.CountingReporter;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.ObjectOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

public class AyaCompiler {
  public static int catching(
    @NotNull CountingReporter reporter,
    @NotNull CompilerFlags flags,
    @NotNull CheckedRunnable<IOException> block
  ) throws IOException {
    try {
      block.runChecked();
    } catch (InternalException e) {
      FileModuleLoader.handleInternalError(e);
      reporter.reportString("Internal error");
      return e.exitCode();
    } catch (InterruptException e) {
      reporter.reportString(e.stage().name() + " interrupted due to:");
      if (flags.interruptedTrace()) e.printStackTrace();
    }
    if (reporter.noError()) {
      reporter.reportString(flags.message().successNotion());
      return 0;
    } else {
      reporter.reportString(reporter.countToString());
      reporter.reportString(flags.message().failNotion());
      return 1;
    }
  }

  public static void saveCompiledCore(
    @NotNull Path coreFile,
    @NotNull ResolveInfo resolveInfo,
    @NotNull ImmutableSeq<GenericDef> defs,
    @NotNull Serializer.State state
  ) throws IOException {
    var compiledAya = CompiledAya.from(resolveInfo, defs, state);
    try (var outputStream = coreWriter(coreFile)) {
      outputStream.writeObject(compiledAya);
    }
  }

  private static @NotNull ObjectOutputStream coreWriter(@NotNull Path coreFile) throws IOException {
    Files.createDirectories(coreFile.toAbsolutePath().getParent());
    return new ObjectOutputStream(Files.newOutputStream(coreFile));
  }
}
