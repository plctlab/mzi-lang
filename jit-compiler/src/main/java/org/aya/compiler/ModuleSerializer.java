// Copyright (c) 2020-2024 Tesla (Yinsen) Zhang.
// Use of this source code is governed by the MIT license that can be found in the LICENSE.md file.
package org.aya.compiler;

import kala.collection.immutable.ImmutableSeq;
import org.aya.generic.NameGenerator;
import org.aya.primitive.ShapeFactory;
import org.aya.syntax.core.def.*;
import org.aya.syntax.ref.QPath;
import org.aya.util.IterableUtil;
import org.jetbrains.annotations.NotNull;

import static org.aya.compiler.NameSerializer.javifyClassName;

/**
 * Serializing a module, note that it may not a file module, so we need not to make importing.
 */
public final class ModuleSerializer extends AbstractSerializer<ModuleSerializer.ModuleResult> {
  public record ModuleResult(
    @NotNull QPath name,
    @NotNull ImmutableSeq<TopLevelDef> defs,
    @NotNull ImmutableSeq<ModuleResult> submodules
  ) { }

  private final @NotNull ShapeFactory shapeFactory;

  public ModuleSerializer(@NotNull SourceBuilder builder, @NotNull ShapeFactory shapeFactory) {
    super(builder);
    this.shapeFactory = shapeFactory;
  }

  private void serializeCons(@NotNull DataDef dataDef, @NotNull DataSerializer serializer) {
    var ser = new ConSerializer(serializer);
    IterableUtil.forEach(dataDef.body, ser::appendLine, ser::serialize);
  }

  private void doSerialize(@NotNull TyckDef unit) {
    switch (unit) {
      case FnDef teleDef -> new FnSerializer(this, shapeFactory)
        .serialize(teleDef);
      case DataDef dataDef -> new DataSerializer(this, shapeFactory, ser -> serializeCons(dataDef, ser))
        .serialize(dataDef);
      case ConDef conDef -> new ConSerializer(this)
        .serialize(conDef);
      case PrimDef primDef -> new PrimSerializer(this)
        .serialize(primDef);
    }
  }

  private void doSerialize(ModuleResult unit, boolean isTopLevel) {
    var moduleName = javifyClassName(unit.name, null);

    buildClass(moduleName, null, !isTopLevel, () -> {
      IterableUtil.forEach(unit.defs, this::appendLine, this::doSerialize);
      // serialize submodules
      if (unit.submodules.isNotEmpty()) appendLine();
      IterableUtil.forEach(unit.submodules, this::appendLine, r -> doSerialize(r, false));
    });
  }

  @Override public ModuleSerializer serialize(ModuleResult unit) {
    doSerialize(unit, true);

    return this;
  }
}
