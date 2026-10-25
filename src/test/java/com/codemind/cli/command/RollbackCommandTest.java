package com.codemind.cli.command;

import com.codemind.service.AuditService;
import com.codemind.service.RollbackService;
import com.codemind.service.RollbackService.RestoredFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RollbackCommandTest {

    private RollbackService rollbackService;
    private AuditService audit;
    private RollbackCommand command;
    private final PrintStream realOut = System.out;
    private ByteArrayOutputStream captured;

    @BeforeEach
    void setUp() {
        rollbackService = mock(RollbackService.class);
        audit = mock(AuditService.class);
        command = new RollbackCommand(rollbackService, audit);
        captured = new ByteArrayOutputStream();
        System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
    }

    @AfterEach
    void tearDown() {
        System.setOut(realOut);
    }

    @Test
    void printsNothingRestoredWhenServiceReturnsEmpty() {
        when(rollbackService.rollbackLatest(anyInt())).thenReturn(List.of());

        command.run();

        String output = captured.toString(StandardCharsets.UTF_8);
        assertThat(output).contains("Nothing restored");
        verify(audit, never()).record("rollback.applied", "0 files");
    }

    @Test
    void printsRestoredTableAndAuditsApplied() {
        Path a = Path.of("src/A.java").toAbsolutePath();
        Path bakA = Path.of(".codemind/backup/src__A.java.1.bak").toAbsolutePath();
        when(rollbackService.rollbackLatest(anyInt())).thenReturn(List.of(
                new RestoredFile(a, bakA, 42L)));

        command.run();

        String output = captured.toString(StandardCharsets.UTF_8);
        assertThat(output).contains("Rollback");
        assertThat(output).contains("restored 1 file(s)");
        assertThat(output).contains("42 bytes");
        assertThat(output).contains(a.toString());
        verify(audit).record("rollback.applied", "1 files");
    }

    @Test
    void passesExplicitCountToService() throws Exception {
        when(rollbackService.rollbackLatest(3)).thenReturn(List.of());
        setHowMany(3);

        command.run();

        verify(rollbackService).rollbackLatest(3);
    }

    @Test
    void defaultsToZeroWhenNoArgGiven() {
        when(rollbackService.rollbackLatest(0)).thenReturn(List.of());

        command.run();

        verify(rollbackService).rollbackLatest(0);
    }

    private void setHowMany(int value) throws Exception {
        Field f = RollbackCommand.class.getDeclaredField("howMany");
        f.setAccessible(true);
        f.setInt(command, value);
    }
}
