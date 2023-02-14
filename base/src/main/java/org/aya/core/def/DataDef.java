// Copyright (c) 2020-2023 Tesla (Yinsen) Zhang.
// Use of this source code is governed by the MIT license that can be found in the LICENSE.md file.
package org.aya.core.def;

import kala.collection.immutable.ImmutableSeq;
import org.aya.concrete.stmt.decl.TeleDecl;
import org.aya.core.pat.Pat;
import org.aya.core.term.*;
import org.aya.ref.DefVar;
import org.jetbrains.annotations.NotNull;

import java.util.function.UnaryOperator;

/**
 * core data definition, corresponding to {@link TeleDecl.DataDecl}
 *
 * @author kiva
 */
public final class DataDef extends UserDef.Type {
  public final @NotNull DefVar<DataDef, TeleDecl.DataDecl> ref;
  public final @NotNull ImmutableSeq<CtorDef> body;

  public DataDef(
    @NotNull DefVar<DataDef, TeleDecl.DataDecl> ref, @NotNull ImmutableSeq<Term.Param> telescope,
    SortTerm result, @NotNull ImmutableSeq<CtorDef> body
  ) {
    super(telescope, result);
    ref.core = this;
    this.ref = ref;
    this.body = body;
  }

  public static @NotNull DefVar<DataDef, TeleDecl.DataDecl> fromCtor(@NotNull DefVar<CtorDef, TeleDecl.DataCtor> conHead) {
    if (conHead.core != null) return conHead.core.dataRef;
    else return conHead.concrete.dataRef;
  }

  public @NotNull DefVar<DataDef, TeleDecl.DataDecl> ref() {
    return ref;
  }

  public @NotNull DataDef update(@NotNull ImmutableSeq<Term.Param> telescope, @NotNull SortTerm result, @NotNull ImmutableSeq<CtorDef> body) {
    return telescope.sameElements(telescope(), true) && result == result() && body.sameElements(this.body, true)
      ? this : new DataDef(ref, telescope, result, body);
  }

  @Override public @NotNull DataDef descent(@NotNull UnaryOperator<Term> f, @NotNull UnaryOperator<Pat> g) {
    return update(telescope.map(p -> p.descent(f)), (SortTerm) f.apply(result), body.map(c -> c.descent(f, g)));
  }

  /**
   * @author ice1000
   */
  public record CtorTelescopes(
    @NotNull ImmutableSeq<Term.Param> ownerTele,
    @NotNull ImmutableSeq<Term.Param> selfTele,
    @NotNull DataCall ret
  ) {
    public CtorTelescopes(@NotNull CtorDef def) {
      this(def.ownerTele.map(Term.Param::implicitify), def.selfTele, (DataCall) def.result);
    }

    public @NotNull Term toConCall(DefVar<CtorDef, TeleDecl.DataCtor> conVar, int ulift) {
      var body = new ConCall(fromCtor(conVar), conVar,
        ret.args(), ulift, selfTele.map(Term.Param::toArg));
      return LamTerm.make(ownerTele.view().concat(selfTele).map(LamTerm::param), body).rename();
    }
  }
}
