package com.codemind.agent;

import com.codemind.agent.core.AgentContext;
import com.codemind.agent.core.AgentExecutor;
import com.codemind.agent.core.AgentResult;
import com.codemind.memory.SessionManager;
import com.codemind.service.PromptGuard;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class QnAAgent {

    private final ChatClient chatClient;
    private final AgentExecutor executor;
    private final PromptGuard guard;
    private final SessionManager sessions;

    public QnAAgent(@Qualifier("qnaChatClient") ChatClient chatClient,
                    AgentExecutor executor,
                    PromptGuard guard,
                    SessionManager sessions) {
        this.chatClient = chatClient;
        this.executor = executor;
        this.guard = guard;
        this.sessions = sessions;
    }

    public AgentResult ask(String query) {
        String sanitized = guard.sanitize(query);
        return executor.execute(chatClient, new AgentContext(sessions.currentSessionId(), sanitized));
    }
}
