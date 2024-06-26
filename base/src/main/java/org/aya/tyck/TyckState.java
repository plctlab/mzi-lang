// Copyright (c) 2020-2024 Tesla (Yinsen) Zhang.
// Use of this source code is governed by the MIT license that can be found in the LICENSE.md file.
package org.aya.tyck;

import kala.collection.mutable.MutableList;
import kala.collection.mutable.MutableMap;
import org.aya.generic.AyaDocile;
import org.aya.pretty.doc.Doc;
import org.aya.primitive.PrimFactory;
import org.aya.primitive.ShapeFactory;
import org.aya.syntax.core.term.Term;
import org.aya.syntax.core.term.call.MetaCall;
import org.aya.syntax.ref.LocalCtx;
import org.aya.syntax.ref.MetaVar;
import org.aya.tyck.error.MetaVarProblem;
import org.aya.unify.Unifier;
import org.aya.util.Ordering;
import org.aya.util.error.SourcePos;
import org.aya.util.error.WithPos;
import org.aya.util.prettier.PrettierOptions;
import org.aya.util.reporter.Reporter;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;
import java.util.function.UnaryOperator;

public record TyckState(
  @NotNull MutableList<Eqn> eqns,
  @NotNull MutableList<WithPos<MetaVar>> activeMetas,
  @NotNull MutableMap<MetaVar, Term> solutions,
  @NotNull ShapeFactory shapeFactory,
  @NotNull PrimFactory primFactory
) {
  public TyckState(@NotNull ShapeFactory shapeFactory, @NotNull PrimFactory primFactory) {
    this(MutableList.create(), MutableList.create(), MutableMap.create(),
      shapeFactory, primFactory);
  }
  @ApiStatus.Internal
  public void solve(MetaVar meta, Term candidate) { solutions.put(meta, candidate); }

  private boolean solveEqn(@NotNull Reporter reporter, @NotNull Eqn eqn, boolean allowDelay) {
    var unifier = new Unifier(this, eqn.localCtx, reporter, eqn.pos, eqn.cmp, allowDelay);
    // We're at the end of the type checking, let's solve something that we didn't want to solve before
    if (!allowDelay) unifier.allowVague = true;
    return unifier.checkEqn(eqn);
  }

  public void solveMetas(@NotNull Reporter reporter) {
    int postSimplificationSize = -1;
    var evilEqns = MutableList.<Eqn>create();
    while (eqns.isNotEmpty()) {
      //noinspection StatementWithEmptyBody
      while (simplify(reporter)) ;
      var eqns = this.eqns.toImmutableSeq();
      if (postSimplificationSize == eqns.size()) {
        // TODO: report error, cannot solve eqns
        reporter.report(new MetaVarProblem.CannotSolveEquations(eqns));
        return;
      } else postSimplificationSize = eqns.size();
      // If the standard 'pattern' fragment cannot solve all equations, try to use a nonstandard method
      if (eqns.isNotEmpty()) for (var eqn : eqns) {
        if (solveEqn(reporter, eqn, false)) evilEqns.append(eqn);
      }
    }
    if (evilEqns.isNotEmpty()) {
      reporter.report(new MetaVarProblem.DidSomethingBad(evilEqns.toImmutableArray()));
    }
  }

  public @NotNull Term computeSolution(@NotNull MetaCall meta, @NotNull UnaryOperator<Term> f) {
    return solutions.getOption(meta.ref())
      .map(sol -> f.apply(MetaCall.app(meta.ref(), sol, meta.args())))
      .getOrDefault(meta);
  }

  /** @return true if <code>this.eqns</code> and <code>this.activeMetas</code> are mutated. */
  private boolean simplify(@NotNull Reporter reporter) {
    var removingMetas = MutableList.<WithPos<MetaVar>>create();
    for (var activeMeta : activeMetas) {
      var v = activeMeta.data();
      if (solutions.containsKey(v)) {
        eqns.retainIf(eqn -> {
          // If the blocking meta is solved, we can check again
          if (eqn.lhs.ref() == v) {
            solveEqn(reporter, eqn, true);
            return false;
          } else return true;
        });
        removingMetas.append(activeMeta);
      }
    }
    activeMetas.removeIf(removingMetas::contains);
    return removingMetas.isNotEmpty();
  }

  public void addEqn(Eqn eqn) {
    eqns.append(eqn);
    var currentActiveMetas = activeMetas.size();
    var consumer = new Consumer<Term>() {
      @Override public void accept(Term term) {
        if (term instanceof MetaCall hole && !solutions.containsKey(hole.ref()))
          activeMetas.append(new WithPos<>(eqn.pos, hole.ref()));
        term.descent(tm -> {
          accept(tm);
          return tm;
        });
      }
    };
    consumer.accept(eqn.lhs);
    consumer.accept(eqn.rhs);
    assert activeMetas.sizeGreaterThan(currentActiveMetas) : "Adding a bad equation";
  }

  public void clearTmp() {
    eqns.clear();
    activeMetas.clear();
    solutions.clear();
  }

  public record Eqn(
    @NotNull MetaCall lhs, @NotNull Term rhs, @Nullable Term type,
    @NotNull Ordering cmp, @NotNull SourcePos pos,
    @NotNull LocalCtx localCtx
  ) implements AyaDocile {
    public @NotNull Doc toDoc(@NotNull PrettierOptions options) {
      return Doc.stickySep(lhs.toDoc(options), Doc.symbol(cmp.symbol), rhs.toDoc(options));
    }
  }
}
