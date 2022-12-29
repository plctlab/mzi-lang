// Copyright (c) 2020-2022 Tesla (Yinsen) Zhang.
// Use of this source code is governed by the MIT license that can be found in the LICENSE.md file.
package org.aya.tyck.pat;

import kala.collection.SeqView;
import kala.collection.immutable.ImmutableSeq;
import kala.collection.mutable.MutableList;
import kala.control.Result;
import kala.tuple.Tuple;
import kala.tuple.Tuple3;
import kala.value.MutableValue;
import org.aya.concrete.Expr;
import org.aya.concrete.Pattern;
import org.aya.core.def.CtorDef;
import org.aya.core.def.Def;
import org.aya.core.pat.Pat;
import org.aya.core.pat.PatMatcher;
import org.aya.core.repr.AyaShape;
import org.aya.core.term.*;
import org.aya.core.visitor.DeltaExpander;
import org.aya.core.visitor.Expander;
import org.aya.core.visitor.Subst;
import org.aya.generic.Constants;
import org.aya.generic.util.InternalException;
import org.aya.generic.util.NormalizeMode;
import org.aya.pretty.doc.Doc;
import org.aya.ref.AnyVar;
import org.aya.ref.GenerateKind;
import org.aya.ref.LocalVar;
import org.aya.tyck.ExprTycker;
import org.aya.tyck.error.TyckOrderError;
import org.aya.tyck.trace.Trace;
import org.aya.tyck.tycker.TyckState;
import org.aya.util.Arg;
import org.aya.util.reporter.Problem;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.UnknownNullability;

/**
 * A Pattern Tycker for only one use.
 */
public class PatternTycker {
  public final @NotNull ExprTycker exprTycker;

  public final @NotNull TypedSubst bodySubst;

  public final @NotNull TypedSubst sigSubst = new TypedSubst();

  public @NotNull Def.Signature<?> signature;

  public @NotNull SeqView<Arg<Pattern>> patterns;

  public final @NotNull MutableList<Arg<Pat>> wellTyped = MutableList.create();

  public Term.@UnknownNullability Param current = null;

  public boolean hasError = false;

  protected PatternTycker(@NotNull ExprTycker exprTycker,
                          @NotNull TypedSubst bodySubst,
                          @NotNull Def.Signature<?> signature,
                          @NotNull SeqView<Arg<Pattern>> patterns) {
    this.exprTycker = exprTycker;
    this.bodySubst = bodySubst;
    this.signature = signature;
    this.patterns = patterns;
  }

  public PatternTycker(@NotNull ExprTycker exprTycker,
                       @NotNull Def.Signature<?> signature,
                       @NotNull SeqView<Arg<Pattern>> patterns) {
    this(exprTycker, new TypedSubst(), signature, patterns);
  }

