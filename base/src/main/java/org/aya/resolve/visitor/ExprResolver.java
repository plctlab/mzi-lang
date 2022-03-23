// Copyright (c) 2020-2022 Yinsen (Tesla) Zhang.
// Use of this source code is governed by the MIT license that can be found in the LICENSE.md file.
package org.aya.resolve.visitor;

import kala.collection.SeqLike;
import kala.collection.SeqView;
import kala.collection.mutable.DynamicSeq;
import kala.collection.mutable.MutableLinkedHashMap;
import kala.collection.mutable.MutableMap;
import kala.collection.mutable.MutableStack;
import kala.tuple.Tuple2;
import org.aya.concrete.Expr;
import org.aya.concrete.stmt.Decl;
import org.aya.concrete.visitor.ExprFixpoint;
import org.aya.generic.ref.GeneralizedVar;
import org.aya.ref.DefVar;
import org.aya.ref.Var;
import org.aya.resolve.context.Context;
import org.aya.resolve.error.GeneralizedNotAvailableError;
import org.aya.tyck.error.FieldProblem;
import org.aya.tyck.order.TyckOrder;
import org.aya.tyck.order.TyckUnit;
import org.aya.util.error.SourcePos;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;

/**
 * Resolves bindings.
 *
 * @param allowedGeneralizes will be filled with generalized vars if allowGeneralized,
 *                           and represents the allowed generalized level vars otherwise
 * @author re-xyr, ice1000
 * @implSpec allowedGeneralizes must be linked map
 * @see StmtResolver
 */
