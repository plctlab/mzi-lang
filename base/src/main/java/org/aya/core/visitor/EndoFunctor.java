// Copyright (c) 2020-2022 Yinsen (Tesla) Zhang.
// Use of this source code is governed by the MIT license that can be found in the LICENSE.md file.
package org.aya.core.visitor;

import kala.collection.SeqLike;
import kala.collection.immutable.ImmutableMap;
import kala.collection.immutable.ImmutableSeq;
import kala.collection.mutable.MutableList;
import kala.collection.mutable.MutableMap;
import kala.tuple.Tuple;
import org.aya.core.Matching;
import org.aya.core.pat.PatMatcher;
import org.aya.core.term.*;
import org.aya.generic.Arg;
import org.aya.generic.Modifier;
import org.aya.generic.util.InternalException;
import org.aya.ref.LocalVar;
import org.aya.ref.Var;
import org.aya.tyck.TyckState;
import org.aya.util.distill.DistillerOptions;
import org.aya.util.error.WithPos;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.Function;

/**
 * A convenient interface to obtain an endofunction on `Term`.
 * Either the operation of folding to a term, or that of unfolding from a term can be regarded as an endofunction.
 * Composing the above two operations also gives an endofunction,
 * and this is in fact what this interface provides by implementing both `Folder<Term>` and `Unfolder<Term>`.
 * In this spirit, the user can provide `fold : Tm<Term> -> Term` and `unfold : Term -> Tm<Term>`.
 * But since `Tm<Term>` and `Term` are isomorphic,
 * we instead ask for `pre : Term -> Term` and `post : Term -> Term`,
 * as this would allow a specialized implementation eliminating unnecessary casting,
 * and dealing with purely `Term`s can be more convenient.
 * <p>
 * The `act : Term -> Term` method should be called for the final composed action on `Term`.
 * Although it is essentially the composition of the derived `unfolded` and `folded` functions,
 * `act` has better performance by eliminating casting completely,
 * and attempts to preserve object identity when possible.
 * The implementation of `pre` and `post` can also take advantage of this behavior.
 *
 * @author wsx
 */
public interface EndoFunctor extends Folder<Term>, Unfolder<Term> {
  default Term pre(Term term) {
    return term;
  }

  default Term post(Term term) {
    return term;
  }

  default Term fold(Tm<Term> tm) {
    return post(Tm.cast(tm));
  }

  default Tm<Term> unfold(Term term) {
    return Tm.cast(pre(term));
  }

  // fold(unfold(term).map(this::act))
  default Term act(Term term) {
    return post(descent(this::act, pre(term)));
  }

