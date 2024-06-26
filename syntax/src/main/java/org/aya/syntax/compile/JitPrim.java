// Copyright (c) 2020-2024 Tesla (Yinsen) Zhang.
// Use of this source code is governed by the MIT license that can be found in the LICENSE.md file.
package org.aya.syntax.compile;

import kala.collection.Seq;
import org.aya.generic.stmt.Reducible;
import org.aya.syntax.core.def.PrimDef;
import org.aya.syntax.core.def.PrimDefLike;
import org.aya.syntax.core.term.Term;
import org.aya.util.error.Panic;
import org.jetbrains.annotations.NotNull;

public abstract non-sealed class JitPrim extends JitDef implements PrimDefLike {
  public final PrimDef.ID id;
  @Override public PrimDef.@NotNull ID id() { return id; }
  protected JitPrim(int telescopeSize, boolean[] telescopeLicit, String[] telescopeName, PrimDef.ID id) {
    super(telescopeSize, telescopeLicit, telescopeName);
    this.id = id;
  }
}
