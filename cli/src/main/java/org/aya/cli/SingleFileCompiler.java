// Copyright (c) 2020-2021 Yinsen (Tesla) Zhang.
// Use of this source code is governed by the GNU GPLv3 license that can be found in the LICENSE file.
package org.aya.cli;

import org.aya.api.error.CountingReporter;
import org.aya.api.error.Reporter;
import org.aya.api.util.InterruptException;
import org.aya.concrete.parse.AyaParsing;
import org.aya.concrete.parse.AyaProducer;
import org.aya.concrete.pretty.StmtPrettier;
import org.aya.concrete.resolve.module.CachedModuleLoader;
import org.aya.concrete.resolve.module.FileModuleLoader;
import org.aya.concrete.resolve.module.ModuleListLoader;
import org.aya.core.def.Def;
import org.aya.core.def.PrimDef;
import org.aya.pretty.doc.Doc;
import org.aya.tyck.ExprTycker;
import org.aya.tyck.trace.Trace;
import org.glavo.kala.tuple.Unit;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Supplier;

public record SingleFileCompiler(@NotNull Reporter reporter, @NotNull Path filePath, Trace.@Nullable Builder builder) {
  public int compile(@NotNull CompilerFlags flags) throws IOException {
    var reporter = new CountingReporter(this.reporter);
    var parser = AyaParsing.parser(filePath, reporter);
    try {
      var program = new AyaProducer(reporter).visitProgram(parser.program());
      // [chuigda]: I suggest 80 columns, or we may detect terminal width with some library
      writeCode(flags, () -> Doc.vcat(
        StmtPrettier.INSTANCE.visitAll(program, Unit.unit()).stream()), CliArgs.DistillStage.Raw);
      var loader = new ModuleListLoader(flags.modulePaths().map(path ->
        new CachedModuleLoader(new FileModuleLoader(path, reporter, builder))));
      FileModuleLoader.tyckModule(loader, program, reporter,
        () -> writeCode(flags, () -> Doc.vcat(StmtPrettier.INSTANCE.visitAll(
          program, Unit.unit()).stream()), CliArgs.DistillStage.Scoped),
        defs -> writeCode(flags, () -> Doc.vcat(defs.map(Def::toDoc)), CliArgs.DistillStage.Typed), builder);
      PrimDef.clearConcrete();
    } catch (ExprTycker.TyckerException e) {
      FileModuleLoader.handleInternalError(e);
      return e.exitCode();
    } catch (InterruptException e) {
      // TODO[ice]: proper error handling
      reporter.reportString(e.stage().name() + " interrupted due to errors.");
      if (flags.interruptedTrace()) e.printStackTrace();
    }
    if (reporter.isEmpty()) {
      reporter.reportString(flags.message().successNotion());
      return 0;
    } else {
      reporter.reportString(flags.message().failNotion());
      return -1;
    }
  }

  private void writeCode(@NotNull CompilerFlags flags, Supplier<Doc> doc, @NotNull CliArgs.DistillStage currentStage) throws IOException {
    if (currentStage != flags.distillStage()) return;
    var pathFileName = filePath.getFileName().toString();
    var dotIndex = pathFileName.indexOf('.');
    var distillDir = filePath.resolveSibling(flags.distillDir());
    if (!Files.exists(distillDir)) Files.createDirectories(distillDir);
    var htmlPath = distillDir.resolve(pathFileName
      .substring(0, dotIndex > 0 ? dotIndex : pathFileName.length()) + ".html");
    Files.writeString(htmlPath, doc.get().renderToHtml());
  }
}
