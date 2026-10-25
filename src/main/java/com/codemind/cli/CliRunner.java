package com.codemind.cli;

import com.codemind.agent.ChangeAnalyzerAgent;
import com.codemind.agent.RefactorAgent;
import com.codemind.agent.core.AgentResult;
import com.codemind.memory.ConversationMemoryService;
import com.codemind.memory.SessionManager;
import com.codemind.service.ApprovalService;
import com.codemind.service.AuditService;
import com.codemind.service.IndexService;
import com.codemind.service.QaService;
import com.codemind.service.RollbackService;
import com.codemind.service.RollbackService.RestoredFile;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.Reference;
import org.jline.reader.UserInterruptException;
import org.jline.reader.impl.DefaultParser;
import org.jline.reader.impl.history.DefaultHistory;
import org.jline.reader.impl.completer.StringsCompleter;
import org.jline.keymap.KeyMap;
import org.jline.terminal.Terminal;
import org.jline.terminal.Terminal.Signal;
import org.jline.terminal.Terminal.SignalHandler;
import org.jline.terminal.TerminalBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Component
public class CliRunner implements CommandLineRunner {

    private static final Set<String> SHELL_LIKE = Set.of(
            "ls", "ll", "dir", "pwd", "cd", "cat", "cp", "mv", "rm", "mkdir", "rmdir",
            "touch", "echo", "grep", "find", "wc", "head", "tail", "less", "more",
            "ps", "kill", "ssh", "scp", "sudo", "chmod", "chown", "tar", "zip", "unzip",
            "curl", "wget", "ping", "tracert", "ipconfig", "ifconfig", "df", "du",
            "git", "docker", "kubectl", "npm", "yarn", "pip", "mvn", "gradle", "java",
            "node", "python", "python3", "go", "cargo", "make", "cmake",
            "cls"
    );

    private final CommandParser parser;
    private final IndexService indexService;
    private final QaService qaService;
    private final RefactorAgent refactorAgent;
    private final ChangeAnalyzerAgent changeAnalyzer;
    private final ApprovalService approvalService;
    private final RollbackService rollbackService;
    private final AuditService auditService;
    private final SessionMode sessionMode;
    private final ConversationMemoryService memory;
    private final SessionManager sessions;
    private final boolean interactiveEnabled;

    public CliRunner(
            CommandParser parser,
            IndexService indexService,
            QaService qaService,
            RefactorAgent refactorAgent,
            ChangeAnalyzerAgent changeAnalyzer,
            ApprovalService approvalService,
            RollbackService rollbackService,
            AuditService auditService,
            SessionMode sessionMode,
            ConversationMemoryService memory,
            SessionManager sessions,
            @Value("${codemind.shell.interactive:true}") boolean interactiveEnabled
    ) {
        this.parser = parser;
        this.indexService = indexService;
        this.qaService = qaService;
        this.refactorAgent = refactorAgent;
        this.changeAnalyzer = changeAnalyzer;
        this.approvalService = approvalService;
        this.rollbackService = rollbackService;
        this.auditService = auditService;
        this.sessionMode = sessionMode;
        this.memory = memory;
        this.sessions = sessions;
        this.interactiveEnabled = interactiveEnabled;
    }

    @Override
    public void run(String... args) {
        // Picocli (driven from CodemindApplication.main) handles subcommands. CliRunner only
        // enters the REPL when invoked with no args or with session-resume flags.
        Optional<SessionLaunchOptions> launch = SessionLaunchOptions.parse(args);
        if (launch.isEmpty()) {
            return;
        }
        if (!interactiveEnabled) {
            return;
        }

        indexCurrentDirectory();
        printBanner();
        applyResume(launch.get());

        DefaultHistory history = new DefaultHistory();
        try {
            Terminal terminal = TerminalBuilder.builder().system(true).build();
            installSigIntHandler(terminal);
            LineReader reader = LineReaderBuilder.builder()
                    .terminal(terminal)
                    .parser(new DefaultParser().escapeChars(new char[0]))
                    .completer(new StringsCompleter(List.of(
                            "/", "/a", "/ask", "/agentic",
                            "/sessions", "/continue", "/new", "--continue", "--session", "--resume",
                            "index", "refactor", "commit", "changelog", "rollback",
                            "clear", "help", "exit", "quit"
                    )))
                    .history(history)
                    .variable(LineReader.HISTORY_FILE, Path.of(System.getProperty("user.home"), ".codemind_history"))
                    .variable(LineReader.HISTORY_SIZE, 1000)
                    .build();
            bindModeKeys(reader);
            InteractivePrompter.register(reader);
            while (true) {
                try {
                    String line = reader.readLine(prompt());
                    if (!handleLine(line)) {
                        break;
                    }
                } catch (UserInterruptException uie) {
                    // Ctrl+C at the empty prompt: stay in the shell, just newline and re-prompt.
                    System.out.println();
                }
            }
        } catch (EndOfFileException e) {
            System.out.println();
        } catch (IOException e) {
            throw new RuntimeException("Failed to initialize terminal", e);
        } finally {
            InteractivePrompter.clear();
            try {
                history.save();
            } catch (IOException ignored) {
                // History is a convenience feature; shell shutdown should not fail if it cannot be saved.
            }
        }
    }

