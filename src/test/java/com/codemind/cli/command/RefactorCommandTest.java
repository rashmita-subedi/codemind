package com.codemind.cli.command;

import com.codemind.agent.RefactorAgent;
import com.codemind.agent.core.AgentResult;
import com.codemind.service.ApprovalService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RefactorCommandTest {

    private RefactorAgent refactorAgent;
    private ApprovalService approvalService;
    private RefactorCommand command;
    private final PrintStream realOut = System.out;
    private ByteArrayOutputStream captured;

    @BeforeEach
    void setUp() {
        refactorAgent = mock(RefactorAgent.class);
        approvalService = mock(ApprovalService.class);
        command = new RefactorCommand(refactorAgent, approvalService);
        when(refactorAgent.analyze(anyString()))
                .thenReturn(new AgentResult("analysis", List.of(), 0, 0L, false));
        when(refactorAgent.apply(anyString(), any()))
                .thenReturn(new AgentResult("applied", List.of(), 0, 0L, false));
        captured = new ByteArrayOutputStream();
        System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
    }

    @AfterEach
    void tearDown() {
        System.setOut(realOut);
    }

    private String output() {
        return captured.toString(StandardCharsets.UTF_8);
    }

    @Test
    void defaultsToAnalyzeOnlyWithoutApplyFlag() {
        command.run();

        assertThat(output()).contains("analysis");
        verify(refactorAgent).analyze("src");
        verify(refactorAgent, never()).apply(anyString(), any());
    }

    @Test
    void applyFlagAsksForConfirmationAndAppliesOnYes() {
        ReflectionTestUtils.setField(command, "target", "src/Foo.java");
        ReflectionTestUtils.setField(command, "apply", true);
        when(approvalService.confirm("Apply Phase-1 refactor to src/Foo.java?")).thenReturn(true);

        command.run();

        assertThat(output()).contains("analysis").contains("applied");
        verify(refactorAgent).analyze("src/Foo.java");
        verify(refactorAgent).apply("src/Foo.java", "analysis");
        verify(approvalService).confirm("Apply Phase-1 refactor to src/Foo.java?");
    }

    @Test
    void applyFlagWithDeclinedConfirmationSkipsApply() {
        ReflectionTestUtils.setField(command, "target", "src/Foo.java");
        ReflectionTestUtils.setField(command, "apply", true);
        when(approvalService.confirm("Apply Phase-1 refactor to src/Foo.java?")).thenReturn(false);

        command.run();

        assertThat(output()).contains("analysis").contains("Skipped");
        verify(refactorAgent).analyze("src/Foo.java");
        verify(refactorAgent, never()).apply(anyString(), any());
    }

    @Test
    void verbosePrintsTrace() {
        ReflectionTestUtils.setField(command, "verbose", true);

        command.run();

        assertThat(output()).contains("--- trace");
    }
}
