// Copyright (c) 2020-2021 Yinsen (Tesla) Zhang.
// Use of this source code is governed by the MIT license that can be found in the LICENSE.md file.
package org.aya.cli.repl;

import org.aya.cli.repl.jline.KwCompleter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;

import java.io.IOException;

public final class JlineRepl extends AbstractRepl {
  private final @NotNull Terminal terminal;
  private final @NotNull LineReader lineReader;

  public JlineRepl() throws IOException {
    terminal = TerminalBuilder.builder()
      .jansi(true)
      .jna(false)
      .build();
    lineReader = LineReaderBuilder.builder()
      .appName(APP_NAME)
      .terminal(terminal)
      // .parser(new AyaParser())
      .completer(KwCompleter.INSTANCE)
      .build();
  }

  @Override String readLine(@NotNull String prompt) {
    return lineReader.readLine(prompt);
  }

  @Override void println(@NotNull String x) {
    terminal.writer().println(x);
    terminal.flush();
  }

  // see `eprintln` in https://github.com/JetBrains/Arend/blob/master/cli/src/main/java/org/arend/frontend/repl/jline/JLineCliRepl.java
  @Override void errPrintln(@NotNull String x) {
    println(new AttributedStringBuilder()
      .style(AttributedStyle.DEFAULT.foreground(AttributedStyle.RED))
      .append(x)
      .style(AttributedStyle.DEFAULT)
      .toAnsi());
  }

  @Override @Nullable String getAdditionalMessage() {
    return null;
  }

  @Override public void close() throws IOException {
    terminal.close();
  }
}