  private Term descent(Function<Term, Term> f, Term term) {
    return switch (term) {
      case FormTerm.Pi pi -> {
        var param = descent(f, pi.param());
        var body = f.apply(pi.body());
        if (param == pi.param() && body == pi.body()) yield pi;
        yield new FormTerm.Pi(param, body);
      }
      case FormTerm.Sigma sigma -> {
        var params = sigma.params().map(param -> descent(f, param));
        if (params.sameElements(sigma.params(), true)) yield sigma;
        yield new FormTerm.Sigma(params);
      }
      case FormTerm.Univ univ -> univ;
      case FormTerm.Interval interval -> interval;
      case PrimTerm.End end -> end;
      case PrimTerm.Str str -> str;
      case IntroTerm.Lambda lambda -> {
        var param = descent(f, lambda.param());
        var body = f.apply(lambda.body());
        if (param == lambda.param() && body == lambda.body()) yield lambda;
        yield new IntroTerm.Lambda(param, body);
      }
      case IntroTerm.Tuple tuple -> {
        var items = tuple.items().map(f);
        if (items.sameElements(tuple.items(), true)) yield tuple;
        yield new IntroTerm.Tuple(items);
      }
      case IntroTerm.New neu -> {
        var struct = f.apply(neu.struct());
        var fields = ImmutableMap.from(neu.params().view().map((k, v) -> Tuple.of(k, f.apply(v))));
        if (struct == neu.struct() && fields.valuesView().sameElements(neu.params().valuesView())) yield neu;
        yield new IntroTerm.New((CallTerm.Struct) struct, fields);
      }
      case ElimTerm.App app -> {
        var function = f.apply(app.of());
        var arg = descent(f, app.arg());
        if (function == app.of() && arg == app.arg()) yield app;
        yield CallTerm.make(function, arg);
      }
      case ElimTerm.Proj proj -> {
        var tuple = f.apply(proj.of());
        if (tuple == proj.of()) yield proj;
        yield new ElimTerm.Proj(tuple, proj.ix());
      }
      case CallTerm.Struct struct -> {
        var args = struct.args().map(arg -> descent(f, arg));
        if (args.sameElements(struct.args(), true)) yield struct;
        yield new CallTerm.Struct(struct.ref(), struct.ulift(), args);
      }
      case CallTerm.Data data -> {
        var args = data.args().map(arg -> descent(f, arg));
        if (args.sameElements(data.args(), true)) yield data;
        yield new CallTerm.Data(data.ref(), data.ulift(), args);
      }
      case CallTerm.Con con -> {
        var head = descent(f, con.head());
        var args = con.conArgs().map(arg -> descent(f, arg));
        if (head == con.head() && args.sameElements(con.conArgs(), true)) yield con;
        yield new CallTerm.Con(head, args);
      }
      case CallTerm.Fn fn -> {
        var args = fn.args().map(arg -> descent(f, arg));
        if (args.sameElements(fn.args(), true)) yield fn;
        yield new CallTerm.Fn(fn.ref(), fn.ulift(), args);
      }
      case CallTerm.Access access -> {
        var struct = f.apply(access.of());
        var structArgs = access.structArgs().map(arg -> descent(f, arg));
        var fieldArgs = access.fieldArgs().map(arg -> descent(f, arg));
        if (struct == access.of()
          && structArgs.sameElements(access.structArgs(), true)
          && fieldArgs.sameElements(access.fieldArgs(), true))
          yield access;
        yield new CallTerm.Access(struct, access.ref(), structArgs, fieldArgs);
      }
      case CallTerm.Prim prim -> {
        var args = prim.args().map(arg -> descent(f, arg));
        if (args.sameElements(prim.args(), true)) yield prim;
        yield new CallTerm.Prim(prim.ref(), prim.ulift(), args);
      }
      case CallTerm.Hole hole -> {
        var contextArgs = hole.contextArgs().map(arg -> descent(f, arg));
        var args = hole.args().map(arg -> descent(f, arg));
        if (contextArgs.sameElements(hole.contextArgs(), true) && args.sameElements(hole.args(), true)) yield hole;
        yield new CallTerm.Hole(hole.ref(), hole.ulift(), contextArgs, args);
      }
      case LitTerm.ShapedInt shaped -> {
        var type = f.apply(shaped.type());
        if (type == shaped.type()) yield shaped;
        yield new LitTerm.ShapedInt(shaped.repr(), shaped.shape(), type);
      }
      case RefTerm ref -> ref;
      case RefTerm.MetaPat metaPat -> metaPat;
      case RefTerm.Field field -> field;
      case ErrorTerm error -> error;
    };
  }
  private Term.Param descent(Function<Term, Term> f, Term.Param param) {
    var type = f.apply(param.type());
    if (type == param.type()) return param;
    return new Term.Param(param, type);
  }
  private Arg<Term> descent(Function<Term, Term> f, Arg<Term> arg) {
    var term = f.apply(arg.term());
    if (term == arg.term()) return arg;
    return new Arg<>(term, arg.explicit());
  }
  private CallTerm.ConHead descent(Function<Term, Term> f, CallTerm.ConHead head) {
    var args = head.dataArgs().map(arg -> descent(f, arg));
    if (args.sameElements(head.dataArgs(), true)) return head;
    return new CallTerm.ConHead(head.dataRef(), head.ref(), head.ulift(), args);
  }

