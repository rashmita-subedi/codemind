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

class RefactorAgentTest {

    private ChatClient analyzeClient;
    private ChatClient applyClient;
    private AgentExecutor executor;
    private RefactorAgent agent;
    private final AgentResult canned = new AgentResult("plan", List.of(), 0, 0L, false);

    @BeforeEach
    void setUp() {
        analyzeClient = mock(ChatClient.class);
        applyClient = mock(ChatClient.class);
        executor = mock(AgentExecutor.class);
        agent = new RefactorAgent(analyzeClient, applyClient, executor,
                new PromptGuard(new PromptGuardConfig(), new AuditService()), new SessionManager());
        when(executor.execute(any(), any())).thenReturn(canned);
    }

    @Test
    void analyzeUsesAnalyzeClientAndEmbedsPath() {
        AgentResult result = agent.analyze("src/Foo.java");

        assertThat(result).isSameAs(canned);
        ArgumentCaptor<AgentContext> ctx = ArgumentCaptor.forClass(AgentContext.class);
        verify(executor).execute(eq(analyzeClient), ctx.capture());
        assertThat(ctx.getValue().originalInput()).contains("Analyse").contains("src/Foo.java");
    }

    @Test
    void applyUsesApplyClientAndEmbedsPath() {
        AgentResult result = agent.apply("src/Foo.java");

        assertThat(result).isSameAs(canned);
        ArgumentCaptor<AgentContext> ctx = ArgumentCaptor.forClass(AgentContext.class);
        verify(executor).execute(eq(applyClient), ctx.capture());
        assertThat(ctx.getValue().originalInput()).contains("Apply").contains("src/Foo.java");
    }

    @Test
    void nullPathSanitizesToEmpty() {
        agent.analyze(null);

        ArgumentCaptor<AgentContext> ctx = ArgumentCaptor.forClass(AgentContext.class);
        verify(executor).execute(eq(analyzeClient), ctx.capture());
        assertThat(ctx.getValue().originalInput()).endsWith(": ");
    }
}
