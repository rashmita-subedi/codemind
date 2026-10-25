package com.codemind.tool;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class GitLogToolTest {

    @TempDir
    Path repo;

    private GitLogTool tool;

    @BeforeEach
    void setUp() throws Exception {
        tool = new GitLogTool(new RepoPathResolver(org.mockito.Mockito.mock(com.codemind.service.IndexService.class)));
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

    private void commit(String file, String msg) throws Exception {
        Files.writeString(repo.resolve(file), "content of " + file);
        git("add", file);
        git("commit", "-m", msg);
    }

    @Test
    void showsCommitHistory() throws Exception {
        commit("a.txt", "first commit");
        commit("b.txt", "second commit");

        String result = tool.log(repo.toString(), 0, "");

        assertThat(result).contains("first commit").contains("second commit");
    }

    @Test
    void honorsLimit() throws Exception {
        commit("a.txt", "first commit");
        commit("b.txt", "second commit");
        commit("c.txt", "third commit");

        String result = tool.log(repo.toString(), 1, "");

        assertThat(result.lines().count()).isEqualTo(1);
        assertThat(result).contains("third commit");
    }

    @Test
    void honorsRange() throws Exception {
        commit("a.txt", "first commit");
        commit("b.txt", "second commit");

        String result = tool.log(repo.toString(), 0, "HEAD~1..HEAD");

        assertThat(result).contains("second commit").doesNotContain("first commit");
    }

    @Test
    void nullRangeIsTreatedAsNoRange() throws Exception {
        commit("a.txt", "first commit");

        String result = tool.log(repo.toString(), 0, null);

        assertThat(result).contains("first commit");
    }

    @Test
    void emptyRepoReportsNoCommits() {
        String result = tool.log(repo.toString(), 0, "");

        assertThat(result).isEqualTo("No commits.");
    }

    @Test
    void badRangeReturnsError() throws Exception {
        commit("a.txt", "first commit");

        String result = tool.log(repo.toString(), 0, "no-such-ref..HEAD");

        assertThat(result).startsWith("ERROR: git log failed:");
    }

    @Test
    void nonDirectoryReturnsError() throws Exception {
        Path file = repo.resolve("a.txt");
        Files.writeString(file, "x");

        assertThat(tool.log(file.toString(), 0, "")).startsWith("ERROR: not a directory:");
    }
}
