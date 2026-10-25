package com.codemind.retrieval;

import com.codemind.model.CodeChunk;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

@Component
@ConditionalOnProperty(name = "codemind.vector-store", havingValue = "memory")
public class InMemoryChunkStore implements ChunkStore {

    private static final String INDEX_DIR = ".codemind";
    private static final String INDEX_FILE = "index.bin";

    private final LocalEmbeddingService embeddingService;
    private final ChunkAnalytics analytics;
    private final List<IndexedCodeChunk> indexedChunks = new ArrayList<>();

    public InMemoryChunkStore(LocalEmbeddingService embeddingService, ChunkAnalytics analytics) {
        this.embeddingService = embeddingService;
        this.analytics = analytics;
    }

    @Override
    public synchronized void replaceAll(List<CodeChunk> newChunks) {
        indexedChunks.clear();
        indexedChunks.addAll(newChunks.stream()
                .map(chunk -> new IndexedCodeChunk(chunk, embeddingService.embed(indexText(chunk))))
                .toList());
    }

    @Override
    public synchronized void replaceAllAndPersist(List<CodeChunk> newChunks, Path root) {
        replaceAll(newChunks);
        persist(root);
    }

    @Override
    public synchronized List<CodeChunk> search(String query, int limit) {
        String normalized = query.toLowerCase(Locale.ROOT);
        float[] queryVector = embeddingService.embed(query);
        return indexedChunks.stream()
                .map(c -> new ScoredChunk(c.chunk(), hybridScore(c, normalized, queryVector, false, false, false)))
                .filter(s -> s.score > 0)
                .sorted(Comparator.comparingDouble(ScoredChunk::score).reversed())
                .limit(limit)
                .map(ScoredChunk::chunk)
                .toList();
    }

    @Override
    public synchronized List<CodeChunk> searchForCodebaseQuestion(String query, int limit) {
        String normalized = query.toLowerCase(Locale.ROOT);
        boolean apiQuestion = analytics.containsAny(normalized, "api", "apis", "endpoint", "controller", "route", "request");
        boolean flowQuestion = analytics.containsAny(normalized, "flow", "workflow", "logic", "feature", "features", "overall", "architecture");
        boolean integrationQuestion = analytics.containsAny(normalized, "microservice", "external", "call", "calls", "client", "integration", "kafka", "event", "rest", "feign", "publish", "subscribe");
        float[] queryVector = embeddingService.embed(query);

        return indexedChunks.stream()
                .map(c -> new ScoredChunk(c.chunk(), hybridScore(c, normalized, queryVector, apiQuestion, flowQuestion, integrationQuestion)))
                .filter(s -> s.score > 0)
                .sorted(Comparator.comparingDouble(ScoredChunk::score).reversed())
                .limit(limit)
                .map(ScoredChunk::chunk)
                .toList();
    }

    @Override
    public synchronized String summary() {
        return analytics.summary(indexedChunks.stream().map(IndexedCodeChunk::chunk).toList());
    }

    @Override
    public synchronized String navigationDigest(String query) {
        return analytics.navigationDigest(indexedChunks.stream().map(IndexedCodeChunk::chunk).toList(), query);
    }

    @Override
    public synchronized int size() {
        return indexedChunks.size();
    }

    @Override
    public synchronized boolean load(Path root) {
        Path indexPath = indexPath(root);
        if (!Files.exists(indexPath)) {
            return false;
        }
        try (ObjectInputStream input = new ObjectInputStream(Files.newInputStream(indexPath))) {
            Object value = input.readObject();
            if (!(value instanceof List<?> values)) {
                return false;
            }
            indexedChunks.clear();
            for (Object item : values) {
                if (item instanceof IndexedCodeChunk indexedChunk) {
                    indexedChunks.add(indexedChunk);
                }
            }
            return !indexedChunks.isEmpty();
        } catch (IOException | ClassNotFoundException e) {
            return false;
        }
    }

    private void persist(Path root) {
        try {
            Path indexPath = indexPath(root);
            Files.createDirectories(indexPath.getParent());
            IndexCacheLayout.markIgnored(indexPath.getParent());
            try (ObjectOutputStream output = new ObjectOutputStream(Files.newOutputStream(indexPath))) {
                output.writeObject(new ArrayList<>(indexedChunks));
            }
        } catch (IOException e) {
            System.out.println("Index persistence skipped: " + e.getMessage());
        }
    }

    private Path indexPath(Path root) {
        return root.toAbsolutePath().normalize().resolve(INDEX_DIR).resolve(INDEX_FILE);
    }

    private double hybridScore(IndexedCodeChunk indexedChunk, String query, float[] queryVector, boolean apiQuestion, boolean flowQuestion, boolean integrationQuestion) {
        CodeChunk chunk = indexedChunk.chunk();
        double vectorScore = embeddingService.cosine(indexedChunk.embedding(), queryVector) * 25.0;
        return vectorScore + analytics.scoreForIntent(chunk, query, apiQuestion, flowQuestion, integrationQuestion);
    }

    private String indexText(CodeChunk chunk) {
        return chunk.service() + "\n" + chunk.kind() + "\n" + chunk.file() + "\n" + chunk.content();
    }

    private record ScoredChunk(CodeChunk chunk, double score) {
    }
}
