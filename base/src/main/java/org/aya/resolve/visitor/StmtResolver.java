// Copyright (c) 2020-2022 Yinsen (Tesla) Zhang.
// Use of this source code is governed by the MIT license that can be found in the LICENSE.md file.
package org.aya.resolve.visitor;

import kala.collection.SeqLike;
import kala.collection.SeqView;
import kala.tuple.Tuple;
import kala.tuple.Tuple2;
import kala.value.Ref;
import org.aya.concrete.Pattern;
import org.aya.concrete.desugar.AyaBinOpSet;
import org.aya.concrete.error.OperatorProblem;
import org.aya.concrete.remark.Remark;
import org.aya.concrete.stmt.*;
import org.aya.core.def.CtorDef;
import org.aya.core.def.PrimDef;
import org.aya.ref.DefVar;
import org.aya.ref.LocalVar;
import org.aya.resolve.ResolveInfo;
import org.aya.resolve.context.Context;
import org.aya.resolve.error.UnknownOperatorError;
import org.aya.tyck.order.TyckOrder;
import org.aya.util.binop.OpDecl;
import org.aya.util.error.SourcePos;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Resolves expressions inside stmts, after {@link StmtShallowResolver}
 *
 * @author re-xyr, ice1000, kiva
 * @see StmtShallowResolver
 * @see ExprResolver
 */
public interface StmtResolver {
  static void resolveStmt(@NotNull SeqLike<@NotNull Stmt> stmt, @NotNull ResolveInfo info) {
    stmt.forEach(s -> resolveStmt(s, info));
  }

  /** @apiNote Note that this function MUTATES the stmt if it's a Decl. */
  static void resolveStmt(@NotNull Stmt stmt, @NotNull ResolveInfo info) {
    switch (stmt) {
      case Command.Module mod -> resolveStmt(mod.contents(), info);
      case Decl.DataDecl decl -> {
        var local = resolveDeclSignature(decl, ExprResolver.LAX);
        addReferences(info, new TyckOrder.Head(decl), local._1);
        local._1.enterBody();
        decl.body.forEach(ctor -> {
          var bodyResolver = local._1.member(decl);
          bodyResolver.enterHead();
          var localCtxWithPat = new Ref<>(local._2);
          ctor.patterns = ctor.patterns.map(pattern -> subpatterns(localCtxWithPat, pattern));
          var ctorLocal = bodyResolver.resolveParams(ctor.telescope, localCtxWithPat.value);
          ctor.telescope = ctorLocal._1.toImmutableSeq();
          addReferences(info, new TyckOrder.Head(ctor), bodyResolver.reference().view()
            .appended(new TyckOrder.Head(decl)));

          bodyResolver.enterBody();
          ctor.clauses = ctor.clauses.map(clause -> matchy(clause, ctorLocal._2, bodyResolver));
          addReferences(info, new TyckOrder.Body(ctor), bodyResolver);
        });
        addReferences(info, new TyckOrder.Body(decl), local._1.reference().view()
          .concat(decl.body.map(TyckOrder.Body::new)));
      }
      case Decl.FnDecl decl -> {
        var local = resolveDeclSignature(decl, ExprResolver.LAX);
        addReferences(info, new TyckOrder.Head(decl), local._1);
        local._1.enterBody();
        var bodyResolver = local._1.body();
        bodyResolver.enterBody();
        decl.body = decl.body.map(
          expr -> expr.accept(bodyResolver, local._2),
          pats -> pats.map(clause -> matchy(clause, local._2, bodyResolver)));
        addReferences(info, new TyckOrder.Body(decl), local._1);
      }
      case Decl.StructDecl decl -> {
        var local = resolveDeclSignature(decl, ExprResolver.LAX);
        addReferences(info, new TyckOrder.Head(decl), local._1);
        local._1.enterBody();
        decl.fields.forEach(field -> {
          var bodyResolver = local._1.member(decl);
          bodyResolver.enterHead();
          var fieldLocal = bodyResolver.resolveParams(field.telescope, local._2);
          field.telescope = fieldLocal._1.toImmutableSeq();
          field.result = field.result.accept(bodyResolver, fieldLocal._2);
          addReferences(info, new TyckOrder.Head(field), bodyResolver.reference().view()
            .appended(new TyckOrder.Head(decl)));

          bodyResolver.enterBody();
          field.body = field.body.map(e -> e.accept(bodyResolver, fieldLocal._2));
          field.clauses = field.clauses.map(clause -> matchy(clause, fieldLocal._2, bodyResolver));
          addReferences(info, new TyckOrder.Body(field), bodyResolver);
        });
        addReferences(info, new TyckOrder.Body(decl), local._1.reference().view()
          .concat(decl.fields.map(TyckOrder.Body::new)));
      }
      case Decl.PrimDecl decl -> {
        var resolver = resolveDeclSignature(decl, ExprResolver.RESTRICTIVE)._1;
        addReferences(info, new TyckOrder.Head(decl), resolver);
        addReferences(info, new TyckOrder.Body(decl), SeqView.empty());
      }
      case Sample sample -> {
        var delegate = sample.delegate();
        var delegateInfo = new ResolveInfo(info.thisModule(), info.program(), info.opSet());
        resolveStmt(delegate, delegateInfo);
        // little hacky: transfer dependencies from `delegate` to `sample`
        var delegateHead = new TyckOrder.Head(delegate);
        var delegateBody = new TyckOrder.Body(delegate);
        var sampleHead = new TyckOrder.Head(sample);
        info.depGraph().sucMut(sampleHead).appendAll(delegateInfo.depGraph().suc(delegateHead));
        info.depGraph().sucMut(new TyckOrder.Body(sample)).appendAll(delegateInfo.depGraph().suc(delegateBody)
          .filterNot(order -> order.equals(delegateHead))
          .appended(sampleHead));
      }
      case Remark remark -> info.depGraph().sucMut(new TyckOrder.Body(remark)).appendAll(remark.doResolve(info));
      case Command cmd -> {}
      case Generalize.Variables variables -> {
        var resolver = new ExprResolver(ExprResolver.RESTRICTIVE);
        resolver.enterBody();
        variables.type = variables.type.accept(resolver, variables.ctx);
        addReferences(info, new TyckOrder.Body(variables), resolver);
      }
    }
  }

