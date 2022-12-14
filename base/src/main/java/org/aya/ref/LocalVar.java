// Copyright (c) 2020-2022 Tesla (Yinsen) Zhang.
// Use of this source code is governed by the MIT license that can be found in the LICENSE.md file.
package org.aya.ref;

import org.aya.generic.Constants;
import org.aya.util.error.SourcePos;
import org.aya.util.error.WithPos;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * @param generateKind whether this LocalVar is generated by aya, not exists in the source code. For example:
 *                     <ul>
 *                       <li>
 *                       `| S Nat` is a ctor, but it actually is `| S (_1 Nat)` where `_1` is generated.
 *                       </li>
 *                       <li>
 *                       `variable A : Type` is a generalized variable,
 *                       but a local var is generated when some one reference to it. For example:
 *                       `def foo : A => {??}` actually is `def foo {A : Type} : A => {??}`
 *                       where `A` is a generated local var
 *                       </li>
 *                     </ul>
 * @author ice1000
 * @apiNote {@link LocalVar#generateKind()} is None after deserializing from an aya binary,
 * because we don't need to use them after deserializing for now.
 */
public record LocalVar(
  @NotNull String name,
  @NotNull SourcePos definition,
  @NotNull GenerateKind generateKind
) implements AnyVar {
  public static final @NotNull LocalVar IGNORED =
    new LocalVar(Constants.ANONYMOUS_PREFIX, SourcePos.NONE, GenerateKind.Anonymous.INSTANCE);

  public LocalVar(@NotNull String name, @NotNull SourcePos definition) {
    this(name, definition, GenerateKind.None.INSTANCE);
  }

  public LocalVar(@NotNull String name) {
    this(name, SourcePos.NONE);
  }

  public static @NotNull LocalVar from(@NotNull WithPos<String> name) {
    return new LocalVar(name.data(), name.sourcePos());
  }

  public @NotNull LocalVar rename() {
    return new LocalVar(name, definition, new GenerateKind.Renamed(this));
  }

  public boolean isGenerated() {
    return generateKind != GenerateKind.None.INSTANCE;
  }

  @Override public boolean equals(@Nullable Object o) {
    return this == o;
  }

  @Override public int hashCode() {
    return System.identityHashCode(this);
  }
}
