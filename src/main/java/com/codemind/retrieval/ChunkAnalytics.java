package com.codemind.retrieval;

import com.codemind.model.CodeChunk;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class ChunkAnalytics {

    private final LanguagePatterns patterns;

    public ChunkAnalytics(LanguagePatterns patterns) {
        this.patterns = patterns;
    }

    public String summary(Collection<CodeChunk> chunks) {
        Map<String, Long> byService = chunks.stream()
                .collect(Collectors.groupingBy(CodeChunk::service, Collectors.counting()));
        Map<String, Long> byKind = chunks.stream()
                .collect(Collectors.groupingBy(CodeChunk::kind, Collectors.counting()));
        return "Indexed chunks: " + chunks.size()
                + "\nServices/modules: " + byService
                + "\nChunk kinds: " + byKind;
    }

    public String navigationDigest(Collection<CodeChunk> chunks, String query) {
        String normalized = query.toLowerCase(Locale.ROOT);
        List<String> apiLines = matchingLines(chunks, "api", normalized, 30);
        List<String> serviceLines = matchingLines(chunks, "service", normalized, 30);
        List<String> integrationLines = matchingLines(chunks, "integration", normalized, 40);
        List<String> dataLines = matchingLines(chunks, "data", normalized, 25);
        List<String> configLines = configLines(chunks, 30);

        return """
                API map:
                %s

                Service/business map:
                %s

                Outbound integrations/events/config:
                %s

                Data/repository map:
                %s

                Runtime/config hints:
                %s
                """.formatted(
                section(apiLines),
                section(serviceLines),
                section(integrationLines),
                section(dataLines),
                section(configLines)
        );
    }

    public int scoreForIntent(CodeChunk chunk, String query, boolean apiQuestion, boolean flowQuestion, boolean integrationQuestion) {
        int score = lexicalScore(chunk.content(), query);
        String file = chunk.file().toLowerCase(Locale.ROOT);
        String content = chunk.content().toLowerCase(Locale.ROOT);

        if (apiQuestion && "api".equals(chunk.kind())) {
            score += 8;
        }
        if (apiQuestion && contentMatchesKind(content, "api")) {
            score += 6;
        }
        if (flowQuestion && ("api".equals(chunk.kind()) || "service".equals(chunk.kind())
                || "data".equals(chunk.kind()) || "integration".equals(chunk.kind()))) {
            score += 5;
        }
        if (integrationQuestion && "integration".equals(chunk.kind())) {
            score += 12;
        }
        if (integrationQuestion && contentMatchesKind(content, "integration")) {
            score += 10;
        }
        if (flowQuestion && fileNameSuggestsRole(file)) {
            score += 4;
        }
        if (containsAny(query, "all", "full", "whole", "overall", "list")) {
            score += Math.max(1, 4 - chunk.service().length() % 4);
        }
        return score;
    }

    public int lexicalScore(String content, String query) {
        String normalized = content.toLowerCase(Locale.ROOT);
        int score = 0;
        for (String term : query.split("\\s+")) {
            if (term.length() < 2) {
                continue;
            }
            if (normalized.contains(term)) {
                score++;
            }
        }
        return score;
    }

    public boolean containsAny(String value, String... terms) {
        for (String term : terms) {
            if (value.contains(term)) {
                return true;
            }
        }
        return false;
    }

    public boolean contentMatchesKind(String lowerContent, String kind) {
        for (String keyword : patterns.keywordsFor(kind)) {
            if (lowerContent.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private boolean fileNameSuggestsRole(String lowerFilePath) {
        return containsAny(lowerFilePath,
                "controller", "service", "handler", "usecase", "repository",
                "dao", "client", "consumer", "producer", "router", "route");
    }

    private List<String> matchingLines(Collection<CodeChunk> chunks, String kind, String query, int limit) {
        return chunks.stream()
                .filter(chunk -> kind.equals(chunk.kind()))
                .flatMap(chunk -> interestingLines(chunk, query).stream())
                .distinct()
                .limit(limit)
                .toList();
    }

    private List<String> configLines(Collection<CodeChunk> chunks, int limit) {
        return chunks.stream()
                .filter(chunk -> chunk.file().endsWith(".yml")
                        || chunk.file().endsWith(".yaml")
                        || chunk.file().endsWith(".properties")
                        || chunk.file().endsWith(".toml")
                        || chunk.file().endsWith(".json"))
                .flatMap(chunk -> interestingLines(chunk, "url host topic bootstrap client base-url endpoint").stream())
                .distinct()
                .limit(limit)
                .toList();
    }

    private List<String> interestingLines(CodeChunk chunk, String query) {
        List<String> lines = chunk.content().lines().toList();
        List<String> result = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).strip();
            if (line.isBlank()) {
                continue;
            }
            String lower = line.toLowerCase(Locale.ROOT);
            if (patterns.isNavigationLine(lower) || queryMatches(lower, query)) {
                result.add("%s:%d [%s/%s] %s".formatted(
                        chunk.file(),
                        chunk.startLine() + i,
                        chunk.service(),
                        chunk.kind(),
                        line
                ));
            }
            if (result.size() >= 12) {
                break;
            }
        }
        return result;
    }

    private boolean queryMatches(String lower, String query) {
        for (String term : query.split("\\W+")) {
            if (term.length() > 2 && lower.contains(term)) {
                return true;
            }
        }
        return false;
    }

    private String section(List<String> lines) {
        if (lines.isEmpty()) {
            return "- No direct evidence found.";
        }
        return lines.stream().map(line -> "- " + line).collect(Collectors.joining("\n"));
    }
}
