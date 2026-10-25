package com.codemind.tool;

import com.codemind.policy.ActionRequest;
import com.codemind.policy.SafeTool;
import com.codemind.policy.SafetyLevel;
import com.codemind.service.ApprovalService;
import com.codemind.service.AuditService;
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
public class GitCommitTool {

    private final ApprovalService approvals;
    private final AuditService audit;
    private final RepoPathResolver repoPaths;

    public GitCommitTool(ApprovalService approvals, AuditService audit, RepoPathResolver repoPaths) {
        this.approvals = approvals;
        this.audit = audit;
        this.repoPaths = repoPaths;
    }

    @Tool(name = "git_commit", description = "Commit currently staged changes with a message. Returns the new short commit hash. Requires approval.")
    @SafeTool(SafetyLevel.CONFIRM_REQUIRED)
    public String commit(
            @ToolParam(description = "Path to the git repository. Pass '.' to use the currently indexed workspace.") String repoPath,
            @ToolParam(description = "Commit message") String message
    ) {
        String resolved = repoPaths.resolve(repoPath);
        if (message == null || message.isBlank()) {
            return "ERROR: empty commit message.";
        }
        Path repo = Path.of(resolved);
        if (!Files.isDirectory(repo)) {
            return "ERROR: not a directory: " + resolved;
        }

        GitResult staged = runGit(repo, List.of("diff", "--staged", "--stat"));
        if (staged.exitCode() != 0) {
            return "ERROR: not a git repository: " + resolved;
        }
        if (staged.output().isBlank()) {
            return "ERROR: nothing staged to commit.";
        }

        var request = new ActionRequest(
                "git_commit:" + resolved,
                SafetyLevel.CONFIRM_REQUIRED,
                "Commit message: " + message + "\n\nStaged:\n" + staged.output().strip());
        if (!approvals.approved(request)) {
            audit.record("git_commit.rejected", resolved);
            return "User declined the commit in " + resolved;
        }

        GitResult commit = runGit(repo, List.of("commit", "-m", message));
        if (commit.exitCode() != 0) {
            audit.record("git_commit.failed", resolved);
            return "ERROR: git commit failed: " + commit.output().strip();
        }

        GitResult hash = runGit(repo, List.of("rev-parse", "--short", "HEAD"));
        audit.record("git_commit.applied", resolved);
        return "Committed " + hash.output().strip() + " in " + resolved;
    }

    // TODO: shared with GitLogTool/GitDiffTool; extract a GitRunner @Component when convenient.
    @Generated
    private GitResult runGit(Path repoDir, List<String> args) {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.addAll(args);
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd)
                    .directory(repoDir.toFile())
                    .redirectErrorStream(true);
            // why: prevents git from walking past $HOME into a stray ~/.git (e.g. accidental
            // global init), which would otherwise make a non-repo tempdir look like a repo.
            String home = System.getProperty("user.home");
            if (home != null && !home.isBlank()) {
                pb.environment().put("GIT_CEILING_DIRECTORIES", home);
            }
            Process p = pb.start();
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
