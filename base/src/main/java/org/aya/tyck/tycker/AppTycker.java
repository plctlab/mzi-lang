// Copyright (c) 2020-2024 Tesla (Yinsen) Zhang.
// Use of this source code is governed by the MIT license that can be found in the LICENSE.md file.
package org.aya.tyck.tycker;

import kala.collection.Seq;
import kala.collection.SeqView;
import kala.collection.immutable.ImmutableArray;
import kala.function.CheckedBiFunction;
import org.aya.generic.stmt.Shaped;
import org.aya.syntax.compile.JitCon;
import org.aya.syntax.compile.JitData;
import org.aya.syntax.compile.JitFn;
import org.aya.syntax.compile.JitPrim;
import org.aya.syntax.concrete.stmt.decl.*;
import org.aya.syntax.core.def.*;
import org.aya.syntax.core.repr.AyaShape;
import org.aya.syntax.core.term.FreeTerm;
import org.aya.syntax.core.term.Term;
import org.aya.syntax.core.term.call.*;
import org.aya.syntax.ref.DefVar;
import org.aya.syntax.ref.LocalVar;
import org.aya.syntax.telescope.AbstractTele;
import org.aya.tyck.Jdg;
import org.aya.tyck.TyckState;
import org.aya.util.error.Panic;
import org.aya.util.error.SourcePos;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.Function;

