package com.codemind.tool;

import com.codemind.policy.ActionRequest;
import com.codemind.service.ApprovalService;
import com.codemind.service.AuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentMatchers;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GitCommitToolTest {

    @TempDir
    Path repo;

    @TempDir
    Path notARepo;

    private ApprovalService approvals;
    private AuditService audit;
    private GitCommitTool tool;

    @BeforeEach
    void setUp() throws Exception {
        approvals = mock(ApprovalService.class);
        audit = mock(AuditService.class);
        tool = new GitCommitTool(approvals, audit, new RepoPathResolver(org.mockito.Mockito.mock(com.codemind.service.IndexService.class)));
        git("init");
        git("config", "user.email", "t@t.dev");
        git("config", "user.name", "Tester");
    }

    private String git(String... args) throws IOException, InterruptedException {
        var cmd = new java.util.ArrayList<String>();
        cmd.add("git");
        cmd.addAll(java.util.List.of(args));
        Process p = new ProcessBuilder(cmd).directory(repo.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        p.waitFor();
        return out;
    }

    private void stage(String file, String content) throws Exception {
        Files.writeString(repo.resolve(file), content);
        git("add", file);
    }

    @Test
    void commitsStagedChangesWhenApproved() throws Exception {
        when(approvals.approved(ArgumentMatchers.any(ActionRequest.class))).thenReturn(true);
        stage("a.txt", "hello");

        String result = tool.commit(repo.toString(), "feat: add a");

        assertThat(result).startsWith("Committed ").contains(repo.toString());
        assertThat(git("log", "--oneline")).contains("feat: add a");
        verify(audit).record("git_commit.applied", repo.toString());
    }

    @Test
    void declinedCommitDoesNothing() throws Exception {
        when(approvals.approved(ArgumentMatchers.any(ActionRequest.class))).thenReturn(false);
        stage("a.txt", "hello");

        String result = tool.commit(repo.toString(), "feat: add a");

        assertThat(result).isEqualTo("User declined the commit in " + repo);
        assertThat(git("log", "--oneline")).doesNotContain("feat: add a");
        verify(audit).record("git_commit.rejected", repo.toString());
        verify(audit, never()).record(ArgumentMatchers.eq("git_commit.applied"), ArgumentMatchers.anyString());
    }

    @Test
    void nothingStagedReturnsError() throws Exception {
        when(approvals.approved(ArgumentMatchers.any(ActionRequest.class))).thenReturn(true);
        stage("a.txt", "hello");
        git("commit", "-m", "init");

        String result = tool.commit(repo.toString(), "feat: nothing new");

        assertThat(result).isEqualTo("ERROR: nothing staged to commit.");
    }

    @Test
    void emptyMessageReturnsError() {
        assertThat(tool.commit(repo.toString(), "  ")).isEqualTo("ERROR: empty commit message.");
    }

    @Test
    void nullMessageReturnsError() {
        assertThat(tool.commit(repo.toString(), null)).isEqualTo("ERROR: empty commit message.");
    }

    @Test
    void nonDirectoryReturnsError() throws Exception {
        Path file = repo.resolve("a.txt");
        Files.writeString(file, "x");

        assertThat(tool.commit(file.toString(), "msg")).startsWith("ERROR: not a directory:");
    }

    @Test
    void nonRepoDirectoryReturnsError() {
        String result = tool.commit(notARepo.toString(), "msg");

        assertThat(result).isEqualTo("ERROR: not a git repository: " + notARepo);
    }

    @Test
    void commitFailureIsReportedAndAudited() throws Exception {
        when(approvals.approved(ArgumentMatchers.any(ActionRequest.class))).thenReturn(true);
        Path hook = repo.resolve(".git").resolve("hooks").resolve("pre-commit");
        Files.writeString(hook, "#!/bin/sh\nexit 1\n");
        hook.toFile().setExecutable(true);
        stage("a.txt", "hello");

        String result = tool.commit(repo.toString(), "feat: blocked by hook");

        assertThat(result).startsWith("ERROR: git commit failed:");
        verify(audit).record("git_commit.failed", repo.toString());
    }
}
