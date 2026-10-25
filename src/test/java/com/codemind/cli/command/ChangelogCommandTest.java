package com.codemind.cli.command;

import com.codemind.agent.ChangeAnalyzerAgent;
import com.codemind.agent.core.AgentResult;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChangelogCommandTest {

    private ChangeAnalyzerAgent changeAnalyzer;
    private ChangelogCommand command;
    private final PrintStream realOut = System.out;
    private ByteArrayOutputStream captured;

    @BeforeEach
    void setUp() {
        changeAnalyzer = mock(ChangeAnalyzerAgent.class);
        command = new ChangelogCommand(changeAnalyzer);
        captured = new ByteArrayOutputStream();
        System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
    }

    @AfterEach
    void tearDown() {
        System.setOut(realOut);
    }

    private AgentResult result(String answer) {
        return new AgentResult(answer, List.of(), 0, 0L, false);
    }

    @Test
    void usesDefaultRangeAndEngineerModeAndPrintsAnswer() {
        when(changeAnalyzer.generateChangelog("HEAD~1..HEAD", false)).thenReturn(result("## Changes"));

        command.run();

        assertThat(captured.toString(StandardCharsets.UTF_8)).contains("## Changes");
        verify(changeAnalyzer).generateChangelog("HEAD~1..HEAD", false);
    }

    @Test
    void passesRangeAndPlainFlagThrough() {
        ReflectionTestUtils.setField(command, "range", "v1.0..HEAD");
        ReflectionTestUtils.setField(command, "plain", true);
        when(changeAnalyzer.generateChangelog("v1.0..HEAD", true)).thenReturn(result("plain notes"));

        command.run();

        assertThat(captured.toString(StandardCharsets.UTF_8)).contains("plain notes");
        verify(changeAnalyzer).generateChangelog("v1.0..HEAD", true);
    }
}
