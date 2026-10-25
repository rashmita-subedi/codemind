package com.codemind.tool;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class GitDiffToolTest {

    @TempDir
    Path repo;

    @TempDir
    Path notARepo;

    private GitDiffTool tool;

    @BeforeEach
    void setUp() throws Exception {
        tool = new GitDiffTool(new RepoPathResolver(org.mockito.Mockito.mock(com.codemind.service.IndexService.class)));
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

    private void commit(String file, String content, String msg) throws Exception {
        Files.writeString(repo.resolve(file), content);
        git("add", file);
        git("commit", "-m", msg);
    }

    @Test
    void showsUnstagedChanges() throws Exception {
        commit("a.txt", "one\n", "init");
        Files.writeString(repo.resolve("a.txt"), "one\ntwo\n");

        String result = tool.diff(repo.toString(), "unstaged", "");

        assertThat(result).contains("+two");
    }

    @Test
    void showsStagedChanges() throws Exception {
        commit("a.txt", "one\n", "init");
        Files.writeString(repo.resolve("a.txt"), "one\ntwo\n");
        git("add", "a.txt");

        String result = tool.diff(repo.toString(), "staged", "");

        assertThat(result).contains("+two");
    }

    @Test
    void showsRangeDiff() throws Exception {
        commit("a.txt", "one\n", "first");
        commit("a.txt", "one\ntwo\n", "second");

        String result = tool.diff(repo.toString(), "range", "HEAD~1..HEAD");

        assertThat(result).contains("+two");
    }

    @Test
    void cleanRepoReportsNoChanges() throws Exception {
        commit("a.txt", "one\n", "init");

        String result = tool.diff(repo.toString(), "unstaged", "");

        assertThat(result).isEqualTo("No changes.");
    }

    @Test
    void unknownModeReturnsError() {
        String result = tool.diff(repo.toString(), "sideways", "");

        assertThat(result).startsWith("ERROR: unknown mode 'sideways'");
    }

    @Test
    void rangeModeWithoutRangeReturnsError() {
        String result = tool.diff(repo.toString(), "range", "  ");

        assertThat(result).isEqualTo("ERROR: range mode requires a non-empty range.");
    }

    @Test
    void rangeModeWithNullRangeReturnsError() {
        String result = tool.diff(repo.toString(), "range", null);

        assertThat(result).isEqualTo("ERROR: range mode requires a non-empty range.");
    }

    @Test
    void nullModeReturnsError() {
        String result = tool.diff(repo.toString(), null, "");

        assertThat(result).startsWith("ERROR: unknown mode");
    }

    @Test
    void nonRepoDirectoryReturnsError() {
        String result = tool.diff(notARepo.toString(), "range", "HEAD~1..HEAD");

        assertThat(result).startsWith("ERROR: git diff failed:");
    }

    @Test
    void nonDirectoryReturnsError() throws Exception {
        Path file = repo.resolve("a.txt");
        Files.writeString(file, "x");

        assertThat(tool.diff(file.toString(), "unstaged", "")).startsWith("ERROR: not a directory:");
    }
}
