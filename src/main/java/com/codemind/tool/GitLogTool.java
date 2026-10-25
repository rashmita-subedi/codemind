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
public class GitLogTool {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 200;

    private final RepoPathResolver repoPaths;

    public GitLogTool(RepoPathResolver repoPaths) {
        this.repoPaths = repoPaths;
    }

    @Tool(name = "git_log", description = "Show recent git commit history as oneline entries. limit caps the number of commits; range optionally narrows it (e.g. HEAD~5..HEAD).")
    @SafeTool(SafetyLevel.SAFE)
    public String log(
            @ToolParam(description = "Path to the git repository. Pass '.' to use the currently indexed workspace.") String repoPath,
            @ToolParam(description = "Maximum commits to show (1-200). Use 0 for the default of 20.") int limit,
            @ToolParam(description = "Optional commit range such as HEAD~5..HEAD. Use the empty string for none.") String range
    ) {
        String resolved = repoPaths.resolve(repoPath);
        Path repo = Path.of(resolved);
        if (!Files.isDirectory(repo)) {
            return "ERROR: not a directory: " + resolved;
        }

        int n = limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
        List<String> args = new ArrayList<>(List.of("log", "--oneline", "--decorate", "-n", String.valueOf(n)));
        if (range != null && !range.isBlank()) {
            args.add(range.strip());
        }

        GitResult result = runGit(repo, args);
        if (result.exitCode() != 0) {
            if (result.output().contains("does not have any commits")) {
                return "No commits.";
            }
            return "ERROR: git log failed: " + result.output().strip();
        }
        return result.output().strip();
    }

    // TODO: the runGit subprocess boundary is triplicated across GitLogTool/GitDiffTool/
    // GitCommitTool. Extract a shared GitRunner @Component when convenient (not required now).
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
