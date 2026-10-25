package com.codemind.agent;

import com.codemind.agent.core.AgentContext;
import com.codemind.agent.core.AgentExecutor;
import com.codemind.agent.core.AgentResult;
import com.codemind.memory.SessionManager;
import com.codemind.service.AuditService;
import com.codemind.service.PromptGuard;
import com.codemind.service.PromptGuardConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QnAAgentTest {

    private ChatClient chatClient;
    private AgentExecutor executor;
    private SessionManager sessions;
    private QnAAgent agent;
    private final AgentResult canned = new AgentResult("answer", List.of(), 0, 0L, false);

    @BeforeEach
    void setUp() {
        chatClient = mock(ChatClient.class);
        executor = mock(AgentExecutor.class);
        sessions = new SessionManager();
        agent = new QnAAgent(chatClient, executor, new PromptGuard(new PromptGuardConfig(), new AuditService()), sessions);
        when(executor.execute(any(), any())).thenReturn(canned);
    }

    @Test
    void runsExecutorWithSanitizedQuestion() {
        AgentResult result = agent.ask("where is auth");

        assertThat(result).isSameAs(canned);
        ArgumentCaptor<AgentContext> ctx = ArgumentCaptor.forClass(AgentContext.class);
        verify(executor).execute(eq(chatClient), ctx.capture());
        assertThat(ctx.getValue().originalInput()).isEqualTo("where is auth");
    }

    @Test
    void nullQuestionBecomesEmptyInput() {
        agent.ask(null);

        ArgumentCaptor<AgentContext> ctx = ArgumentCaptor.forClass(AgentContext.class);
        verify(executor).execute(eq(chatClient), ctx.capture());
        assertThat(ctx.getValue().originalInput()).isEmpty();
    }

    @Test
    void usesSharedSessionIdAcrossCalls() {
        agent.ask("first");
        agent.ask("second");

        ArgumentCaptor<AgentContext> ctx = ArgumentCaptor.forClass(AgentContext.class);
        verify(executor, org.mockito.Mockito.times(2)).execute(eq(chatClient), ctx.capture());
        assertThat(ctx.getAllValues().get(0).sessionId())
                .isEqualTo(ctx.getAllValues().get(1).sessionId())
                .isEqualTo(sessions.currentSessionId());
    }
}
