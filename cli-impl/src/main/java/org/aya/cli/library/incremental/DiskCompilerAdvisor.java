// Copyright (c) 2020-2024 Tesla (Yinsen) Zhang.
// Use of this source code is governed by the MIT license that can be found in the LICENSE.md file.
package org.aya.cli.library.incremental;

import kala.collection.immutable.ImmutableSeq;
import kala.collection.mutable.MutableList;
import org.aya.cli.library.source.LibraryOwner;
import org.aya.cli.library.source.LibrarySource;
import org.aya.cli.utils.CompilerUtil;
import org.aya.compiler.CompiledModule;
import org.aya.compiler.FileSerializer;
import org.aya.compiler.ModuleSerializer;
import org.aya.compiler.NameSerializer;
import org.aya.resolve.ResolveInfo;
import org.aya.resolve.context.EmptyContext;
import org.aya.resolve.module.ModuleLoader;
import org.aya.syntax.core.def.TopLevelDef;
import org.aya.syntax.core.def.TyckDef;
import org.aya.syntax.ref.ModulePath;
import org.aya.syntax.ref.QPath;
import org.aya.util.FileUtil;
import org.aya.util.reporter.Reporter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.tools.ToolProvider;
import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class DiskCompilerAdvisor implements CompilerAdvisor {
  private static class AyaClassLoader extends URLClassLoader {
    public MutableList<Path> urls = MutableList.create();
    public AyaClassLoader() {
      super(new URL[0], DiskCompilerAdvisor.class.getClassLoader());
    }
    public void addURL(Path url) throws MalformedURLException {
      addURL(url.toUri().toURL());
      urls.append(url);
    }
  }
  private final AyaClassLoader cl = new AyaClassLoader();
  @Override public void close() throws Exception { cl.close(); }

  @Override public boolean isSourceModified(@NotNull LibrarySource source) {
    try {
      var core = source.compiledCorePath();
      if (!Files.exists(core)) return true;
      return Files.getLastModifiedTime(source.underlyingFile())
        .compareTo(Files.getLastModifiedTime(core)) > 0;
    } catch (IOException ignore) {
      return true;
    }
  }

  @Override public void updateLastModified(@NotNull LibrarySource source) {
    try {
      var core = source.compiledCorePath();
      Files.setLastModifiedTime(core, Files.getLastModifiedTime(source.underlyingFile()));
    } catch (IOException ignore) {
    }
  }

  @Override public void prepareLibraryOutput(@NotNull LibraryOwner owner) throws IOException {
    Files.createDirectories(owner.outDir());
  }

  @Override public void clearLibraryOutput(@NotNull LibraryOwner owner) throws IOException {
    FileUtil.deleteRecursively(owner.outDir());
  }

  @Override public void clearModuleOutput(@NotNull LibrarySource source) throws IOException {
    Files.deleteIfExists(source.compiledCorePath());
  }
  @Override public @Nullable ResolveInfo doLoadCompiledCore(
    @NotNull Reporter reporter,
    @NotNull LibraryOwner owner, @NotNull ModulePath mod,
    @Nullable Path sourcePath,
    @Nullable Path corePath,
    @NotNull ModuleLoader recurseLoader
  ) throws IOException, ClassNotFoundException {
    if (corePath == null || sourcePath == null) return null;
    if (!Files.exists(corePath)) return null;

    var context = new EmptyContext(reporter, sourcePath).derive(mod);
    try (var inputStream = FileUtil.ois(corePath)) {
      var compiledAya = (CompiledModule) inputStream.readObject();
      var parentCount = mod.size();
      var baseDir = corePath;
      for (int i = 0; i < parentCount; i++) baseDir = baseDir.getParent();
      baseDir = computeBaseDir(baseDir);
      cl.addURL(baseDir);
      cl.loadClass(NameSerializer.getModuleReference(QPath.fileLevel(mod)));
      return compiledAya.toResolveInfo(recurseLoader, context, cl);
    }
  }

  @Override public void doSaveCompiledCore(
    @NotNull LibrarySource file,
    @NotNull ResolveInfo resolveInfo,
    @NotNull ImmutableSeq<TyckDef> defs
  ) throws IOException {
    var javaCode = new FileSerializer(resolveInfo.shapeFactory())
      .serialize(new ModuleSerializer.ModuleResult(
        QPath.fileLevel(file.moduleName()), defs.filterIsInstance(TopLevelDef.class), ImmutableSeq.empty()))
      .result();
    var baseDir = computeBaseDir(file.owner().outDir()).toAbsolutePath();
    var relativePath = NameSerializer.getReference(QPath.fileLevel(file.moduleName()), null,
      NameSerializer.NameType.ClassPath) + ".java";
    var javaSrcPath = baseDir.resolve(relativePath);
    FileUtil.writeString(javaSrcPath, javaCode);
    var compiler = ToolProvider.getSystemJavaCompiler();
    var fileManager = compiler.getStandardFileManager(null, null, null);
    var compilationUnits = fileManager.getJavaFileObjects(javaSrcPath);
    var classpath = cl.urls.view()
      .appended(baseDir)
      .map(Path::toString)
      .appended(System.getProperty("java.class.path"));
    var options = List.of("-classpath", classpath.joinToString(File.pathSeparator), "--enable-preview", "--release", "21");
    var task = compiler.getTask(null, fileManager, null, options, null, compilationUnits);
    task.call();
    // Files.delete(javaSrcPath);
    var coreFile = file.compiledCorePath();
    CompilerUtil.saveCompiledCore(coreFile, defs, resolveInfo);
  }

  private static @NotNull Path computeBaseDir(@NotNull Path outDir) {
    return outDir.resolve("compiled");
  }
}