  private @NotNull Pat doTyck(@NotNull Pattern pattern, @NotNull Term term, boolean resultIsProp) {
    return switch (pattern) {
      case Pattern.Absurd absurd -> {
        var selection = selectCtor(term, null, absurd);
        if (selection != null) foundError(new PatternProblem.PossiblePat(absurd, selection._3));
        yield new Pat.Absurd();
      }
      case Pattern.Tuple tuple -> {
        if (!(term.normalize(exprTycker.state, NormalizeMode.WHNF) instanceof SigmaTerm sigma))
          yield withError(new PatternProblem.TupleNonSig(tuple, term), term);
        var tupleIsProp = exprTycker.isPropType(sigma);
        if (!resultIsProp && tupleIsProp) foundError(new PatternProblem.IllegalPropPat(tuple));
        // sig.result is a dummy term
        var sig = new Def.Signature<>(sigma.params(),
          new ErrorTerm(Doc.plain("Rua"), false));
        yield new Pat.Tuple(
          tyckInner(sig, tuple.patterns().view(), tuple, resultIsProp)
            .wellTyped()
            .toImmutableSeq());
      }
      case Pattern.Ctor ctor -> {
        var var = ctor.resolved().data();
        var realCtor = selectCtor(term, var, ctor);
        if (realCtor == null) yield randomPat(term);
        var ctorRef = realCtor._3.ref();
        var dataIsProp = ctorRef.core.inProp();
        if (!resultIsProp && dataIsProp) foundError(new PatternProblem.IllegalPropPat(ctor));
        var ctorCore = ctorRef.core;

        final var dataCall = realCtor._1;
        var sig = new Def.Signature<>(Term.Param.subst(ctorCore.selfTele, realCtor._2, 0), dataCall);
        // It is possible that `ctor.params()` is empty.
        var patterns = tyckInner(sig, ctor.params().view(), ctor, resultIsProp).wellTyped.toImmutableSeq();
        yield new Pat.Ctor(realCtor._3.ref(), patterns, dataCall);
      }
      case Pattern.Bind(var pos, var bind, var tyExpr, var tyRef) -> {
        exprTycker.localCtx.put(bind, term);
        if (tyExpr != null) exprTycker.subscoped(() -> {
          exprTycker.definitionEqualities.addDirectly(bodySubst);
          var syn = exprTycker.synthesize(tyExpr);
          exprTycker.unifyTyReported(term, syn.wellTyped(), tyExpr);
          return null;
        });
        tyRef.set(term);
        yield new Pat.Bind(bind, term);
      }
      case Pattern.CalmFace(var pos) -> new Pat.Meta(MutableValue.create(),
        new LocalVar(Constants.ANONYMOUS_PREFIX, pos, GenerateKind.Anonymous.INSTANCE), term);
      case Pattern.Number(var pos, var number) -> {
        var ty = term.normalize(exprTycker.state, NormalizeMode.WHNF);
        if (ty instanceof DataCall dataCall) {
          var data = dataCall.ref().core;
          var shape = exprTycker.shapeFactory.find(data);
          if (shape.isDefined() && shape.get().shape() == AyaShape.NAT_SHAPE)
            yield new Pat.ShapedInt(number, shape.get(), dataCall);
        }
        yield withError(new PatternProblem.BadLitPattern(pattern, term), term);
      }
      case Pattern.List(var pos, var el) -> {
        // desugar `Pattern.List` to `Pattern.Ctor` here, but use `CodeShape` !
        // Note: this is a special case (maybe), If there is another similar requirement,
        //       a PatternDesugarer is recommended.
        var ty = term.normalize(exprTycker.state, NormalizeMode.WHNF);
        if (ty instanceof DataCall dataCall) {
          var data = dataCall.ref().core;
          var shape = exprTycker.shapeFactory.find(data);
          if (shape.isDefined() && shape.get().shape() == AyaShape.LIST_SHAPE)
            yield doTyck(new Pattern.FakeShapedList(pos, el, shape.get(), dataCall)
              .constructorForm(), term, resultIsProp);
        }
        yield withError(new PatternProblem.BadLitPattern(pattern, term), term);
      }
      case Pattern.As(var pos, var inner, var as, var type) -> {
        var innerPat = doTyck(inner, term, resultIsProp);

        type.set(term);
        addPatSubst(as, innerPat, term);

        yield innerPat;
      }
      case Pattern.QualifiedRef ignored -> throw new InternalException("QualifiedRef patterns should be desugared");
      case Pattern.BinOpSeq ignored -> throw new InternalException("BinOpSeq patterns should be desugared");
    };
  }