    // Lets the user resume/switch sessions from inside the REPL, e.g. `--continue`,
    // `--session my-feature`, `--resume <id>` — the same flags accepted at launch.
    private boolean handleSessionCommand(String input) {
        String[] tokens = input.trim().split("\\s+");
        Optional<SessionLaunchOptions> parsed = SessionLaunchOptions.parse(tokens);
        if (parsed.isEmpty()) {
            return false;
        }
        SessionLaunchOptions opts = parsed.get();
        if (!opts.continueLatest() && opts.sessionId() == null) {
            return false;
        }
        applyResume(opts);
        return true;
    }

    private void listSessions() {
        List<String> ids = memory.listSessions();
        if (ids.isEmpty()) {
            System.out.println("  " + Ansi.wrap("No saved sessions yet in this folder.", Ansi.DIM));
            return;
        }
        String current = sessions.currentSessionId();
        System.out.println("  " + Ansi.wrap("Saved sessions", Ansi.BOLD, Ansi.BRIGHT_CYAN)
                + Ansi.wrap(" (most recent first)", Ansi.DIM));
        for (String id : ids) {
            String marker = id.equals(current) ? Ansi.wrap("  * ", Ansi.BRIGHT_MAGENTA) : "    ";
            System.out.println(marker + Ansi.wrap(id, id.equals(current) ? Ansi.BOLD : Ansi.DIM));
        }
        System.out.println("  " + Ansi.wrap("resume with `--session <id>` or `--continue`", Ansi.DIM));
    }

    private void applyResume(SessionLaunchOptions launch) {
        String target;
        if (launch.sessionId() != null) {
            target = launch.sessionId();
        } else if (launch.continueLatest()) {
            target = memory.latestSession().orElse(null);
            if (target == null) {
                System.out.println("  " + Ansi.wrap("No previous session to continue.", Ansi.YELLOW)
                        + " " + Ansi.wrap("Starting a fresh session.", Ansi.DIM));
                return;
            }
        } else {
            return;
        }

        String id = sessions.setCurrentSessionId(target);
        int restored = memory.resume(id);
        if (restored > 0) {
            System.out.println("  " + Ansi.wrap("Resumed session ", Ansi.GREEN)
                    + Ansi.wrap(id, Ansi.BOLD)
                    + Ansi.wrap("  " + restored + " turns restored", Ansi.DIM));
            printResumePreview(id);
        } else {
            System.out.println("  " + Ansi.wrap("Started session ", Ansi.GREEN)
                    + Ansi.wrap(id, Ansi.BOLD)
                    + Ansi.wrap("  no prior turns", Ansi.DIM));
        }
    }

    private void printResumePreview(String id) {
        String[] lines = memory.transcript(id).split("\n");
        int from = Math.max(0, lines.length - 4);
        System.out.println("  " + Ansi.wrap("recent context:", Ansi.DIM));
        for (int i = from; i < lines.length; i++) {
            String line = lines[i].length() > 100 ? lines[i].substring(0, 100) + "…" : lines[i];
            System.out.println("    " + Ansi.wrap(line, Ansi.DIM));
        }
    }

