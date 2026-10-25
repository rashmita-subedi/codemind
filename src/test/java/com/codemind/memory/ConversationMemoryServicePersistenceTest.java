package com.codemind.memory;

import com.codemind.memory.ConversationTurn.Role;
import com.codemind.service.AuditService;
import com.codemind.service.PromptGuard;
import com.codemind.service.PromptGuardConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationMemoryServicePersistenceTest {

    @TempDir
    Path tmp;

    private ConversationMemoryService service(int turns, int chars) {
        return new ConversationMemoryService(turns, chars,
                new PromptGuard(new PromptGuardConfig(), new AuditService()), new SessionStore(tmp.toString()));
    }

    @Test
    void recordedTurnsSurviveIntoAFreshServiceInstance() {
        ConversationMemoryService first = service(20, 8000);
        first.recordExchange("sess1", "what is auth", "AuthService at L42");

        // Simulates a process restart: a brand-new service reading the same store directory.
        ConversationMemoryService restarted = service(20, 8000);
        int restored = restarted.resume("sess1");

        assertThat(restored).isEqualTo(2);
        assertThat(restarted.transcript("sess1"))
                .contains("User: what is auth")
                .contains("CodeMind: AuthService at L42");
    }

    @Test
    void resumeAppliesTheWindowBoundToTheFullLog() {
        ConversationMemoryService writer = service(20, 8000);
        for (int i = 0; i < 10; i++) {
            writer.record("sess2", Role.USER, "turn-" + i);
        }

        ConversationMemoryService restarted = service(3, 8000);
        int restored = restarted.resume("sess2");

        assertThat(restored).isEqualTo(3);
        assertThat(restarted.history("sess2")).extracting(ConversationTurn::content)
                .containsExactly("turn-7", "turn-8", "turn-9");
    }

    @Test
    void resumeUnknownSessionRestoresNothing() {
        assertThat(service(20, 8000).resume("never-existed")).isZero();
    }

    @Test
    void resumeNullSessionRestoresNothing() {
        assertThat(service(20, 8000).resume(null)).isZero();
    }

    @Test
    void latestSessionDelegatesToTheStore() {
        ConversationMemoryService service = service(20, 8000);
        service.record("only-session", Role.USER, "hi");

        assertThat(service.latestSession()).contains("only-session");
    }

    @Test
    void newTurnsAfterResumeAppendToTheSameLog() {
        service(20, 8000).recordExchange("sess3", "q1", "a1");

        ConversationMemoryService restarted = service(20, 8000);
        restarted.resume("sess3");
        restarted.recordExchange("sess3", "q2", "a2");

        // A third instance sees all four turns persisted.
        ConversationMemoryService third = service(20, 8000);
        third.resume("sess3");
        assertThat(third.turnCount("sess3")).isEqualTo(4);
        assertThat(third.transcript("sess3")).contains("q1").contains("a1").contains("q2").contains("a2");
    }
}