  private static void addReferences(@NotNull ResolveInfo info, TyckOrder decl, SeqView<TyckOrder> refs) {
    info.depGraph().sucMut(decl).appendAll(refs
      .filter(unit -> unit.unit().needTyck(info.thisModule().moduleName())));
    if (decl instanceof TyckOrder.Body) info.depGraph().sucMut(decl)
      .append(new TyckOrder.Head(decl.unit()));
  }

  private static void addReferences(@NotNull ResolveInfo info, TyckOrder decl, ExprResolver resolver) {
    addReferences(info, decl, resolver.reference().view());
  }

  private static @NotNull Tuple2<ExprResolver, Context>
  resolveDeclSignature(@NotNull Decl decl, ExprResolver.@NotNull Options options) {
    var resolver = new ExprResolver(options);
    resolver.enterHead();
    var local = resolver.resolveParams(decl.telescope, decl.ctx);
    decl.telescope = local._1
      .prependedAll(resolver.allowedGeneralizes().valuesView())
      .toImmutableSeq();
    decl.result = decl.result.accept(resolver, local._2);
    return Tuple.of(resolver, local._2);
  }

  static void visitBind(@NotNull DefVar<?, ?> selfDef, @NotNull BindBlock bind, @NotNull ResolveInfo info) {
    var opSet = info.opSet();
    var self = selfDef.opDecl;
    if (self == null && bind != BindBlock.EMPTY) {
      opSet.reporter.report(new OperatorProblem.NotOperator(selfDef.concrete.sourcePos(), selfDef.name()));
      throw new Context.ResolvingInterruptedException();
    }
    bind(bind, opSet, self);
  }

  private static void bind(@NotNull BindBlock bindBlock, AyaBinOpSet opSet, OpDecl self) {
    if (bindBlock == BindBlock.EMPTY) return;
    var ctx = bindBlock.context().value;
    assert ctx != null : "no shallow resolver?";
    bindBlock.resolvedLoosers().value = bindBlock.loosers().map(looser -> bind(self, opSet, ctx, OpDecl.BindPred.Looser, looser));
    bindBlock.resolvedTighters().value = bindBlock.tighters().map(tighter -> bind(self, opSet, ctx, OpDecl.BindPred.Tighter, tighter));
  }

