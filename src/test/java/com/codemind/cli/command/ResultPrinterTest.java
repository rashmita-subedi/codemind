package com.codemind.cli.command;

import com.codemind.agent.core.AgentResult;
import com.codemind.agent.core.ReActStep;
import com.codemind.policy.SafetyLevel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ResultPrinterTest {

    private final PrintStream realOut = System.out;
    private ByteArrayOutputStream captured;

    @BeforeEach
    void setUp() {
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
    void nonVerbosePrintsOnlyAnswer() {
        ResultPrinter.print(new AgentResult("the answer", List.of(), 0, 0L, false), false);

        assertThat(output()).contains("the answer");
        assertThat(output()).doesNotContain("--- trace");
    }

    @Test
    void verbosePrintsTraceForAppliedStepWithinLimit() {
        ReActStep applied = new ReActStep(1, "t", "read_file", "{}", "read it", SafetyLevel.SAFE, true, 9L);
        ResultPrinter.print(new AgentResult("answer", List.of(applied), 1, 9L, false), true);

        String out = output();
        assertThat(out).contains("--- trace (1 steps, 9 ms) ---");
        assertThat(out).contains("1. read_file — read it");
        assertThat(out).doesNotContain("[not applied]");
        assertThat(out).doesNotContain("hit step limit");
    }

    @Test
    void verboseMarksDeclinedStepsAndStepLimit() {
        ReActStep declined = new ReActStep(2, "t", "write_file", "{}", "User declined",
                SafetyLevel.CONFIRM_REQUIRED, false, 4L);
        ResultPrinter.print(new AgentResult("answer", List.of(declined), 1, 4L, true), true);

        String out = output();
        assertThat(out).contains("hit step limit");
        assertThat(out).contains("2. write_file [not applied] — User declined");
    }
}
