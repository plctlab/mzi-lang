// Copyright (c) 2020-2024 Tesla (Yinsen) Zhang.
// Use of this source code is governed by the MIT license that can be found in the LICENSE.md file.
package org.aya.compiler;

import kala.collection.Seq;
import kala.collection.immutable.ImmutableSeq;
import kala.collection.immutable.ImmutableTreeSeq;
import kala.collection.mutable.MutableSeq;
import kala.control.Result;
import org.aya.compiler.util.SerializeUtils;
import org.aya.generic.Modifier;
import org.aya.syntax.core.pat.Pat;
import org.aya.syntax.core.term.Term;
import org.aya.syntax.core.term.TupTerm;
import org.aya.syntax.core.term.call.*;
import org.aya.util.error.Panic;
import org.intellij.lang.annotations.Language;

import static org.aya.compiler.ExprializeUtils.getJavaReference;

/**
 * <h1>Serializing</h1>
 * AyaSerializer will serialize some {@link T} to some (java) class or expression, depends on what {@link T} is.
 *
 * <h2>File Structure</h2>
 * Each aya module will be serialized to a java file,
 * each {@link org.aya.syntax.concrete.stmt.decl.Decl} will be serialized to a nested class.
 */
public interface AyaSerializer {
  String PACKAGE_BASE = "AYA";
  String STATIC_FIELD_INSTANCE = "INSTANCE";
  String FIELD_INSTANCE = "ref";
  String CLASS_CONCALL = getJavaReference(ConCall.class);
  String CLASS_CONCALLLIKE = getJavaReference(ConCallLike.class);
  String CLASS_TUPLE = getJavaReference(TupTerm.class);
  String CLASS_FNCALL = getJavaReference(FnCall.class);
  String CLASS_DATACALL = getJavaReference(DataCall.class);
  String CLASS_PRIMCALL = getJavaReference(PrimCall.class);
  String CLASS_IMMSEQ = getJavaReference(ImmutableSeq.class);
  String CLASS_PIMMSEQ = getJavaReference(ImmutableTreeSeq.class);
  String CLASS_MUTSEQ = getJavaReference(MutableSeq.class);
  String CLASS_SEQ = getJavaReference(Seq.class);
  String CLASS_TERM = getJavaReference(Term.class);
  String CLASS_PAT = getJavaReference(Pat.class);
  String CLASS_PANIC = getJavaReference(Panic.class);
  String CLASS_MODIFIER = getJavaReference(Modifier.class);

  String CLASS_SER_UTILS = getJavaReference(SerializeUtils.class);
  String CLASS_RESULT = getJavaReference(Result.class);
  String CLASS_BOOLEAN = getJavaReference(Boolean.class);
  String TYPE_IMMTERMSEQ = STR."\{CLASS_IMMSEQ}<\{CLASS_TERM}>";

  @Language("Java") String IMPORT_BLOCK = """
    import org.aya.generic.term.SortKind;
    import org.aya.generic.State;
    import org.aya.generic.Modifier;
    import org.aya.compiler.util.*;
    import org.aya.syntax.compile.*;
    import org.aya.syntax.compile.CompiledAya;
    import org.aya.syntax.ref.LocalVar;
    import org.aya.syntax.core.*;
    import org.aya.syntax.core.Closure.Jit;
    import org.aya.syntax.core.pat.Pat;
    import org.aya.syntax.core.repr.*;
    import org.aya.syntax.core.term.*;
    import org.aya.syntax.core.term.repr.*;
    import org.aya.syntax.core.term.call.*;
    import org.aya.syntax.core.term.xtt.*;
    import org.aya.normalize.PatMatcher;
    import org.aya.util.error.Panic;
    import org.aya.util.binop.Assoc;

    import kala.collection.immutable.ImmutableSeq;
    import kala.collection.immutable.ImmutableTreeSeq;
    import kala.collection.mutable.MutableSeq;
    import kala.collection.Seq;
    import kala.control.Result;
    """;
}
