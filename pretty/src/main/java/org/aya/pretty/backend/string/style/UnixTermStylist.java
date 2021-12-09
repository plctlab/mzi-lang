// Copyright (c) 2020-2021 Yinsen (Tesla) Zhang.
// Use of this source code is governed by the MIT license that can be found in the LICENSE.md file.
package org.aya.pretty.backend.string.style;

import org.aya.pretty.backend.string.custom.UnixTermStyle;
import org.aya.pretty.doc.Style;
import org.jetbrains.annotations.NotNull;

public class UnixTermStylist extends ClosingStylist {
  public static final @NotNull UnixTermStylist INSTANCE = new UnixTermStylist();

  @Override protected @NotNull StyleToken formatItalic() {
    return new StyleToken("\033[3m", "\033[23m", false);
  }

  @Override protected @NotNull StyleToken formatCode() {
    return new StyleToken("`", "'", true);
  }

  @Override protected @NotNull StyleToken formatBold() {
    return new StyleToken("\033[1m", "\033[22m", false);
  }

  @Override protected @NotNull StyleToken formatStrike() {
    return new StyleToken("\033[9m", "\033[29m", false);
  }

  @Override protected @NotNull StyleToken formatUnderline() {
    return new StyleToken("\033[4m", "\033[24m", false);
  }

  @Override protected @NotNull StyleToken formatCustom(Style.@NotNull CustomStyle style) {
    if (style instanceof UnixTermStyle termStyle) {
      return switch (termStyle) {
        case Dim -> new StyleToken("\033[2m", "\033[22m", false);
        case DoubleUnderline -> new StyleToken("\033[21m", "\033[24m", false);
        case CurlyUnderline -> new StyleToken("\033[4:3m", "\033[4:0m", false);
        case Overline -> new StyleToken("\033[53m", "\033[55m", false);
        case Blink -> new StyleToken("\033[5m", "\033[25m", false);
        case Reverse -> new StyleToken("\033[7m", "\033[27m", false);
        case TerminalRed -> new StyleToken("\033[31m", "\033[39m", false);
        case TerminalGreen -> new StyleToken("\033[32m", "\033[39m", false);
        case TerminalBlue -> new StyleToken("\033[34m", "\033[39m", false);
        case TerminalYellow -> new StyleToken("\033[33m", "\033[39m", false);
        case TerminalPurple -> new StyleToken("\033[35m", "\033[39m", false);
        case TerminalCyan -> new StyleToken("\033[36m", "\033[39m", false);
      };
    }
    return StyleToken.NULL;
  }

  @Override protected @NotNull StyleToken formatColorHex(int rgb, boolean bg) {
    int r = (rgb & 0xFF0000) >> 16;
    int g = (rgb & 0xFF00) >> 8;
    int b = (rgb & 0xFF);

    return new StyleToken(
      String.format("\033[%d;2;%d;%d;%dm", bg ? 48 : 38, r, g, b),
      String.format("\033[%dm", bg ? 49 : 39),
      false
    );
  }
}