  private static @NotNull DefVar<?, ?> bind(
    @NotNull OpDecl self, @NotNull AyaBinOpSet opSet, @NotNull Context ctx,
    @NotNull OpDecl.BindPred pred, @NotNull QualifiedID id
  ) throws Context.ResolvingInterruptedException {
    if (ctx.get(id) instanceof DefVar<?, ?> defVar) {
      var opDecl = defVar.opDecl;
      if (opDecl != null) {
        opSet.bind(self, pred, opDecl, id.sourcePos());
        return defVar;
      }
    }
    opSet.reporter.report(new UnknownOperatorError(id.sourcePos(), id.join()));
    throw new Context.ResolvingInterruptedException();
  }

  static void resolveBind(@NotNull SeqLike<@NotNull Stmt> contents, @NotNull ResolveInfo info) {
    contents.forEach(s -> resolveBind(s, info));
    info.bindBlockRename().forEach((opDecl, bindBlock) -> bind(bindBlock, info.opSet(), opDecl));
  }

  static void resolveBind(@NotNull Stmt stmt, @NotNull ResolveInfo info) {
    switch (stmt) {
      case Command.Module mod -> resolveBind(mod.contents(), info);
      case Decl.DataDecl decl -> {
        decl.body.forEach(ctor -> visitBind(ctor.ref, ctor.bindBlock, info));
        visitBind(decl.ref, decl.bindBlock, info);
      }
      case Decl.StructDecl decl -> {
        decl.fields.forEach(field -> visitBind(field.ref, field.bindBlock, info));
        visitBind(decl.ref, decl.bindBlock, info);
      }
      case Decl.FnDecl decl -> visitBind(decl.ref, decl.bindBlock, info);
      case Sample sample -> resolveBind(sample.delegate(), info);
      case Remark remark -> {}
      case Command cmd -> {}
      case Decl.PrimDecl decl -> {}
      case Generalize generalize -> {}
    }
  }

  static Pattern.Clause matchy(
    @NotNull Pattern.Clause match,
    @NotNull Context context,
    @NotNull ExprResolver bodyResolver
  ) {
    var ctx = new Ref<>(context);
    var pats = match.patterns.map(pat -> subpatterns(ctx, pat));
    return new Pattern.Clause(match.sourcePos, pats,
      match.expr.map(e -> e.accept(bodyResolver, ctx.value)));
  }

  static @NotNull Pattern subpatterns(Ref<Context> ctx, Pattern pat) {
    var res = resolve(pat, ctx.value);
    ctx.value = res._1;
    return res._2;
  }

  static Context bindAs(LocalVar as, Context ctx, SourcePos sourcePos) {
    return as != null ? ctx.bind(as, sourcePos) : ctx;
  }

  static Tuple2<Context, Pattern> resolve(@NotNull Pattern pattern, Context context) {
    return switch (pattern) {
      case Pattern.Tuple tuple -> {
        var newCtx = new Ref<>(context);
        var patterns = tuple.patterns().map(p -> subpatterns(newCtx, p));
        yield Tuple.of(
          bindAs(tuple.as(), newCtx.value, tuple.sourcePos()),
          new Pattern.Tuple(tuple.sourcePos(), tuple.explicit(), patterns, tuple.as()));
      }
      case Pattern.Bind bind -> {
        var maybe = findPatternDef(context, bind.sourcePos(), bind.bind().name());
        if (maybe != null) yield Tuple.of(context, new Pattern.Ctor(bind, maybe));
        else yield Tuple.of(context.bind(bind.bind(), bind.sourcePos(), var -> false), bind);
      }
      // We will never have Ctor instances before desugar.
      case Pattern.BinOpSeq seq -> {
        var newCtx = new Ref<>(context);
        var pats = seq.seq().map(p -> subpatterns(newCtx, p));
        yield Tuple.of(
          bindAs(seq.as(), newCtx.value, seq.sourcePos()),
          new Pattern.BinOpSeq(seq.sourcePos(), pats, seq.as(), seq.explicit()));
      }
      default -> Tuple.of(context, pattern);
    };
  }

  static @Nullable DefVar<?, ?> findPatternDef(Context context, SourcePos namePos, String name) {
    return context.iterate(c -> {
      var maybe = c.getUnqualifiedLocalMaybe(name, namePos);
      if (!(maybe instanceof DefVar<?, ?> defVar)) return null;
      if (defVar.core instanceof CtorDef || defVar.concrete instanceof Decl.DataCtor) return defVar;
      if (defVar.core instanceof PrimDef || defVar.concrete instanceof Decl.PrimDecl) return defVar;
      return null;
    });
  }
}