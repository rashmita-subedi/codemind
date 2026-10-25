package com.codemind.memory;

import com.codemind.memory.ConversationTurn.Role;
import com.codemind.service.AuditService;
import com.codemind.service.PromptGuard;
import com.codemind.service.PromptGuardConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

class ConversationMemoryServiceTest {

    private PromptGuard guard;
    private ConversationMemoryService memory;

    @BeforeEach
    void setUp() {
        guard = new PromptGuard(new PromptGuardConfig(), new AuditService());
        memory = new ConversationMemoryService(20, 8000, guard, SessionStore.disabled());
    }

    @Test
    void recordsAndReturnsHistoryInOrder() {
        memory.record("s", Role.USER, "hi");
        memory.record("s", Role.ASSISTANT, "hello");

        List<ConversationTurn> history = memory.history("s");
        assertThat(history).extracting(ConversationTurn::role)
                .containsExactly(Role.USER, Role.ASSISTANT);
        assertThat(history).extracting(ConversationTurn::content)
                .containsExactly("hi", "hello");
    }

    @Test
    void recordExchangeStoresBothTurns() {
        memory.recordExchange("s", "question", "answer");

        assertThat(memory.turnCount("s")).isEqualTo(2);
        assertThat(memory.transcript("s")).isEqualTo("User: question\nCodeMind: answer");
    }

    @Test
    void evictsOldestWhenTurnCountExceedsWindow() {
        ConversationMemoryService small = new ConversationMemoryService(3, 8000, guard, SessionStore.disabled());
        for (int i = 0; i < 5; i++) {
            small.record("s", Role.USER, "m" + i);
        }

        List<ConversationTurn> history = small.history("s");
        assertThat(history).hasSize(3);
        assertThat(history).extracting(ConversationTurn::content)
                .containsExactly("m2", "m3", "m4");
    }

    @Test
    void evictsOldestWhenCharBudgetExceeded() {
        ConversationMemoryService tight = new ConversationMemoryService(20, 10, guard, SessionStore.disabled());
        tight.record("s", Role.USER, "aaaaaa");   // 6 chars
        tight.record("s", Role.USER, "bbbbbb");   // 6 chars -> total 12 > 10, evict first

        List<ConversationTurn> history = tight.history("s");
        assertThat(history).hasSize(1);
        assertThat(history.get(0).content()).isEqualTo("bbbbbb");
    }

    @Test
    void charBudgetNeverEvictsTheOnlyTurn() {
        ConversationMemoryService tight = new ConversationMemoryService(20, 4, guard, SessionStore.disabled());
        tight.record("s", Role.USER, "way too long to fit the budget");

        assertThat(memoryFor(tight, "s")).hasSize(1);
    }

    @Test
    void constructorFloorsNonPositiveBoundsToOne() {
        ConversationMemoryService floored = new ConversationMemoryService(0, -5, guard, SessionStore.disabled());
        floored.record("s", Role.USER, "a");
        floored.record("s", Role.USER, "b");

        assertThat(floored.turnCount("s")).isEqualTo(1);
        assertThat(floored.history("s").get(0).content()).isEqualTo("b");
    }

    @Test
    void ignoresNullSessionRoleAndBlankContent() {
        memory.record(null, Role.USER, "x");
        memory.record("s", null, "x");
        memory.record("s", Role.USER, null);
        memory.record("s", Role.USER, "   ");

        assertThat(memory.turnCount("s")).isZero();
    }

    @Test
    void sanitizesContentOnStore() {
        PromptGuard spyGuard = spy(new PromptGuard(new PromptGuardConfig(), new AuditService()));
        ConversationMemoryService guarded = new ConversationMemoryService(20, 8000, spyGuard, SessionStore.disabled());

        guarded.record("s", Role.USER, "untrusted text");

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(spyGuard).sanitize(captor.capture());
        assertThat(captor.getValue()).isEqualTo("untrusted text");
    }

    @Test
    void messagesMapsRolesToSpringAiTypes() {
        memory.record("s", Role.USER, "u");
        memory.record("s", Role.ASSISTANT, "a");

        List<Message> messages = memory.messages("s");
        assertThat(messages.get(0)).isInstanceOf(UserMessage.class);
        assertThat(messages.get(1)).isInstanceOf(AssistantMessage.class);
        assertThat(messages.get(0).getText()).isEqualTo("u");
        assertThat(messages.get(1).getText()).isEqualTo("a");
    }

    @Test
    void messagesIsEmptyForUnknownSession() {
        assertThat(memory.messages("nope")).isEmpty();
    }

    @Test
    void transcriptIsEmptyForUnknownSession() {
        assertThat(memory.transcript("nope")).isEmpty();
    }

    @Test
    void historyIsEmptyForUnknownSession() {
        assertThat(memory.history("nope")).isEmpty();
    }

    @Test
    void turnCountIsZeroForUnknownSession() {
        assertThat(memory.turnCount("nope")).isZero();
    }

    @Test
    void clearRemovesSession() {
        memory.record("s", Role.USER, "x");

        memory.clear("s");

        assertThat(memory.turnCount("s")).isZero();
    }

    @Test
    void clearNullSessionIsNoop() {
        memory.record("s", Role.USER, "x");

        memory.clear(null);

        assertThat(memory.turnCount("s")).isEqualTo(1);
    }

    @Test
    void sessionsAreIsolated() {
        memory.record("a", Role.USER, "for-a");
        memory.record("b", Role.USER, "for-b");

        assertThat(memory.transcript("a")).contains("for-a").doesNotContain("for-b");
        assertThat(memory.transcript("b")).contains("for-b").doesNotContain("for-a");
    }

    private List<ConversationTurn> memoryFor(ConversationMemoryService service, String session) {
        return service.history(session);
    }
}
