package com.codemind.tool;

import com.codemind.policy.SafeTool;
import com.codemind.policy.SafetyLevel;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
public class GitDiffTool {

    private final RepoPathResolver repoPaths;

    public GitDiffTool(RepoPathResolver repoPaths) {
        this.repoPaths = repoPaths;
    }

    @Tool(name = "git_diff", description = "Show a git diff. mode is 'unstaged', 'staged', or 'range'. For 'range' pass a commit range such as HEAD~1..HEAD.")
    @SafeTool(SafetyLevel.PREVIEW)
    public String diff(
            @ToolParam(description = "Path to the git repository. Pass '.' to use the currently indexed workspace.") String repoPath,
            @ToolParam(description = "One of: unstaged, staged, range") String mode,
            @ToolParam(description = "Commit range used only when mode is 'range', e.g. HEAD~1..HEAD") String range
    ) {
        String resolved = repoPaths.resolve(repoPath);
        Path repo = Path.of(resolved);
        if (!Files.isDirectory(repo)) {
            return "ERROR: not a directory: " + resolved;
        }
        String normalized = mode == null ? "" : mode.strip().toLowerCase();

        List<String> args = new ArrayList<>(List.of("diff"));
        switch (normalized) {
            case "unstaged" -> { }
            case "staged" -> args.add("--staged");
            case "range" -> {
                if (range == null || range.isBlank()) {
                    return "ERROR: range mode requires a non-empty range.";
                }
                args.add(range.strip());
            }
            default -> {
                return "ERROR: unknown mode '" + mode + "'; expected unstaged, staged, or range.";
            }
        }

        GitResult result = runGit(repo, args);
        if (result.exitCode() != 0) {
            return "ERROR: git diff failed: " + result.output().strip();
        }
        String out = result.output().strip();
        return out.isEmpty() ? "No changes." : out;
    }

    // TODO: shared with GitLogTool/GitCommitTool; extract a GitRunner @Component when convenient.
    @Generated
    private GitResult runGit(Path repoDir, List<String> args) {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.addAll(args);
        try {
            Process p = new ProcessBuilder(cmd)
                    .directory(repoDir.toFile())
                    .redirectErrorStream(true)
                    .start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!p.waitFor(30, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return new GitResult(-1, "git command timed out");
            }
            return new GitResult(p.exitValue(), out);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return new GitResult(-1, e.getMessage() == null ? "" : e.getMessage());
        }
    }

    private record GitResult(int exitCode, String output) {
    }
}
