// Copyright (c) 2020-2024 Tesla (Yinsen) Zhang.
// Use of this source code is governed by the MIT license that can be found in the LICENSE.md file.
package org.aya.cli.library.json;

import kala.collection.immutable.ImmutableSeq;
import org.aya.cli.utils.LiteratePrettierOptions;
import org.aya.util.Version;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;

/**
 * The compiler aspect of library description file, with generated settings.
 *
 * @author re-xyr, kiva
 * @implNote <a href="https://github.com/ice1000/aya-prover/issues/491">issue #491</a>
 */
public record LibraryConfig(
  @NotNull Version ayaVersion,
  @NotNull String name,
  @NotNull String version,
  @NotNull Path libraryRoot,
  @NotNull Path librarySrcRoot,
  @NotNull Path libraryBuildRoot,
  @NotNull Path libraryOutRoot,
  @NotNull LibraryLiterateConfig literateConfig,
  @NotNull ImmutableSeq<LibraryDependency> deps
) {
  /// @param datetimeFrontMatterKey it makes little sense to specify the "values" too,
  ///                               because a library has many files, and each file should
  ///                               have their own modified time, which is unrealistic to retrieve.
  public record LibraryLiterateConfig(
    @Nullable LiteratePrettierOptions pretty,
    @Nullable String datetimeFrontMatterKey,
    @NotNull String linkPrefix,
    @NotNull Path outputPath
  ) {
  }
}
