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
public class ChangeAnalyzerAgent {

    private final ChatClient commitChatClient;
    private final ChatClient changelogEngineerChatClient;
    private final ChatClient changelogPlainChatClient;
    private final AgentExecutor executor;
    private final PromptGuard guard;
    private final SessionManager sessions;

    public ChangeAnalyzerAgent(@Qualifier("changeAnalyzerChatClient") ChatClient commitChatClient,
                               @Qualifier("changelogEngineerChatClient") ChatClient changelogEngineerChatClient,
                               @Qualifier("changelogPlainChatClient") ChatClient changelogPlainChatClient,
                               AgentExecutor executor,
                               PromptGuard guard,
                               SessionManager sessions) {
        this.commitChatClient = commitChatClient;
        this.changelogEngineerChatClient = changelogEngineerChatClient;
        this.changelogPlainChatClient = changelogPlainChatClient;
        this.executor = executor;
        this.guard = guard;
        this.sessions = sessions;
    }

    public AgentResult generateCommitMessage() {
        String task = "Generate a single Conventional Commits message for the staged changes.";
        return executor.execute(commitChatClient, new AgentContext(sessions.currentSessionId(), task));
    }

    public AgentResult generateChangelog(String range, boolean plain) {
        String sanitized = guard.sanitize(range);
        String task = "Generate a changelog for the git revision range: " + sanitized;
        ChatClient chatClient = plain ? changelogPlainChatClient : changelogEngineerChatClient;
        return executor.execute(chatClient, new AgentContext(sessions.currentSessionId(), task));
    }
}
