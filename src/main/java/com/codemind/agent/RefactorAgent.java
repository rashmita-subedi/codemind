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
public class RefactorAgent {

    private final ChatClient analyzeChatClient;
    private final ChatClient applyChatClient;
    private final AgentExecutor executor;
    private final PromptGuard guard;
    private final SessionManager sessions;

    public RefactorAgent(@Qualifier("refactorAnalyzeChatClient") ChatClient analyzeChatClient,
                         @Qualifier("refactorApplyChatClient") ChatClient applyChatClient,
                         AgentExecutor executor,
                         PromptGuard guard,
                         SessionManager sessions) {
        this.analyzeChatClient = analyzeChatClient;
        this.applyChatClient = applyChatClient;
        this.executor = executor;
        this.guard = guard;
        this.sessions = sessions;
    }

    public AgentResult analyze(String path) {
        String sanitized = guard.sanitize(path);
        String task = "Analyse and propose a behaviour-preserving refactor for: " + sanitized;
        return executor.execute(analyzeChatClient, new AgentContext(sessions.currentSessionId(), task));
    }

    public AgentResult apply(String path) {
        return apply(path, null);
    }

    /**
     * Runs the apply stage with the analysis output from a prior {@link #analyze(String)}
     * call included verbatim as the approved Phase-1 plan. The apply system prompt expects
     * "the list of Phase-1 changes from the approved plan"; without this the LLM has to
     * re-analyse from scratch and usually produces nothing actionable.
     */
    public AgentResult apply(String path, String approvedPlan) {
        String sanitized = guard.sanitize(path);
        StringBuilder task = new StringBuilder("Apply the Phase-1 safe refactor to: ").append(sanitized);
        if (approvedPlan != null && !approvedPlan.isBlank()) {
            task.append("\n\nApproved Phase-1 plan (from the prior analysis turn):\n")
                    .append("<<UNTRUSTED>>\n")
                    .append(guard.sanitize(approvedPlan))
                    .append("\n<<END_UNTRUSTED>>");
        }
        return executor.execute(applyChatClient,
                new AgentContext(sessions.currentSessionId(), task.toString(), 20));
    }
}
