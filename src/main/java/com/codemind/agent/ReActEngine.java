package com.codemind.agent;

import com.codemind.cli.Spinner;
import com.codemind.memory.ConversationMemoryService;
import com.codemind.memory.SessionManager;
import com.codemind.model.CodeChunk;
import com.codemind.retrieval.ChunkStore;
import com.codemind.retrieval.LanguagePatterns;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

@Component
public class ReActEngine {

    private final ChunkStore chunkStore;
    private final LanguagePatterns patterns;
    private final ChatClient chatClient;
    private final ConversationMemoryService memory;
    private final SessionManager sessions;

    public ReActEngine(ChunkStore chunkStore, LanguagePatterns patterns, ChatClient.Builder chatClientBuilder,
                       ConversationMemoryService memory, SessionManager sessions) {
        this.chunkStore = chunkStore;
        this.patterns = patterns;
        this.chatClient = chatClientBuilder.build();
        this.memory = memory;
        this.sessions = sessions;
    }

    public ReActResult answer(String question) {
        String sessionId = sessions.currentSessionId();
        String priorTurns = memory.transcript(sessionId);
        List<ReActStep> steps = new ArrayList<>();
        steps.add(new ReActStep("Need relevant context", "semantic_search", "Searching indexed chunks"));
        List<CodeChunk> hits;
        try (Spinner spinner = new Spinner("Embedding query and searching vector store...")) {
            hits = chunkStore.searchForCodebaseQuestion(question, resultLimit(question));
        }
        steps.add(new ReActStep("Found candidate evidence", "read_chunk", "Retrieved " + hits.size() + " chunks"));
        if (hits.isEmpty()) {
            String miss = "No matching indexed context found. CodeMind auto-indexes the current directory on startup; use `index <path>` if your codebase is elsewhere.";
            memory.recordExchange(sessionId, question, miss);
            return new ReActResult(miss, steps);
        }

        String response;
        try (Spinner spinner = new Spinner("Generating answer with " + hits.size() + " chunks of context...")) {
            response = callModel(question, hits, true, priorTurns);
            if (response == null || response.isBlank()) {
                spinner.update("Model returned empty response, retrying with compact context...");
                steps.add(new ReActStep("Model returned empty response", "retry_compact_context", "Retrying with compact evidence"));
                response = callModel(question, hits.stream().limit(8).toList(), false, priorTurns);
            }
        }
        if (response == null || response.isBlank()) {
            steps.add(new ReActStep("Model returned empty response again", "fallback_summary", "Returning retrieved evidence summary"));
            response = fallbackSummary(question, hits);
        }

        memory.recordExchange(sessionId, question, response);
        steps.add(new ReActStep("Compose cited answer", "respond", "Returned file:line references"));
        return new ReActResult(response, steps);
    }

    private String callModel(String question, List<CodeChunk> hits, boolean detailed, String priorTurns) {
        String context = hits.stream()
                .map(hit -> formatChunk(hit, detailed))
                .collect(Collectors.joining("\n\n---\n\n"));

        return chatClient.prompt()
                .system("""
                        You are CodeMind, a senior codebase analyst.
                        Analyze enterprise codebases from retrieved context.
                        For API/flow questions, connect controller/router, service/use-case, repository/client, and configuration layers when visible.
                        Treat the deterministic code navigation map as high-priority evidence.
                        Do not claim there are no external or microservice calls when the navigation map contains clients, listeners, templates, URLs, topics, or integration classes.
                        Always cite file:line for concrete claims.
                        Keep the answer complete but compact.
                        If evidence is partial, say exactly what is missing.
                        """)
                .user("""
                        Conversation so far (oldest first; empty on a fresh session):
                        %s

                        Question:
                        %s

                        Index summary:
                        %s

                        Deterministic code navigation map:
                        %s

                        Retrieved evidence:
                        %s
                        """.formatted(priorTurns == null ? "" : priorTurns, question,
                                chunkStore.summary(), chunkStore.navigationDigest(question), context))
                .call()
                .content();
    }

    private String formatChunk(CodeChunk hit, boolean detailed) {
        String content = detailed ? truncate(hit.content(), 2400) : keyLines(hit.content());
        return """
                Service/module: %s
                Kind: %s
                File: %s:%d
                %s
                """.formatted(hit.service(), hit.kind(), hit.file(), hit.startLine(), content);
    }

    private String keyLines(String content) {
        return content.lines()
                .filter(line -> {
                    String lower = line.toLowerCase(Locale.ROOT);
                    if (patterns.isNavigationLine(lower)) {
                        return true;
                    }
                    return lower.contains("return ")
                            || lower.contains(".save")
                            || lower.contains(".find")
                            || lower.contains(".create")
                            || lower.contains(".update")
                            || lower.contains(".delete");
                })
                .limit(35)
                .collect(Collectors.joining("\n"));
    }

    private String truncate(String content, int maxChars) {
        if (content.length() <= maxChars) {
            return content;
        }
        return content.substring(0, maxChars) + "\n... [truncated]";
    }

    private int resultLimit(String question) {
        String lower = question.toLowerCase(Locale.ROOT);
        if (lower.contains("complete") || lower.contains("full") || lower.contains("flow") || lower.contains("all api") || lower.contains("all apis")) {
            return 16;
        }
        return 10;
    }

    private String fallbackSummary(String question, List<CodeChunk> hits) {
        String evidence = hits.stream()
                .limit(12)
                .map(hit -> "- [%s] %s:%d (%s)".formatted(hit.kind(), hit.file(), hit.startLine(), hit.service()))
                .collect(Collectors.joining("\n"));
        return """
                The model endpoint returned an empty response twice, so CodeMind is showing the retrieved evidence instead.

                Question: %s

                Deterministic code navigation map:
                %s

                Most relevant files:
                %s

                Try narrowing the question, for example: "explain loanProcess flow" or "trace LoanConfirm API".
                """.formatted(question, chunkStore.navigationDigest(question), evidence);
    }
}
