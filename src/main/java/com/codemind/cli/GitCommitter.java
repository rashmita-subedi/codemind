package com.codemind.cli;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Runs {@code git commit -F <tempfile>} in the project working directory using a
 * commit message drafted by the LLM. Cleans common wrappers the model adds
 * ({@code FINAL_ANSWER:}, triple backtick fences) and refuses to commit if the
 * model surfaced an error string instead of a message.
 */
public final class GitCommitter {

    private static volatile java.nio.file.Path activeRoot;

    private GitCommitter() {
    }

    /**
     * Sets the working directory for subsequent {@code git} invocations.
     * Called by {@link com.codemind.service.IndexService} whenever the user
     * indexes (or loads) a workspace, so {@code commit} runs against the
     * indexed repo rather than the JVM's launch directory.
     */
    public static void setActiveRoot(java.nio.file.Path root) {
        activeRoot = root;
    }

    public static java.nio.file.Path activeRoot() {
        return activeRoot;
    }

    private static File workingDirectory() {
        java.nio.file.Path root = activeRoot;
        if (root != null) {
            return root.toFile();
        }
        return new File(System.getProperty("user.dir"));
    }

    /**
     * If nothing is currently staged, stages every tracked + untracked change with
     * {@code git add -A}. If the user has already pre-staged something, leaves it
     * alone (so manual partial-staging workflows still work).
     *
     * @return {@code true} if there is at least one staged change after this call.
     */
    public static boolean ensureStaged() {
        if (hasStagedChanges()) {
            return true;
        }
        if (!hasAnyWorkingChanges()) {
            return false;
        }
        System.out.println("  " + Ansi.wrap("Staging all changes...", Ansi.DIM));
        int exit = runGit(30, "add", "-A");
        if (exit != 0) {
            System.out.println("  " + Ansi.wrap("git add failed (exit " + exit + ")", Ansi.RED));
            return false;
        }
        return hasStagedChanges();
    }

    /**
     * @return {@code true} if {@code git diff --cached} reports any staged changes.
     */
    public static boolean hasStagedChanges() {
        // `git diff --cached --quiet` exits 0 when there are no staged changes,
        // 1 when there are. Anything else (128 = not a repo) means git itself failed.
        return runGitQuiet(10, "diff", "--cached", "--quiet") == 1;
    }

    /**
     * @return {@code true} if there is any working-tree change (tracked diff or
     * untracked file) that could potentially be staged.
     */
    public static boolean hasAnyWorkingChanges() {
        return runGitQuiet(10, "status", "--porcelain") >= 0 && hasNonEmptyStatus();
    }

    private static boolean hasNonEmptyStatus() {
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "status", "--porcelain");
            pb.directory(workingDirectory());
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                String line = reader.readLine();
                proc.waitFor(10, TimeUnit.SECONDS);
                return line != null && !line.isBlank();
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }

    private static int runGitQuiet(int timeoutSec, String... args) {
        try {
            String[] cmd = new String[args.length + 1];
            cmd[0] = "git";
            System.arraycopy(args, 0, cmd, 1, args.length);
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(workingDirectory());
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            Process proc = pb.start();
            if (!proc.waitFor(timeoutSec, TimeUnit.SECONDS)) {
                proc.destroyForcibly();
                return -1;
            }
            return proc.exitValue();
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return -1;
        }
    }

    private static int runGit(int timeoutSec, String... args) {
        try {
            String[] cmd = new String[args.length + 1];
            cmd[0] = "git";
            System.arraycopy(args, 0, cmd, 1, args.length);
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(workingDirectory());
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    System.out.println("  " + line);
                }
            }
            if (!proc.waitFor(timeoutSec, TimeUnit.SECONDS)) {
                proc.destroyForcibly();
                return -1;
            }
            return proc.exitValue();
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return -1;
        }
    }

    public static boolean commit(String rawMessage) {
        String message = clean(rawMessage);
        if (message == null || message.isBlank()) {
            System.out.println("  " + Ansi.wrap("No usable commit message; aborting.", Ansi.RED));
            return false;
        }
        Path tmp;
        try {
            tmp = Files.createTempFile("codemind-commit-", ".txt");
            Files.writeString(tmp, message, StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.out.println("  " + Ansi.wrap("Could not write commit message: " + e.getMessage(), Ansi.RED));
            return false;
        }
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "commit", "-F", tmp.toString());
            pb.directory(workingDirectory());
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    System.out.println("  " + line);
                }
            }
            boolean finished = proc.waitFor(30, TimeUnit.SECONDS);
            if (!finished) {
                proc.destroyForcibly();
                System.out.println("  " + Ansi.wrap("git commit timed out.", Ansi.RED));
                return false;
            }
            int exit = proc.exitValue();
            if (exit == 0) {
                System.out.println();
                System.out.println("  " + Ansi.wrap("Committed.", Ansi.BOLD, Ansi.GREEN));
                return true;
            }
            System.out.println();
            System.out.println("  " + Ansi.wrap("git commit failed (exit " + exit + ")", Ansi.RED));
            return false;
        } catch (IOException e) {
            System.out.println("  " + Ansi.wrap("git commit failed: " + e.getMessage(), Ansi.RED));
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.out.println("  " + Ansi.wrap("git commit interrupted.", Ansi.YELLOW));
            return false;
        } finally {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // best effort
            }
        }
    }

    /**
     * Strips common LLM wrappers and refuses ERROR replies.
     *
     * @return cleaned message, or {@code null} if the input looks like an error/refusal.
     */
    static String clean(String raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.trim();
        // FINAL_ANSWER: prefix the ReAct engine sometimes leaves in place.
        if (text.startsWith("FINAL_ANSWER:")) {
            text = text.substring("FINAL_ANSWER:".length()).trim();
        }
        // Surrounding triple-backtick fence (sometimes language-tagged).
        if (text.startsWith("```")) {
            int firstNl = text.indexOf('\n');
            if (firstNl >= 0) {
                text = text.substring(firstNl + 1);
            }
            if (text.endsWith("```")) {
                text = text.substring(0, text.length() - 3);
            }
            text = text.trim();
        }
        // Refuse to commit error / refusal text.
        String upper = text.toUpperCase();
        if (upper.startsWith("ERROR:")
                || upper.startsWith("ERROR ")
                || text.contains("No staged changes")
                || text.contains("nothing to commit")) {
            return null;
        }
        return text;
    }
}
