package com.codemind.agent;

import com.codemind.agent.IntentClassification.Args;
import com.codemind.agent.IntentClassification.ChainStep;
import com.codemind.agent.IntentClassification.Intent;
import com.codemind.agent.core.AgentResult;
import com.codemind.agent.core.ReActStep;
import com.codemind.service.PromptGuard;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class DirectorAgent {

    private static final double MIN_CONFIDENCE = 0.6;

    private final ChatClient chatClient;
    private final QnAAgent qna;
    private final RefactorAgent refactor;
    private final ChangeAnalyzerAgent change;
    private final PromptGuard guard;

    public DirectorAgent(@Qualifier("directorChatClient") ChatClient chatClient,
                         QnAAgent qna,
                         RefactorAgent refactor,
                         ChangeAnalyzerAgent change,
                         PromptGuard guard) {
        this.chatClient = chatClient;
        this.qna = qna;
        this.refactor = refactor;
        this.change = change;
        this.guard = guard;
    }

    public AgentResult dispatch(String userInput) {
        String sanitized = guard.sanitize(userInput);
        IntentClassification classification;
        try {
            classification = chatClient.prompt()
                    .user(sanitized)
                    .call()
                    .entity(IntentClassification.class);
        } catch (Exception e) {
            return message("Could not classify the request: " + e.getMessage()
                    + "\nTry `ask`, `refactor`, `commit`, or `changelog` directly.");
        }

        if (classification == null || classification.intent() == null) {
            return message("Could not understand the request. "
                    + "Try `ask`, `refactor`, `commit`, or `changelog` directly.");
        }
        if (classification.confidence() < MIN_CONFIDENCE) {
            return message("Not confident enough to route this (best guess: "
                    + classification.intent() + ", confidence "
                    + classification.confidence() + ").\n"
                    + "Rephrase, or use `ask`, `refactor`, `commit`, or `changelog` directly.");
        }
        return route(classification.intent(), classification.args(), classification.chain());
    }

    private AgentResult route(Intent intent, Args args, List<ChainStep> chain) {
        return switch (intent) {
            case QNA -> qna.ask(args == null ? "" : args.query());
            case REFACTOR -> {
                if (args == null || args.path() == null || args.path().isBlank()) {
                    yield message("Refactor needs a file path. Example: `do refactor src/Foo.java`.");
                }
                yield refactor.analyze(args.path());
            }
            case COMMIT -> change.generateCommitMessage();
            case CHANGELOG -> {
                if (args == null || args.range() == null || args.range().isBlank()) {
                    yield message("Changelog needs a git range. Example: `do changelog v1.0..HEAD`.");
                }
                yield change.generateChangelog(args.range(), args.plainOrFalse());
            }
            case COMPOUND -> runChain(chain);
        };
    }

    private AgentResult runChain(List<ChainStep> chain) {
        if (chain == null || chain.isEmpty()) {
            return message("Compound request had no steps to run.");
        }
        List<ReActStep> allSteps = new ArrayList<>();
        StringBuilder answer = new StringBuilder();
        long totalMs = 0L;
        boolean hitMax = false;
        int step = 0;
        for (ChainStep link : chain) {
            step++;
            if (link == null || link.intent() == null) {
                answer.append("## Step ").append(step).append(": skipped (unrecognised step)\n\n");
                continue;
            }
            AgentResult result = route(link.intent(), link.args(), null);
            answer.append("## Step ").append(step).append(": ").append(link.intent()).append('\n')
                    .append(result.answer()).append("\n\n");
            allSteps.addAll(result.steps());
            totalMs += result.totalDurationMs();
            hitMax = hitMax || result.hitMaxSteps();
        }
        return new AgentResult(answer.toString().strip(), List.copyOf(allSteps), allSteps.size(), totalMs, hitMax);
    }

    private static AgentResult message(String text) {
        return new AgentResult(text, List.of(), 0, 0L, false);
    }
}