    private void indexCurrentDirectory() {
        try {
            String cwd = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize().toString();
            System.out.println(Ansi.wrap("  Indexing", Ansi.BOLD, Ansi.BRIGHT_CYAN)
                    + "  " + Ansi.wrap(cwd, Ansi.DIM));
            indexService.indexCurrentDirectory();
        } catch (Exception e) {
            System.out.println();
            System.out.println("  " + Ansi.wrap("Startup indexing failed", Ansi.BOLD, Ansi.RED));
            System.out.println("  " + Ansi.wrap(String.valueOf(e.getMessage()), Ansi.RED));
            System.out.println("  " + Ansi.wrap("Use `index <path>` to index a specific repository.", Ansi.DIM));
        }
    }

    private void printBanner() {
        System.out.println();
        System.out.println("  " + Ansi.wrap("CodeMind", Ansi.BOLD, Ansi.BRIGHT_CYAN)
                + Ansi.wrap("  repo-aware AI assistant", Ansi.DIM));
        System.out.println();
        System.out.println("  " + Ansi.wrap("Tab", Ansi.BOLD) + Ansi.wrap(" complete", Ansi.DIM)
                + Ansi.wrap("   ", Ansi.DIM)
                + Ansi.wrap("Up/Dn", Ansi.BOLD) + Ansi.wrap(" history", Ansi.DIM)
                + Ansi.wrap("   ", Ansi.DIM)
                + Ansi.wrap("/a", Ansi.BOLD) + Ansi.wrap(" ask <-> agentic", Ansi.DIM)
                + Ansi.wrap("   ", Ansi.DIM)
                + Ansi.wrap("exit", Ansi.BOLD) + Ansi.wrap(" | ", Ansi.DIM)
                + Ansi.wrap("quit", Ansi.BOLD) + Ansi.wrap(" close", Ansi.DIM));
        System.out.println();
    }

    private boolean handleLine(String line) {
        String normalized = line == null ? "" : line.trim();
        if (normalized.isBlank()) {
            return true;
        }
        String lower = normalized.toLowerCase();
        if (handleSlashCommand(lower)) {
            return true;
        }
        if ("quit".equals(lower)) {
            System.out.println("  " + Ansi.wrap("Closed.", Ansi.GREEN));
            return false;
        }
        if ("exit".equals(lower)) {
            String ended = sessions.currentSessionId();
            sessionMode.set(ConversationMode.ASK);
            memory.clear(ended);
            sessions.reset();
            System.out.println("  " + Ansi.wrap("New session started.", Ansi.GREEN)
                    + " " + Ansi.wrap("Previous saved; resume with `--continue` or `--session " + ended + "`.", Ansi.DIM));
            return true;
        }
        if (handleSessionCommand(normalized)) {
            return true;
        }
        try {
            execute(parser.parseLine(normalized));
        } catch (Exception e) {
            System.out.println();
            System.out.println("  " + Ansi.wrap("Command failed", Ansi.BOLD, Ansi.RED));
            System.out.println("  " + Ansi.wrap(String.valueOf(e.getMessage()), Ansi.RED));
        }
        return true;
    }

    private void execute(ParsedCommand command) {
        switch (command.name()) {
            case "index" -> indexService.index(Path.of(pathArg(command, ".")));
            case "ask" -> {
                if (command.args().isEmpty()) {
                    setConversationMode(ConversationMode.ASK);
                } else {
                    qaService.ask(String.join(" ", command.args()));
                }
            }
            case "refactor" -> runRefactor(command);
            case "commit" -> runCommit();
            case "changelog" -> runChangelog(command);
            case "rollback" -> runRollback(command);
            case "agentic" -> setConversationMode(ConversationMode.AGENTIC);
            case "clear" -> handleClear();
            case "help" -> printHelp();
            default -> handleFreeText(toQuestion(command));
        }
    }