  /** Not an IntelliJ Renamer. */
  record Renamer(Subst subst) implements EndoFunctor {
    public Renamer() {
      this(new Subst(MutableMap.create()));
    }

    private @NotNull Term.Param handleBinder(@NotNull Term.Param param) {
      var v = param.renameVar();
      subst.addDirectly(param.ref(), new RefTerm(v, 0));
      return new Term.Param(v, param.type(), param.pattern(), param.explicit());
    }

    @Override public Term pre(Term term) {
      return switch (term) {
        case IntroTerm.Lambda lambda -> new IntroTerm.Lambda(handleBinder(lambda.param()), lambda.body());
        case FormTerm.Pi pi -> new FormTerm.Pi(handleBinder(pi.param()), pi.body());
        case FormTerm.Sigma sigma -> new FormTerm.Sigma(sigma.params().map(this::handleBinder));
        case RefTerm ref -> subst.map().getOrDefault(ref.var(), ref);
        case RefTerm.Field field -> subst.map().getOrDefault(field.ref(), field);
        case Term misc -> misc;
      };
    }
  }

  /**
   * Performes capture-avoiding substitution.
   */
  record Substituter(Subst subst) implements EndoFunctor {
    @Override public Term post(Term term) {
      return switch (term) {
        case RefTerm ref && ref.var() == LocalVar.IGNORED -> throw new InternalException("found usage of ignored var");
        case RefTerm ref -> subst.map().getOption(ref.var()).map(Term::rename).getOrDefault(ref);
        case RefTerm.Field field -> subst.map().getOption(field.ref()).map(Term::rename).getOrDefault(field);
        case Term misc -> misc;
      };
    }
  }

  /** A lift but in American English. */
  record Elevator(int lift, MutableList<Var> boundVars) implements EndoFunctor {
    public Elevator(int lift) {
      this(lift, MutableList.create());
    }

    @Override public Term pre(Term term) {
      switch (term) {
        case FormTerm.Pi pi -> boundVars.append(pi.param().ref());
        case FormTerm.Sigma sigma -> boundVars.appendAll(sigma.params().map(Term.Param::ref));
        case IntroTerm.Lambda lambda -> boundVars.append(lambda.param().ref());
        default -> {}
      }
      return term;
    }

    @Override public Term post(Term term) {
      return switch (term) {
        case FormTerm.Univ univ -> new FormTerm.Univ(univ.lift() + lift);
        case CallTerm.Struct struct -> new CallTerm.Struct(struct.ref(), struct.ulift() + lift, struct.args());
        case CallTerm.Data data -> new CallTerm.Data(data.ref(), data.ulift() + lift, data.args());
        case CallTerm.Con con -> {
          var head = con.head();
          head = new CallTerm.ConHead(head.dataRef(), head.ref(), head.ulift() + lift, head.dataArgs());
          yield new CallTerm.Con(head, con.conArgs());
        }
        case CallTerm.Fn fn -> new CallTerm.Fn(fn.ref(), fn.ulift() + lift, fn.args());
        case CallTerm.Prim prim -> new CallTerm.Prim(prim.ref(), prim.ulift() + lift, prim.args());
        case CallTerm.Hole hole -> new CallTerm.Hole(hole.ref(), hole.ulift() + lift, hole.contextArgs(), hole.args());
        case RefTerm ref -> boundVars.contains(ref.var())
          ? ref : new RefTerm(ref.var(), ref.lift() + lift);
        case RefTerm.Field field -> boundVars.contains(field.ref())
          ? field : new RefTerm.Field(field.ref(), field.lift() + lift);
        case Term misc -> misc;
      };
    }
  }