public record AppTycker<Ex extends Exception>(
  @NotNull TyckState state, @NotNull SourcePos pos,
  int argsCount, int lift, @NotNull Factory<Ex> makeArgs
) {
  /**
   * <pre>
   * Signature (0th param) --------> Argument Parser (this interface)
   *                                        |
   *                                  [ arguments ]
   *                                        |
   *                                        v
   * Well-typed Call (result) <---- Factory (1st param)
   * </pre>
   */
  @FunctionalInterface
  public interface Factory<Ex extends Exception> extends
    CheckedBiFunction<AbstractTele, Function<Term[], Jdg>, Jdg, Ex> {
  }

  public @NotNull Jdg checkCompiledApplication(@NotNull AbstractTele def) throws Ex {
    return switch (def) {
      case JitFn fn -> {
        int shape = fn.metadata().shape();
        var operator = shape != -1 ? AyaShape.ofFn(fn, AyaShape.values()[shape]) : null;
        yield checkFnCall(fn, operator);
      }
      case JitData data -> checkDataCall(data);
      case JitPrim prim -> checkPrimCall(prim);
      case JitCon con -> checkConCall(con);
      default -> throw new Panic(def.getClass().getCanonicalName());
    };
  }

  @SuppressWarnings("unchecked")
  public @NotNull Jdg checkDefApplication(
    @NotNull DefVar<?, ?> defVar
  ) throws Ex {
    return switch (defVar.concrete) {
      case FnDecl _ -> {
        var fnDef = new FnDef.Delegate((DefVar<FnDef, FnDecl>) defVar);
        var op = state.shapeFactory.find(fnDef).map(recog -> AyaShape.ofFn(fnDef, recog.shape())).getOrNull();
        yield checkFnCall(fnDef, op);
      }
      // Extracted to prevent pervasive influence of suppression of unchecked warning.
      case DataDecl _ -> checkDataCall(
        new DataDef.Delegate((DefVar<DataDef, DataDecl>) defVar));
      case PrimDecl _ -> checkPrimCall(
        new PrimDef.Delegate((DefVar<PrimDef, PrimDecl>) defVar));
      case DataCon _ -> checkConCall(
        new ConDef.Delegate((DefVar<ConDef, DataCon>) defVar));
      case ClassDecl _ -> checkClassCall(
        new ClassDef.Delegate((DefVar<ClassDef, ClassDecl>) defVar));
      case ClassMember _ -> checkProjCall(
        new MemberDef.Delegate((DefVar<MemberDef, ClassMember>) defVar));
      case Decl any -> throw new Panic(any.getClass().getCanonicalName());
    };
  }

  private @NotNull Jdg checkConCall(@NotNull ConDefLike conVar) throws Ex {
    var dataVar = conVar.dataRef();

    // ownerTele + selfTele
    var fullSignature = conVar.signature().lift(lift);

    return makeArgs.applyChecked(fullSignature, args -> {
      var realArgs = ImmutableArray.from(args);
      var ownerArgs = realArgs.take(conVar.ownerTeleSize());
      var conArgs = realArgs.drop(conVar.ownerTeleSize());

      var type = (DataCall) fullSignature.result(realArgs);
      var shape = state.shapeFactory.find(dataVar)
        .mapNotNull(recog -> AyaShape.ofCon(conVar, recog, type))
        .getOrNull();
      if (shape != null) return new Jdg.Default(new RuleReducer.Con(shape, 0, ownerArgs, conArgs), type);
      var wellTyped = new ConCall(conVar, ownerArgs, 0, conArgs);
      return new Jdg.Default(wellTyped, type);
    });
  }
  private @NotNull Jdg checkPrimCall(@NotNull PrimDefLike primVar) throws Ex {
    var signature = primVar.signature().lift(lift);
    return makeArgs.applyChecked(signature, args -> new Jdg.Default(
      state.primFactory.unfold(new PrimCall(primVar, 0, ImmutableArray.from(args)), state),
      signature.result(args)
    ));
  }
  private @NotNull Jdg checkDataCall(@NotNull DataDefLike data) throws Ex {
    var signature = data.signature().lift(lift);
    return makeArgs.applyChecked(signature, args -> new Jdg.Default(
      new DataCall(data, 0, ImmutableArray.from(args)),
      signature.result(args)
    ));
  }
  private @NotNull Jdg checkFnCall(
    @NotNull FnDefLike fnDef, @Nullable Shaped.Applicable<FnDefLike> operator
  ) throws Ex {
    var signature = fnDef.signature().lift(lift);
    return makeArgs.applyChecked(signature, args -> {
      var argsSeq = ImmutableArray.from(args);
      var result = signature.result(args);
      if (operator != null) {
        return new Jdg.Default(new RuleReducer.Fn(operator, 0, argsSeq), result);
      }
      var fnCall = new FnCall(fnDef, 0, argsSeq);
      return new Jdg.Default(fnCall, result);
    });
  }

  private @NotNull Jdg checkClassCall(@NotNull ClassDefLike clazz) throws Ex {
    var appliedParams = ofClassMembers(clazz, argsCount).lift(lift);
    var self = LocalVar.generate("self");
    state.classThis.push(self);
    var result = makeArgs.applyChecked(appliedParams, args -> new Jdg.Default(
      new ClassCall(clazz, 0, ImmutableArray.from(args).map(x -> x.bind(self))),
      appliedParams.result(args)
    ));
    state.classThis.pop();
    return result;
  }

  private @NotNull Jdg checkProjCall(@NotNull MemberDefLike member) throws Ex {
    var signature = member.signature().lift(lift);
    return makeArgs.applyChecked(signature, args -> {
      assert args.length >= 1;
      var fieldArgs = ImmutableArray.fill(args.length - 1, i -> args[i + 1]);
      return new Jdg.Default(
        new MemberCall(args[0], member, 0, fieldArgs),
        signature.result(args)
      );
    });
  }

  static @NotNull AbstractTele ofClassMembers(@NotNull ClassDefLike def, int memberCount) {
    return switch (def) {
      case ClassDef.Delegate delegate -> new TakeMembers(delegate.core(), memberCount);
    };
  }

  record TakeMembers(@NotNull ClassDef clazz, @Override int telescopeSize) implements AbstractTele {
    @Override public boolean telescopeLicit(int i) { return true; }
    @Override public @NotNull String telescopeName(int i) {
      assert i < telescopeSize;
      return clazz.members().get(i).ref().name();
    }

    // class Foo
    // | foo : A
    // | + : A -> A -> A
    // | bar : Fn (x : Foo A) -> (x.foo) self.+ (self.foo)
    //                  instantiate these!   ^       ^
    @Override public @NotNull Term telescope(int i, Seq<Term> teleArgs) {
      // teleArgs are former members
      assert i < telescopeSize;
      var member = clazz.members().get(i);
      return TyckDef.defSignature(member.ref()).makePi(Seq.of(new FreeTerm(clazz.ref().concrete.self)));
    }
    @Override public @NotNull Term result(Seq<Term> teleArgs) {
      // Use SigmaTerm::lub
      throw new UnsupportedOperationException("TODO");
    }
    @Override public @NotNull SeqView<String> namesView() {
      return clazz.members().sliceView(0, telescopeSize).map(i -> i.ref().name());
    }
  }
}
