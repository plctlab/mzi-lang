// Copyright (c) 2020-2021 Yinsen (Tesla) Zhang.
// Use of this source code is governed by the GNU GPLv3 license that can be found in the LICENSE file.
package org.aya.tyck.error;

import org.aya.api.distill.DistillerOptions;
import org.aya.api.error.Problem;
import org.aya.api.error.SourcePos;
import org.aya.api.util.NormalizeMode;
import org.aya.core.term.CallTerm;
import org.aya.core.term.RefTerm;
import org.aya.pretty.doc.Doc;
import org.jetbrains.annotations.NotNull;

public record Goal(@NotNull CallTerm.Hole hole) implements Problem {
  @Override public @NotNull Doc describe() {
    var meta = hole.ref().core();
    var scope = hole.contextArgs().view().map(arg -> ((RefTerm) arg.term()).var()).toImmutableSeq();
    var doc = Doc.vcat(
      Doc.english("Goal of type"),
      Doc.par(1, meta.result.toDoc(DistillerOptions.DEFAULT)),
      Doc.par(1, Doc.parened(Doc.sep(Doc.plain("Normalized:"), meta.result.normalize(NormalizeMode.NF).toDoc(DistillerOptions.DEFAULT)))),
      Doc.plain("Context:"),
      Doc.vcat(meta.fullTelescope().map(param -> {
        var paramDoc = param.toDoc(DistillerOptions.DEFAULT);
        return Doc.par(1, scope.contains(param.ref()) ? paramDoc : Doc.sep(paramDoc, Doc.parened(Doc.english("not in scope"))));
      })),
      Doc.plain("To ensure confluence:"),
      Doc.vcat(hole.conditions().value.map(tup -> Doc.par(1, Doc.cat(
        Doc.plain("Given "),
        Doc.parened(tup._1.toDoc(DistillerOptions.DEFAULT)),
        Doc.plain(", we should have: "),
        tup._2.toDoc(DistillerOptions.DEFAULT)
      ))))
    );
    return meta.body == null ? doc :
      Doc.vcat(Doc.plain("Candidate exists:"), Doc.par(1, meta.body.toDoc(DistillerOptions.DEFAULT)), doc);
  }

  @Override public @NotNull SourcePos sourcePos() {
    return hole.ref().core().sourcePos;
  }

  @Override public @NotNull Severity level() {
    return Severity.GOAL;
  }
}
