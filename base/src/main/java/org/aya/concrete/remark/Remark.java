// Copyright (c) 2020-2021 Yinsen (Tesla) Zhang.
// Use of this source code is governed by the GNU GPLv3 license that can be found in the LICENSE file.
package org.aya.concrete.remark;

import kala.collection.immutable.ImmutableSeq;
import kala.collection.mutable.Buffer;
import kala.value.Ref;
import org.aya.api.error.SourcePos;
import org.aya.api.util.NormalizeMode;
import org.aya.concrete.desugar.BinOpSet;
import org.aya.concrete.parse.AyaParsing;
import org.aya.concrete.parse.AyaProducer;
import org.aya.concrete.resolve.context.Context;
import org.aya.concrete.stmt.Stmt;
import org.aya.pretty.doc.Doc;
import org.aya.pretty.doc.Style;
import org.commonmark.node.*;
import org.commonmark.parser.Parser;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * @author ice1000
 */
public final class Remark implements Stmt {
  public final @Nullable Literate literate;
  public final @NotNull String raw;
  public final @NotNull SourcePos sourcePos;
  public @Nullable Context ctx = null;

  private Remark(@Nullable Literate literate, @NotNull String raw, @NotNull SourcePos sourcePos) {
    this.literate = literate;
    this.raw = raw;
    this.sourcePos = sourcePos;
  }

  public static @NotNull Remark make(@NotNull String raw, @NotNull SourcePos pos, @NotNull AyaProducer producer) {
    var parser = Parser.builder().build();
    var ast = parser.parse(raw);
    return new Remark(mapAST(ast, pos, producer), raw, pos);
  }

  private static @NotNull ImmutableSeq<Literate> mapChildren(
    @NotNull Node parent, @NotNull SourcePos pos,
    @NotNull AyaProducer producer
  ) {
    Node next;
    var children = Buffer.<Literate>create();
    for (Node node = parent.getFirstChild(); node != null; node = next) {
      next = node.getNext();
      children.append(mapAST(node, pos, producer));
    }
    return children.toImmutableSeq();
  }

  private static @Nullable Literate mapAST(
    @NotNull Node node, @NotNull SourcePos pos,
    @NotNull AyaProducer producer
  ) {
    if (node instanceof Code code) {
      var text = code.getLiteral();
      boolean isType;
      if (text.startsWith("ty:") || text.startsWith("TY:")) {
        isType = true;
        text = text.substring(3);
      } else isType = false;
      NormalizeMode mode = null;
      for (var value : NormalizeMode.values()) {
        var prefix = value + ":";
        if (text.startsWith(prefix)) {
          mode = value;
          text = text.substring(prefix.length());
          break;
        }
      }
      var expr = producer.visitExpr(AyaParsing.parser(text).expr());
      return new Literate.Code(new Ref<>(expr), new Literate.CodeCmd(isType, mode));
    } else if (node instanceof Text text) {
      return new Literate.Raw(Doc.plain(text.getLiteral()));
    } else if (node instanceof Emphasis emphasis) {
      return new Literate.Styled(Style.italic(), mapChildren(emphasis, pos, producer));
    } else if (node instanceof HardLineBreak) {
      return new Literate.Raw(Doc.line());
    } else if (node instanceof StrongEmphasis emphasis) {
      return new Literate.Styled(Style.bold(), mapChildren(emphasis, pos, producer));
    } else if (node instanceof Paragraph) {
      return new Literate.Par(mapChildren(node, pos, producer));
    } else if (node instanceof Document) {
      var children = mapChildren(node, pos, producer);
      if (children.sizeEquals(1)) return children.first();
      else return new Literate.Par(children);
    } else {
      producer.reporter().report(new UnsupportedMarkdown(pos, node.getClass().getSimpleName()));
      return null;
    }
  }

  @Override public @NotNull Accessibility accessibility() {
    return Accessibility.Private;
  }

  @Override public <P, R> R doAccept(@NotNull Visitor<P, R> visitor, P p) {
    return visitor.visitRemark(this, p);
  }

  public @NotNull SourcePos sourcePos() {
    return sourcePos;
  }

  public void doResolve(@NotNull BinOpSet binOpSet) {
    if (literate == null) return;
    assert ctx != null : "Be sure to call the shallow resolver before resolving";
    literate.resolve(binOpSet, ctx);
  }
}