  record Normalizer(TyckState state) implements EndoFunctor {
    @Override public Term post(Term term) {
      return switch (term) {
        case ElimTerm.App app && app.of() instanceof IntroTerm.Lambda lambda -> act(CallTerm.make(lambda, app.arg()));
        case ElimTerm.Proj proj && proj.of() instanceof IntroTerm.Tuple tuple -> {
          var ix = proj.ix();
          assert tuple.items().sizeGreaterThanOrEquals(ix) && ix > 0
            : proj.toDoc(DistillerOptions.debug()).debugRender();
          yield tuple.items().get(ix - 1);
        }
        case CallTerm.Con con -> {
          var def = con.ref().core;
          if (def == null) yield con;
          var unfolded = unfoldClauses(true, con.conArgs(), def.clauses);
          yield unfolded != null ? act(unfolded.data()) : con;
        }
        case CallTerm.Fn fn -> {
          var def = fn.ref().core;
          if (def == null) yield fn;
          if (def.modifiers.contains(Modifier.Opaque)) yield fn;
          yield def.body.fold(
            lamBody -> act(lamBody.subst(buildSubst(def.telescope(), fn.args()))),
            patBody -> {
              var unfolded = unfoldClauses(def.modifiers.contains(Modifier.Overlap), fn.args(), patBody);
              return unfolded != null ? act(unfolded.data()) : fn;
            }
          );
        }
        case CallTerm.Access access -> {
          var fieldDef = access.ref().core;
          if (access.of() instanceof IntroTerm.New n) {
            var fieldBody = access.fieldArgs().foldLeft(n.params().get(access.ref()), CallTerm::make);
            yield act(fieldBody.subst(buildSubst(fieldDef.ownerTele, access.structArgs())));
          } else {
            var subst = buildSubst(fieldDef.fullTelescope(), access.args());
            for (var field : fieldDef.structRef.core.fields) {
              if (field == fieldDef) continue;
              var fieldArgs = field.telescope().map(Term.Param::toArg);
              var acc = new CallTerm.Access(access.of(), field.ref, access.structArgs(), fieldArgs);
              subst.add(field.ref, IntroTerm.Lambda.make(field.telescope(), acc));
            }
            var unfolded = unfoldClauses(true, access.fieldArgs(), subst, fieldDef.clauses);
            yield unfolded != null ? act(unfolded.data()) : access;
          }
        }
        case CallTerm.Prim prim -> state.primFactory().unfold(prim.id(), prim, state);
        case CallTerm.Hole hole -> {
          var def = hole.ref();
          if (!state.metas().containsKey(def)) yield hole;
          var body = state.metas().get(def);
          yield act(body.subst(buildSubst(def.fullTelescope(), hole.fullArgs())));
        }
        case RefTerm.MetaPat metaPat -> metaPat.inline();
        case Term t -> t;
      };
    }

    static private Subst buildSubst(SeqLike<Term.Param> params, SeqLike<Arg<Term>> args) {
      var subst = new Subst(MutableMap.create());
      params.view().zip(args).forEach(t -> subst.add(t._1.ref(), t._2.term()));
      return subst;
    }

    private @Nullable WithPos<Term> unfoldClauses(
      boolean orderIndependent, SeqLike<Arg<Term>> args,
      ImmutableSeq<Matching> clauses
    ) {
      return unfoldClauses(orderIndependent, args, new Subst(MutableMap.create()), clauses);
    }

    private @Nullable WithPos<Term> unfoldClauses(
      boolean orderIndependent, SeqLike<Arg<Term>> args,
      Subst subst, ImmutableSeq<Matching> clauses
    ) {
      for (var match : clauses) {
        var result = PatMatcher.tryBuildSubstArgs(null, match.patterns(), args);
        if (result.isOk()) {
          subst.add(result.get());
          var body = match.body().subst(subst);
          return new WithPos<>(match.sourcePos(), body);
        } else if (!orderIndependent && result.getErr())
          return null;
      }
      return null;
    }
  }
}