public record ExprResolver(
  @NotNull Options options,
  @NotNull MutableMap<GeneralizedVar, Expr.Param> allowedGeneralizes,
  @NotNull DynamicSeq<TyckOrder> reference,
  @NotNull MutableStack<Where> where,
  @Nullable Consumer<TyckUnit> parentAdd
) implements ExprFixpoint<Context> {
  enum Where {
    Head, Body
  }

  public void enterHead() {
    where.push(Where.Head);
    reference.clear();
  }

  public void enterBody() {
    where.push(Where.Body);
    reference.clear();
  }

  private void addReference(@NotNull TyckUnit unit) {
    if (parentAdd != null) parentAdd.accept(unit);
    if (where.isEmpty()) throw new IllegalStateException("where am I?");
    if (where.peek() == Where.Head) {
      reference.append(new TyckOrder.Head(unit));
      reference.append(new TyckOrder.Body(unit));
    } else {
      reference.append(new TyckOrder.Body(unit));
    }
  }

  /**
   * @param allowLevels true for signatures, false for bodies
   */
  public record Options(boolean allowLevels, boolean allowGeneralized) {
  }

  public static final @NotNull Options RESTRICTIVE = new Options(false, false);
  public static final @NotNull Options LAX = new ExprResolver.Options(true, true);

  public ExprResolver(@NotNull Options options) {
    this(options, MutableLinkedHashMap.of(), DynamicSeq.create(), MutableStack.create(), null);
  }

  public @NotNull ExprResolver member(@NotNull TyckUnit decl) {
    return new ExprResolver(RESTRICTIVE, allowedGeneralizes, DynamicSeq.of(new TyckOrder.Head(decl)), MutableStack.create(),
      this::addReference);
  }

  public @NotNull ExprResolver body() {
    return new ExprResolver(RESTRICTIVE, allowedGeneralizes, reference, MutableStack.create(),
      this::addReference);
  }

  @Override public @NotNull Expr visitUnresolved(@NotNull Expr.UnresolvedExpr expr, Context ctx) {
    var sourcePos = expr.sourcePos();
    return switch (ctx.get(expr.name())) {
      case GeneralizedVar generalized -> {
        if (options.allowGeneralized) {
          // Ordered set semantics. Do not expect too many generalized vars.
          if (!allowedGeneralizes.containsKey(generalized)) {
            var owner = generalized.owner;
            assert owner != null : "Sanity check";
            allowedGeneralizes.put(generalized, owner.toExpr(false, generalized.toLocal()));
            addReference(owner);
          }
        } else if (!allowedGeneralizes.containsKey(generalized))
          generalizedUnavailable(ctx, sourcePos, generalized);
        yield new Expr.RefExpr(sourcePos, allowedGeneralizes.get(generalized).ref());
      }
      case DefVar<?, ?> ref -> {
        switch (ref.concrete) {
          case null -> {
            // RefExpr is referring to a serialized core which is already tycked.
            // Collecting tyck order for tycked terms is unnecessary, just skip.
            assert ref.core != null; // ensure it is tycked
          }
          case TyckUnit unit && unit instanceof Decl decl && decl.ownerSample != null -> addReference(decl.ownerSample);
          case TyckUnit unit -> addReference(unit);
        }
        yield new Expr.RefExpr(sourcePos, ref);
      }
      case Var var -> new Expr.RefExpr(sourcePos, var);
    };
  }

  @Override public @NotNull Expr visitProj(@NotNull Expr.ProjExpr expr, Context context) {
    var tup = expr.tup().accept(this, context);
    if (expr.ix().isLeft())
      return new Expr.ProjExpr(expr.sourcePos(), tup, expr.ix(), expr.resolvedIx(), expr.theCore());
    var projName = expr.ix().getRightValue();
    var resolvedIx = context.getMaybe(projName);
    if (resolvedIx == null) context.reportAndThrow(new FieldProblem.UnknownField(expr, projName.join()));
    return new Expr.ProjExpr(expr.sourcePos(), tup, expr.ix(), resolvedIx, expr.theCore());
  }

  private void generalizedUnavailable(Context ctx, SourcePos refExpr, Var var) {
    ctx.reporter().report(new GeneralizedNotAvailableError(refExpr, var));
    throw new Context.ResolvingInterruptedException();
  }

  public @NotNull Tuple2<Expr.Param, Context> visitParam(@NotNull Expr.Param param, Context ctx) {
    var type = param.type().accept(this, ctx);
    return Tuple2.of(new Expr.Param(param, type), ctx.bind(param.ref(), param.sourcePos()));
  }

  @Contract(pure = true)
  public @NotNull Tuple2<SeqView<Expr.Param>, Context>
  resolveParams(@NotNull SeqLike<Expr.Param> params, Context ctx) {
    if (params.isEmpty()) return Tuple2.of(SeqView.empty(), ctx);
    var first = params.first();
    var type = first.type().accept(this, ctx);
    var newCtx = ctx.bind(first.ref(), first.sourcePos());
    var result = resolveParams(params.view().drop(1), newCtx);
    return Tuple2.of(result._1.prepended(new Expr.Param(first, type)), result._2);
  }

  @Override public @NotNull Expr visitLam(@NotNull Expr.LamExpr expr, Context ctx) {
    var param = visitParam(expr.param(), ctx);
    var body = expr.body().accept(this, param._2);
    return new Expr.LamExpr(expr.sourcePos(), param._1, body);
  }

  @Override public Expr.@NotNull Field visitField(Expr.@NotNull Field t, Context context) {
    for (var binding : t.bindings()) context = context.bind(binding.data(), binding.sourcePos());
    return ExprFixpoint.super.visitField(t, context);
  }

  @Override public @NotNull Expr visitPi(@NotNull Expr.PiExpr expr, Context ctx) {
    var param = visitParam(expr.param(), ctx);
    var last = expr.last().accept(this, param._2);
    return new Expr.PiExpr(expr.sourcePos(), expr.co(), param._1, last);
  }

  @Override public @NotNull Expr visitSigma(@NotNull Expr.SigmaExpr expr, Context ctx) {
    var params = resolveParams(expr.params(), ctx);
    return new Expr.SigmaExpr(expr.sourcePos(), expr.co(), params._1.toImmutableSeq());
  }

  @Override public @NotNull Expr visitHole(@NotNull Expr.HoleExpr expr, Context context) {
    expr.accessibleLocal().set(context.collect(DynamicSeq.create()).toImmutableSeq());
    return ExprFixpoint.super.visitHole(expr, context);
  }
}