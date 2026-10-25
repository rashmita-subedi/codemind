package com.codemind.cli;

import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.UserInterruptException;

import java.io.Console;
import java.util.Scanner;

/**
 * Single-source-of-truth for reading interactive responses (y/N, free-text confirms, etc.)
 * from the user.
 *
 * <p>Inside the JLine REPL, raw {@code Scanner.nextLine()} collides with JLine's stdin
 * handling and the call hangs. {@link CliRunner} registers its {@link LineReader} here at
 * startup; consumers like {@link com.codemind.service.ApprovalService} call
 * {@link #prompt(String)} which delegates to JLine when present and falls back to a
 * lazy {@link Scanner} (or {@link Console}) for the picocli one-shot path.</p>
 */
public final class InteractivePrompter {

    private static volatile LineReader reader;
    private static volatile Scanner fallback;

    private InteractivePrompter() {
    }

    public static void register(LineReader lineReader) {
        reader = lineReader;
    }

    public static void clear() {
        reader = null;
        synchronized (InteractivePrompter.class) {
            fallback = null;
        }
    }

    public static boolean hasJLine() {
        return reader != null;
    }

    public static String prompt(String message) {
        LineReader r = reader;
        if (r != null) {
            try {
                return r.readLine(message);
            } catch (UserInterruptException uie) {
                return "";
            } catch (EndOfFileException eof) {
                return "";
            } catch (RuntimeException e) {
                // Fall through to the system console / scanner
            }
        }
        Console console = System.console();
        if (console != null) {
            String line = console.readLine("%s", message);
            return line == null ? "" : line;
        }
        if (fallback == null) {
            synchronized (InteractivePrompter.class) {
                if (fallback == null) {
                    fallback = new Scanner(System.in);
                }
            }
        }
        System.out.print(message);
        System.out.flush();
        if (!fallback.hasNextLine()) {
            return "";
        }
        return fallback.nextLine();
    }
}