  /**
   * Start to tyck each {@link Pattern} with {@link Def.Signature}.
   * {@param outerPattern} should be specified if stream is empty.
   *
   * @param outerPattern null if visiting the whole pattern (like `A, x, ctor a b`). This is only used for error reporting.
   *                     For now, {@param outerPattern} is used when {@link PatternTycker#signature} is not empty
   *                     but {@link PatternTycker#patterns} is empty, it is possible when matching parameters of Ctor.
   */
  public @NotNull PatternTycker.TyckResult tyck(
    @Nullable Pattern outerPattern,
    @Nullable Expr body,
    boolean resultIsProp
  ) {
    assert current == null;
    // last pattern which user given (not aya generated)
    @Nullable Arg<Pattern> lastPat = null;
    while (signature.param().isNotEmpty()) {
      current = signature.param().first();
      Arg<Pattern> pat;
      // Type explicit, does not have pattern
      if (patterns.isEmpty()) {
        if (body instanceof Expr.Lambda(
          var lamPos, var lamParam, var lamBody
        ) && lamParam.explicit() == current.explicit()) {
          body = lamBody;
          var pattern = new Pattern.Bind(lamPos, lamParam.ref(), lamParam.type(), MutableValue.create());
          pat = new Arg<>(pattern, current.explicit());
        } else if (current.explicit()) {
          Pattern errorPattern;

          if (lastPat == null) {
            assert outerPattern != null;
            errorPattern = outerPattern;
          } else {
            errorPattern = lastPat.term();
          }

          foundError(new PatternProblem.InsufficientPattern(errorPattern, current));
          return done(body);
        } else {
          // Type is implicit, does not have pattern
          generatePat();
          continue;
        }
      } else if (current.explicit()) {
        // Type explicit, does have pattern
        pat = patterns.first();
        lastPat = pat;
        patterns = patterns.drop(1);
        if (!pat.explicit()) {
          foundError(new PatternProblem.TooManyImplicitPattern(pat.term(), current));
          return done(body);
        }
      } else {
        // Type is implicit, does have pattern
        pat = patterns.first();
        if (pat.explicit()) {
          // Pattern is explicit, so we leave it to the next type, do not "consume" it
          generatePat();
          continue;
        } else {
          lastPat = pat;
          patterns = patterns.drop(1);
        }
        // ^ Pattern is implicit, so we "consume" it (stream.drop(1))
      }
      updateSig(pat, resultIsProp);
    }
    if (patterns.isNotEmpty()) {
      foundError(new PatternProblem
        .TooManyPattern(patterns.first().term(), signature.result().freezeHoles(exprTycker.state)));
    }
    return done(body);
  }

  private @NotNull PatternTycker.TyckResult done(@Nullable Expr body) {
    return new TyckResult(wellTyped.toImmutableSeq(), signature.result().subst(sigSubst.subst()), body);
  }

  /**
   * Tyck the inner patterns with a new tycker
   */
  private @NotNull PatternTycker.TyckResult tyckInner(
    @NotNull Def.Signature<?> signature,
    @NotNull SeqView<Arg<Pattern>> patterns,
    @NotNull Pattern outerPattern,
    boolean resultIsProp
  ) {
    var sub = new PatternTycker(this.exprTycker, this.bodySubst, signature, patterns);
    var result = sub.tyck(outerPattern, null, resultIsProp);

    this.hasError = hasError || sub.hasError;

    return result;
  }

  private void onTyck(@NotNull Runnable runnable) {
    current = current.subst(sigSubst.subst());
    runnable.run();
    signature = new Def.Signature<>(signature.param().drop(1), signature.result());
  }

  /**
   * A user given pattern matches a parameter, we update the signature.
   *
   * @apiNote {@code data.param.explicit = arg.explicit} or the world explode.
   */
  private void updateSig(Arg<Pattern> arg, boolean resultIsProp) {
    onTyck(() -> {
      var type = current.type();
      var pat = arg.term();
      var res = exprTycker.traced(() -> new Trace.PatT(type, pat, pat.sourcePos()),
        () -> doTyck(pat, type, resultIsProp));
      addSigSubst(current, res);
      wellTyped.append(new Arg<>(res, arg.explicit()));
    });
  }

