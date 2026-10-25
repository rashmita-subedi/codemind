package com.codemind.cli.command;

import com.codemind.agent.ChangeAnalyzerAgent;
import com.codemind.agent.core.AgentResult;
import com.codemind.cli.GitCommitter;
import com.codemind.service.ApprovalService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CommitCommandTest {

    @TempDir
    Path repo;

    private ChangeAnalyzerAgent changeAnalyzer;
    private ApprovalService approvalService;
    private CommitCommand command;
    private final PrintStream realOut = System.out;
    private ByteArrayOutputStream captured;

    @BeforeEach
    void setUp() throws Exception {
        changeAnalyzer = mock(ChangeAnalyzerAgent.class);
        approvalService = mock(ApprovalService.class);
        command = new CommitCommand(changeAnalyzer, approvalService);
        when(changeAnalyzer.generateCommitMessage())
                .thenReturn(new AgentResult("feat: add thing", List.of(), 0, 0L, false));
        captured = new ByteArrayOutputStream();
        System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
        initRepoWithStagedFile();
        GitCommitter.setActiveRoot(repo);
    }

    @AfterEach
    void tearDown() {
        GitCommitter.setActiveRoot(null);
        System.setOut(realOut);
    }

    private void initRepoWithStagedFile() throws IOException, InterruptedException {
        git("init");
        git("config", "user.email", "t@t.dev");
        git("config", "user.name", "Tester");
        Files.writeString(repo.resolve("a.txt"), "hello");
        git("add", "a.txt");
    }

    private void git(String... args) throws IOException, InterruptedException {
        java.util.List<String> cmd = new java.util.ArrayList<>();
        cmd.add("git");
        for (String a : args) cmd.add(a);
        Process p = new ProcessBuilder(cmd).directory(repo.toFile()).redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        p.waitFor();
    }

    @Test
    void generatesMessageAndAsksForConfirmation() {
        when(approvalService.confirm("Apply this commit?")).thenReturn(true);

        command.run();

        assertThat(captured.toString(StandardCharsets.UTF_8)).contains("feat: add thing");
        verify(changeAnalyzer).generateCommitMessage();
        verify(approvalService).confirm("Apply this commit?");
    }

    @Test
    void declineConfirmationSkipsApply() {
        when(approvalService.confirm("Apply this commit?")).thenReturn(false);

        command.run();

        String output = captured.toString(StandardCharsets.UTF_8);
        assertThat(output).contains("feat: add thing");
        assertThat(output).contains("Skipped");
    }
}
