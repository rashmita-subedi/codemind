package com.codemind.cli.command;

import com.codemind.agent.DirectorAgent;
import com.codemind.agent.core.AgentResult;
import com.codemind.agent.core.ReActStep;
import com.codemind.policy.SafetyLevel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DoCommandTest {

    private DirectorAgent director;
    private DoCommand command;
    private final PrintStream realOut = System.out;
    private ByteArrayOutputStream captured;

    @BeforeEach
    void setUp() {
        director = mock(DirectorAgent.class);
        command = new DoCommand(director);
        ReflectionTestUtils.setField(command, "input", List.of("refactor", "Foo.java"));
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
    void printsAnswerWithoutTraceWhenNotVerbose() {
        when(director.dispatch("refactor Foo.java"))
                .thenReturn(new AgentResult("the answer", List.of(), 0, 0L, false));

        command.run();

        assertThat(output()).contains("the answer");
        assertThat(output()).doesNotContain("--- trace");
    }

    @Test
    void printsTraceWhenVerbose() {
        ReflectionTestUtils.setField(command, "verbose", true);
        ReActStep applied = new ReActStep(1, "t", "search", "{}", "found it", SafetyLevel.SAFE, true, 12L);
        when(director.dispatch("refactor Foo.java"))
                .thenReturn(new AgentResult("answer", List.of(applied), 1, 12L, false));

        command.run();

        String out = output();
        assertThat(out).contains("--- trace (1 steps, 12 ms) ---");
        assertThat(out).contains("1. search — found it");
        assertThat(out).doesNotContain("hit step limit");
        assertThat(out).doesNotContain("[not applied]");
    }

    @Test
    void verboseTraceMarksDeclinedStepsAndStepLimit() {
        ReflectionTestUtils.setField(command, "verbose", true);
        ReActStep declined = new ReActStep(2, "t", "write_file", "{}", "User declined",
                SafetyLevel.CONFIRM_REQUIRED, false, 3L);
        when(director.dispatch("refactor Foo.java"))
                .thenReturn(new AgentResult("answer", List.of(declined), 1, 3L, true));

        command.run();

        String out = output();
        assertThat(out).contains("hit step limit");
        assertThat(out).contains("2. write_file [not applied] — User declined");
    }
}