    private void runRollback(ParsedCommand command) {
        int howMany = 0;
        for (String arg : command.args()) {
            if (arg.startsWith("--")) {
                continue;
            }
            try {
                howMany = Integer.parseInt(arg);
                break;
            } catch (NumberFormatException e) {
                System.out.println("  " + Ansi.wrap("rollback: expected a number, got '" + arg + "'", Ansi.YELLOW));
                return;
            }
        }
        List<RestoredFile> restored = rollbackService.rollbackLatest(howMany);
        if (restored.isEmpty()) {
            System.out.println();
            System.out.println("  " + Ansi.wrap("Nothing restored.", Ansi.YELLOW)
                    + " " + Ansi.wrap("(no backups under .codemind/backup/, or rollback was declined)", Ansi.DIM));
            return;
        }
        System.out.println();
        System.out.println("  " + Ansi.wrap("Rollback", Ansi.BOLD, Ansi.BRIGHT_CYAN)
                + Ansi.wrap("  restored " + restored.size() + " file(s)", Ansi.DIM));
        for (RestoredFile r : restored) {
            System.out.println("  " + Ansi.wrap(String.format("%10d bytes", r.bytes()), Ansi.DIM)
                    + "  " + r.originalPath());
        }
        auditService.record("rollback.applied", restored.size() + " files");
    }

    private void runRefactor(ParsedCommand command) {
        String target = command.args().stream()
                .filter(arg -> !arg.startsWith("--"))
                .findFirst()
                .orElse("src");
        AgentResult analysis = refactorAgent.analyze(target);
        System.out.println(analysis.answer());
        if (!approvalService.confirm("Apply Phase-1 refactor to " + target + "?")) {
            System.out.println("  " + Ansi.wrap("Skipped.", Ansi.DIM));
            return;
        }
        AgentResult applied = refactorAgent.apply(target, analysis.answer());
        System.out.println(applied.answer());
    }

    private void runCommit() {
        if (!GitCommitter.ensureStaged()) {
            System.out.println();
            System.out.println("  " + Ansi.wrap("Nothing to commit.", Ansi.YELLOW)
                    + " " + Ansi.wrap("(working tree clean, or not a git repo)", Ansi.DIM));
            return;
        }
        AgentResult result = changeAnalyzer.generateCommitMessage();
        System.out.println(result.answer());
        if (!approvalService.confirm("Apply this commit?")) {
            System.out.println("  " + Ansi.wrap("Skipped.", Ansi.DIM));
            return;
        }
        GitCommitter.commit(result.answer());
    }

    private void runChangelog(ParsedCommand command) {
        boolean plain = command.args().stream()
                .anyMatch(arg -> arg.equalsIgnoreCase("--plain") || arg.equalsIgnoreCase("plain"));
        java.util.List<String> revs = command.args().stream()
                .filter(arg -> !arg.startsWith("--") && !arg.equalsIgnoreCase("plain"))
                .toList();
        String range;
        if (revs.isEmpty()) {
            range = "HEAD~1..HEAD";
        } else if (revs.size() == 1) {
            String only = revs.get(0);
            // Either already a range ("A..B") or a single rev — treat single as A..HEAD.
            range = only.contains("..") ? only : (only + "..HEAD");
        } else {
            // Two or more refs → use the first and the last as start/end.
            range = revs.get(0) + ".." + revs.get(revs.size() - 1);
        }
        System.out.println();
        System.out.println("  " + Ansi.wrap("Changelog", Ansi.BOLD, Ansi.BRIGHT_CYAN)
                + Ansi.wrap("  range=" + range + (plain ? "  (plain)" : ""), Ansi.DIM));
        long startNanos = System.nanoTime();
        AgentResult result = changeAnalyzer.generateChangelog(range, plain);
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
        String answer = result.answer();
        if (answer == null || answer.isBlank()) {
            System.out.println();
            System.out.println("  " + Ansi.wrap("No changelog content returned.", Ansi.BOLD, Ansi.RED));
            System.out.println("  " + Ansi.wrap("Check the range exists and the LLM has reachable git output.", Ansi.DIM));
            return;
        }
        System.out.println();
        System.out.println(AnswerRenderer.renderIndented(answer));
        System.out.println();
        int stepCount = result.steps() == null ? 0 : result.steps().size();
        String footer = stepCount + " steps  " + String.format("%.1fs", elapsedMs / 1000.0);
        System.out.println("  " + Ansi.wrap(footer, Ansi.DIM));
    }