  /**
   * For every implicit parameter that not explicitly (no user given pattern) matched,
   * we generate a MetaPat for each,
   * so that they can be inferred during {@link ClauseTycker#checkLhs(ExprTycker, Pattern.Clause, Def.Signature, boolean, boolean)}
   *
   * @apiNote {@code daat.param.explicit = false} or the world explode.
   */
  private void generatePat() {
    onTyck(() -> {
      var ref = current.ref();
      Pat bind;
      var freshVar = ref.rename();
      if (current.type().normalize(exprTycker.state, NormalizeMode.WHNF) instanceof DataCall dataCall) {
        bind = new Pat.Meta(MutableValue.create(), freshVar, dataCall);
      } else {
        bind = new Pat.Bind(freshVar, current.type());
        exprTycker.localCtx.put(freshVar, current.type());
      }
      wellTyped.append(new Arg<>(bind, false));
      addSigSubst(current, bind);
    });
  }

  /**
   * Adding a subst for body (rhs)
   */
  private void addPatSubst(@NotNull AnyVar var, @NotNull Pat pat, @NotNull Term type) {
    bodySubst.addDirectly(var, pat.toTerm(), type);
  }

  /**
   * Adding a subst for signature and body
   */
  private void addSigSubst(@NotNull Term.Param param, @NotNull Pat pat) {
    addPatSubst(param.ref(), pat, param.type());
    sigSubst.addDirectly(param.ref(), pat.toTerm(), param.type());
  }

  /// region Error Reporting

  private void foundError(@Nullable Problem problem) {
    hasError = true;
    if (problem != null) exprTycker.reporter.report(problem);
  }

  private @NotNull Pat withError(Problem problem, Term param) {
    foundError(problem);
    // In case something's wrong, produce a random pattern
    return randomPat(param);
  }

  /// endregion

  /// region Helper

  private @NotNull Pat randomPat(Term param) {
    return new Pat.Bind(new LocalVar("?"), param);
  }

  /**
   * @param name if null, the selection will be performed on all constructors
   * @return null means selection failed
   */
  private @Nullable Tuple3<DataCall, Subst, ConCall.Head>
  selectCtor(Term param, @Nullable AnyVar name, @NotNull Pattern pos) {
    if (!(param.normalize(exprTycker.state, NormalizeMode.WHNF) instanceof DataCall dataCall)) {
      foundError(new PatternProblem.SplittingOnNonData(pos, param));
      return null;
    }
    var dataRef = dataCall.ref();
    // We are checking an absurd pattern, but the data is not yet fully checked
    var core = dataRef.core;
    if (core == null && name == null) {
      foundError(new TyckOrderError.NotYetTyckedError(pos.sourcePos(), dataRef));
      return null;
    }
    var body = Def.dataBody(dataRef);
    for (var ctor : body) {
      if (name != null && ctor.ref() != name) continue;
      var matchy = mischa(dataCall, ctor, exprTycker.state);
      if (matchy.isOk()) {
        return Tuple.of(dataCall, matchy.get(), dataCall.conHead(ctor.ref()));
      }
      // For absurd pattern, we look at the next constructor
      if (name == null) {
        // Is blocked
        if (matchy.getErr()) {
          foundError(new PatternProblem.BlockedEval(pos, dataCall));
          return null;
        }
        continue;
      }
      // Since we cannot have two constructors of the same name,
      // if the name-matching constructor mismatches the type,
      // we get an error.
      foundError(new PatternProblem.UnavailableCtor(pos, dataCall));
      return null;
    }
    // Here, name != null, and is not in the list of checked body
    if (core == null) {
      foundError(new TyckOrderError.NotYetTyckedError(pos.sourcePos(), name));
      return null;
    }
    if (name != null) foundError(new PatternProblem.UnknownCtor(pos));
    return null;
  }

  public static Result<Subst, Boolean> mischa(DataCall dataCall, CtorDef ctor, @NotNull TyckState state) {
    if (ctor.pats.isNotEmpty()) {
      return PatMatcher.tryBuildSubst(true, ctor.pats, dataCall.args(), new Expander.WHNFer(state));
    } else {
      return kala.control.Result.ok(DeltaExpander.buildSubst(Def.defTele(dataCall.ref()), dataCall.args()));
    }
  }

  /// endregion

  public record TyckResult(
    @NotNull ImmutableSeq<Arg<Pat>> wellTyped,
    @NotNull Term codomain,
    @UnknownNullability Expr newBody
  ) {}
}
