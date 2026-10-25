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

class ChangeAnalyzerAgentTest {

    private ChatClient commitClient;
    private ChatClient engineerClient;
    private ChatClient plainClient;
    private AgentExecutor executor;
    private ChangeAnalyzerAgent agent;
    private final AgentResult canned = new AgentResult("out", List.of(), 0, 0L, false);

    @BeforeEach
    void setUp() {
        commitClient = mock(ChatClient.class);
        engineerClient = mock(ChatClient.class);
        plainClient = mock(ChatClient.class);
        executor = mock(AgentExecutor.class);
        agent = new ChangeAnalyzerAgent(commitClient, engineerClient, plainClient, executor,
                new PromptGuard(new PromptGuardConfig(), new AuditService()), new SessionManager());
        when(executor.execute(any(), any())).thenReturn(canned);
    }

    @Test
    void commitUsesCommitClient() {
        AgentResult result = agent.generateCommitMessage();

        assertThat(result).isSameAs(canned);
        ArgumentCaptor<AgentContext> ctx = ArgumentCaptor.forClass(AgentContext.class);
        verify(executor).execute(eq(commitClient), ctx.capture());
        assertThat(ctx.getValue().originalInput()).contains("staged");
    }

    @Test
    void changelogEngineerModeUsesEngineerClient() {
        agent.generateChangelog("v1.0..HEAD", false);

        ArgumentCaptor<AgentContext> ctx = ArgumentCaptor.forClass(AgentContext.class);
        verify(executor).execute(eq(engineerClient), ctx.capture());
        assertThat(ctx.getValue().originalInput()).contains("v1.0..HEAD");
    }

    @Test
    void changelogPlainModeUsesPlainClient() {
        agent.generateChangelog("v1.0..HEAD", true);

        verify(executor).execute(eq(plainClient), any());
    }

    @Test
    void nullRangeSanitizesToEmpty() {
        agent.generateChangelog(null, false);

        ArgumentCaptor<AgentContext> ctx = ArgumentCaptor.forClass(AgentContext.class);
        verify(executor).execute(eq(engineerClient), ctx.capture());
        assertThat(ctx.getValue().originalInput()).endsWith(": ");
    }
}
