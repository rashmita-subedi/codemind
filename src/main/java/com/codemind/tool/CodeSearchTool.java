package com.codemind.tool;

import com.codemind.model.CodeChunk;
import com.codemind.policy.SafeTool;
import com.codemind.policy.SafetyLevel;
import com.codemind.retrieval.ChunkStore;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
public class CodeSearchTool {

    private static final int DEFAULT_TOP_K = 8;
    private static final int MAX_CONTENT_CHARS = 1200;

    private final ChunkStore chunkStore;

    public CodeSearchTool(ChunkStore chunkStore) {
        this.chunkStore = chunkStore;
    }

    @Tool(name = "code_search", description = "Semantic + lexical search over the indexed codebase. Returns the top matching code chunks with file path, line range, service/module, and kind. Use this before reading any specific file.")
    @SafeTool(SafetyLevel.SAFE)
    public String search(
            @ToolParam(description = "Natural-language query describing the code or behaviour you want to find") String query,
            @ToolParam(description = "Maximum number of chunks to return (1-20). Use 8 unless you need broader coverage.") int topK
    ) {
        if (query == null || query.isBlank()) {
            return "ERROR: query is empty.";
        }
        int limit = clamp(topK, 1, 20);
        List<CodeChunk> hits = chunkStore.searchForCodebaseQuestion(query, limit);
        if (hits.isEmpty()) {
            return "No matching chunks found for query: " + query;
        }
        return hits.stream()
                .map(this::format)
                .collect(Collectors.joining("\n\n---\n\n"));
    }

    @Tool(name = "navigation_map", description = "Show a deterministic map of indexed APIs, services, integrations, and data access points without calling the LLM. Use to orient yourself when the indexed codebase is large or when semantic search is too narrow.")
    @SafeTool(SafetyLevel.SAFE)
    public String navigationMap(
            @ToolParam(description = "Topic hint to filter the map. Use the empty string for a general overview.") String topicHint
    ) {
        String query = topicHint == null || topicHint.isBlank()
                ? "api flow integration service repository"
                : topicHint;
        return chunkStore.navigationDigest(query);
    }

    private String format(CodeChunk hit) {
        String content = hit.content();
        if (content.length() > MAX_CONTENT_CHARS) {
            content = content.substring(0, MAX_CONTENT_CHARS) + "\n... [truncated]";
        }
        return "[%s/%s] %s:%d-%d%n%s".formatted(
                hit.service(),
                hit.kind(),
                hit.file(),
                hit.startLine(),
                hit.endLine(),
                content
        );
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