    private boolean handleSlashCommand(String lower) {
        return switch (lower) {
            case "/a", "/mode" -> {
                toggleConversationMode();
                yield true;
            }
            case "/ask" -> {
                setConversationMode(ConversationMode.ASK);
                yield true;
            }
            case "/agentic" -> {
                setConversationMode(ConversationMode.AGENTIC);
                yield true;
            }
            case "/sessions" -> {
                listSessions();
                yield true;
            }
            case "/continue", "/resume" -> {
                applyResume(new SessionLaunchOptions(true, null));
                yield true;
            }
            case "/new" -> {
                String ended = sessions.currentSessionId();
                memory.clear(ended);
                String fresh = sessions.reset();
                System.out.println("  " + Ansi.wrap("New session ", Ansi.GREEN) + Ansi.wrap(fresh, Ansi.BOLD)
                        + Ansi.wrap("  previous saved as " + ended, Ansi.DIM));
                yield true;
            }
            default -> false;
        };
    }

    private void handleClear() {
        Path cwd = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        Path active = indexService.currentRoot();
        boolean alreadyOnCwd = active != null && active.equals(cwd);

        if (!alreadyOnCwd) {
            // Active workspace differs from cwd (or nothing indexed yet) - re-index after confirming.
            System.out.println();
            System.out.println("  " + Ansi.wrap("clear", Ansi.BOLD, Ansi.BRIGHT_MAGENTA)
                    + Ansi.wrap(" will reset to ask mode and re-index ", Ansi.DIM)
                    + Ansi.wrap(cwd.toString(), Ansi.YELLOW));
            if (!approvalService.confirm("Continue?")) {
                System.out.println("  " + Ansi.wrap("Skipped.", Ansi.DIM));
                return;
            }
        }

        // Clear screen + cursor home (ANSI). Falls back gracefully if no ANSI support.
        System.out.print("[2J[H");
        System.out.flush();
        sessionMode.set(ConversationMode.ASK);

        if (!alreadyOnCwd) {
            try {
                System.out.println(Ansi.wrap("  Indexing", Ansi.BOLD, Ansi.BRIGHT_CYAN)
                        + "  " + Ansi.wrap(cwd.toString(), Ansi.DIM));
                indexService.index(cwd);
            } catch (Exception e) {
                System.out.println("  " + Ansi.wrap("Re-index failed: " + e.getMessage(), Ansi.RED));
                return;
            }
        }
        printBanner();
    }

    private void handleFreeText(String question) {
        String trimmed = question.trim();
        if (trimmed.isEmpty()) {
            return;
        }
        if (trimmed.startsWith("!")) {
            ShellRunner.run(trimmed.substring(1).trim());
            return;
        }
        if (looksLikeShellCommand(trimmed)) {
            ShellRunner.run(trimmed);
            return;
        }
        if (isNoiseInput(trimmed)) {
            System.out.println("  " + Ansi.wrap("That doesn't look like a question.", Ansi.YELLOW)
                    + " " + Ansi.wrap("Type `help` to see commands.", Ansi.DIM));
            return;
        }
        if (sessionMode.isAgentic()) {
            qaService.ask("Agentic analysis request: " + question + ". Reason across API, service, data, and integration flow.");
            return;
        }
        qaService.ask(question);
    }

    /**
     * Filters out accidental keystrokes like {@code //}, {@code --}, {@code ?},
     * {@code ..}, {@code ##}, etc. — anything with fewer than 3 alphanumeric
     * characters. Stops the LLM round-trip on noise input.
     */
    private boolean isNoiseInput(String text) {
        int alphanum = 0;
        for (int i = 0; i < text.length(); i++) {
            if (Character.isLetterOrDigit(text.charAt(i))) {
                alphanum++;
                if (alphanum >= 3) {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean looksLikeShellCommand(String text) {
        String firstToken = text.split("\\s+", 2)[0].toLowerCase();
        if (!SHELL_LIKE.contains(firstToken)) {
            return false;
        }
        String lower = text.toLowerCase();
        if (lower.contains("?") || lower.contains("explain") || lower.contains("what")
                || lower.contains("how") || lower.contains("why") || lower.contains("describe")) {
            return false;
        }
        return text.split("\\s+").length <= 4;
    }

    private String toQuestion(ParsedCommand command) {
        if (command.args().isEmpty()) {
            return command.name();
        }
        return command.name() + " " + String.join(" ", command.args());
    }

    private String pathArg(ParsedCommand command, String fallback) {
        return command.args().isEmpty() ? fallback : String.join(" ", command.args());
    }

    private void setConversationMode(ConversationMode mode) {
        sessionMode.set(mode);
        String name = mode.name().toLowerCase();
        String color = "ask".equals(name) ? Ansi.CYAN : Ansi.BRIGHT_MAGENTA;
        System.out.println("  " + Ansi.wrap("mode:", Ansi.DIM)
                + " " + Ansi.wrap(name, Ansi.BOLD, color));
    }

    private void toggleConversationMode() {
        setConversationMode(sessionMode.isAsk() ? ConversationMode.AGENTIC : ConversationMode.ASK);
    }

    private String prompt() {
        String name = Ansi.wrap("codemind", Ansi.BOLD, Ansi.BRIGHT_CYAN);
        String modeLabel = sessionMode.get().name().toLowerCase();
        String color = sessionMode.isAsk() ? Ansi.CYAN : Ansi.BRIGHT_MAGENTA;
        String chip = Ansi.wrap(modeLabel, color);
        String arrow = Ansi.wrap(">", Ansi.BOLD, Ansi.BRIGHT_MAGENTA);
        return "\n  " + name + "  " + chip + "  " + arrow + " ";
    }

    private void installSigIntHandler(Terminal terminal) {
        SignalHandler[] previous = new SignalHandler[1];
        previous[0] = terminal.handle(Signal.INT, sig -> {
            if (OperationRegistry.cancelCurrent()) {
                return;
            }
            SignalHandler prev = previous[0];
            if (prev != null && prev != SignalHandler.SIG_DFL && prev != SignalHandler.SIG_IGN) {
                prev.handle(sig);
            }
        });
    }

    private void bindModeKeys(LineReader reader) {
        reader.getWidgets().put("toggle-conversation-mode", () -> {
            toggleConversationMode();
            return true;
        });
        KeyMap<org.jline.reader.Binding> main = reader.getKeyMaps().get(LineReader.MAIN);
        main.bind(new Reference("toggle-conversation-mode"), KeyMap.alt("a"));
    }

    private void printHelp() {
        System.out.println();
        System.out.println("  " + Ansi.wrap("CodeMind commands", Ansi.BOLD, Ansi.BRIGHT_CYAN));
        System.out.println();
        System.out.println("  " + Ansi.wrap("ask mode (default)", Ansi.BOLD, Ansi.CYAN)
                + Ansi.wrap("        type a question to ask about the indexed code", Ansi.DIM));
        System.out.println("  " + Ansi.wrap("agentic mode      ", Ansi.BOLD, Ansi.BRIGHT_MAGENTA)
                + Ansi.wrap("        same, but commit / refactor / changelog auto-apply", Ansi.DIM));
        System.out.println("  " + Ansi.wrap(String.format("%-26s", "/a, Alt+A"), Ansi.YELLOW)
                + Ansi.wrap("toggle ask <-> agentic", Ansi.DIM));
        System.out.println();
        String[][] rows = {
                {"index [path]", "re-index. defaults to current directory"},
                {"commit", "draft a commit from git diff (asks before applying in ask mode)"},
                {"changelog [range]", "draft a changelog entry (asks before printing in ask mode)"},
                {"refactor <path>", "plan + apply AI refactor (asks per file in ask mode)"},
                {"rollback [n]", "restore the most recent refactor (n>0 = restore n newest backups)"},
                {"!<command>", "run a shell command in the project directory"},
                {"--continue", "resume your most recent session"},
                {"--session <id>", "resume or switch to a named session"},
                {"/sessions", "list saved sessions in this folder"},
                {"/new", "start a fresh session (previous stays saved)"}
        };
        for (String[] row : rows) {
            System.out.println("  " + Ansi.wrap(String.format("%-26s", row[0]), Ansi.YELLOW)
                    + Ansi.wrap(row[1], Ansi.DIM));
        }
        System.out.println();
        String[][] session = {
                {"clear", "clear screen, reset to ask mode, re-index current directory"},
                {"help", "this help"},
                {"exit", "end current session, start a new one (previous saved)"},
                {"quit", "close CodeMind (session stays saved)"}
        };
        for (String[] row : session) {
            System.out.println("  " + Ansi.wrap(String.format("%-26s", row[0]), Ansi.YELLOW)
                    + Ansi.wrap(row[1], Ansi.DIM));
        }
        System.out.println();
    }
}
