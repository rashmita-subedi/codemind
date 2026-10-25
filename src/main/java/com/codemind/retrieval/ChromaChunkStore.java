package com.codemind.retrieval;

import com.codemind.cli.Spinner;
import com.codemind.model.CodeChunk;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

@Component
@ConditionalOnProperty(name = "codemind.vector-store", havingValue = "chroma", matchIfMissing = true)
public class ChromaChunkStore implements ChunkStore {

    private static final String INDEX_DIR = ".codemind";
    private static final String CHUNKS_FILE = "chunks.bin";
    private static final int RERANK_MULTIPLIER = 4;

    private final VectorStore vectorStore;
    private final ChunkAnalytics analytics;
    private final int embedParallelism;
    private final int batchSize;
    private final Map<String, CodeChunk> cache = new LinkedHashMap<>();

    public ChromaChunkStore(VectorStore vectorStore,
                            ChunkAnalytics analytics,
                            @Value("${codemind.embed.parallelism:4}") int embedParallelism,
                            @Value("${codemind.embed.batch-size:16}") int batchSize) {
        this.vectorStore = vectorStore;
        this.analytics = analytics;
        this.embedParallelism = Math.max(1, embedParallelism);
        this.batchSize = Math.max(1, batchSize);
    }

    @Override
    public synchronized void replaceAll(List<CodeChunk> newChunks) {
        if (!cache.isEmpty()) {
            try {
                vectorStore.delete(new ArrayList<>(cache.keySet()));
            } catch (Exception e) {
                System.out.println("WARN: failed to delete prior Chroma entries: " + e.getMessage());
            }
            cache.clear();
        }

        List<List<Document>> batches = new ArrayList<>();
        List<Document> current = new ArrayList<>(batchSize);
        for (CodeChunk chunk : newChunks) {
            String id = idFor(chunk);
            cache.put(id, chunk);
            current.add(new Document(id, indexText(chunk), metadataFor(chunk)));
            if (current.size() >= batchSize) {
                batches.add(current);
                current = new ArrayList<>(batchSize);
            }
        }
        if (!current.isEmpty()) {
            batches.add(current);
        }

        int total = newChunks.size();
        AtomicInteger processed = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        Semaphore inflight = new Semaphore(embedParallelism);
        try (Spinner spinner = new Spinner("Embedding 0/" + total + " chunks (parallelism=" + embedParallelism + ")...");
             ExecutorService executor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("codemind-embed-", 0).factory())) {
            List<Future<?>> futures = new ArrayList<>(batches.size());
            for (List<Document> batch : batches) {
                inflight.acquire();
                futures.add(executor.submit(() -> {
                    try {
                        vectorStore.add(batch);
                        int done = processed.addAndGet(batch.size());
                        spinner.update("Embedding " + done + "/" + total + " chunks (parallelism=" + embedParallelism + ")...");
                    } catch (Exception e) {
                        failed.addAndGet(batch.size());
                        System.out.println("\nWARN: batch of " + batch.size() + " failed to embed: " + e.getMessage());
                    } finally {
                        inflight.release();
                    }
                }));
            }
            for (Future<?> f : futures) {
                try {
                    f.get();
                } catch (Exception e) {
                    // already logged inside the task
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Embedding interrupted", e);
        }
        if (failed.get() > 0) {
            System.out.println("WARN: " + failed.get() + " of " + total + " chunks were not embedded; rerun `index` to retry.");
        }
    }

    @Override
    public synchronized void replaceAllAndPersist(List<CodeChunk> newChunks, Path root) {
        replaceAll(newChunks);
        persist(root);
    }

    @Override
    public synchronized List<CodeChunk> search(String query, int limit) {
        return runSearch(query, limit, false, false, false);
    }

    @Override
    public synchronized List<CodeChunk> searchForCodebaseQuestion(String query, int limit) {
        String normalized = query.toLowerCase(Locale.ROOT);
        boolean apiQuestion = analytics.containsAny(normalized, "api", "apis", "endpoint", "controller", "route", "request");
        boolean flowQuestion = analytics.containsAny(normalized, "flow", "workflow", "logic", "feature", "features", "overall", "architecture");
        boolean integrationQuestion = analytics.containsAny(normalized, "microservice", "external", "call", "calls", "client", "integration", "kafka", "event", "rest", "feign", "publish", "subscribe");
        return runSearch(query, limit, apiQuestion, flowQuestion, integrationQuestion);
    }

    @Override
    public synchronized String summary() {
        return analytics.summary(cache.values());
    }

    @Override
    public synchronized String navigationDigest(String query) {
        return analytics.navigationDigest(cache.values(), query);
    }

    @Override
    public synchronized int size() {
        return cache.size();
    }

    @Override
    public synchronized boolean load(Path root) {
        Path chunksPath = chunksPath(root);
        if (!Files.exists(chunksPath)) {
            return false;
        }
        try (ObjectInputStream input = new ObjectInputStream(Files.newInputStream(chunksPath))) {
            Object value = input.readObject();
            if (!(value instanceof List<?> values)) {
                return false;
            }
            cache.clear();
            for (Object item : values) {
                if (item instanceof CodeChunk chunk) {
                    cache.put(idFor(chunk), chunk);
                }
            }
            if (cache.isEmpty()) {
                return false;
            }
            probeChromaForChunks();
            return true;
        } catch (IOException | ClassNotFoundException e) {
            return false;
        }
    }

    private List<CodeChunk> runSearch(String query, int limit, boolean apiQuestion, boolean flowQuestion, boolean integrationQuestion) {
        if (cache.isEmpty()) {
            return List.of();
        }
        int fetchSize = Math.max(limit, limit * RERANK_MULTIPLIER);
        List<Document> hits;
        try {
            hits = vectorStore.similaritySearch(SearchRequest.builder()
                    .query(query)
                    .topK(fetchSize)
                    .build());
        } catch (Exception e) {
            System.out.println("WARN: Chroma similarity search failed (" + e.getMessage() + "); falling back to lexical scoring.");
            hits = List.of();
        }
        if (hits == null) {
            hits = List.of();
        }

        Map<String, Double> vectorRank = new HashMap<>();
        int rank = 0;
        for (Document doc : hits) {
            String id = doc.getId();
            if (id != null && !vectorRank.containsKey(id)) {
                vectorRank.put(id, (double) (hits.size() - rank));
            }
            rank++;
        }

        String normalizedQuery = query.toLowerCase(Locale.ROOT);
        List<ScoredChunk> scored = new ArrayList<>(cache.size());
        for (Map.Entry<String, CodeChunk> entry : cache.entrySet()) {
            CodeChunk chunk = entry.getValue();
            double vectorScore = vectorRank.getOrDefault(entry.getKey(), 0.0);
            int intentScore = analytics.scoreForIntent(chunk, normalizedQuery, apiQuestion, flowQuestion, integrationQuestion);
            double combined = vectorScore * 1.5 + intentScore;
            if (combined > 0) {
                scored.add(new ScoredChunk(chunk, combined));
            }
        }
        return scored.stream()
                .sorted(Comparator.comparingDouble(ScoredChunk::score).reversed())
                .limit(limit)
                .map(ScoredChunk::chunk)
                .toList();
    }

    private void probeChromaForChunks() {
        try {
            List<Document> probe = vectorStore.similaritySearch(SearchRequest.builder()
                    .query("module function class")
                    .topK(1)
                    .build());
            if (probe == null || probe.isEmpty()) {
                System.out.println("WARN: Chroma collection appears empty for cached chunks. Run `index <path>` to repopulate.");
            }
        } catch (Exception e) {
            System.out.println("WARN: Could not verify Chroma collection (" + e.getMessage() + "). Run `index <path>` if searches return no results.");
        }
    }

    private void persist(Path root) {
        try {
            Path chunksPath = chunksPath(root);
            Files.createDirectories(chunksPath.getParent());
            IndexCacheLayout.markIgnored(chunksPath.getParent());
            try (ObjectOutputStream output = new ObjectOutputStream(Files.newOutputStream(chunksPath))) {
                output.writeObject(new ArrayList<>(cache.values()));
            }
        } catch (IOException e) {
            System.out.println("Chunk cache persistence skipped: " + e.getMessage());
        }
    }

    private Path chunksPath(Path root) {
        return root.toAbsolutePath().normalize().resolve(INDEX_DIR).resolve(CHUNKS_FILE);
    }

    private Map<String, Object> metadataFor(CodeChunk chunk) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("file", chunk.file());
        metadata.put("service", chunk.service());
        metadata.put("kind", chunk.kind());
        metadata.put("startLine", chunk.startLine());
        metadata.put("endLine", chunk.endLine());
        return metadata;
    }

    private String indexText(CodeChunk chunk) {
        return chunk.service() + "\n" + chunk.kind() + "\n" + chunk.file() + "\n" + chunk.content();
    }

    private String idFor(CodeChunk chunk) {
        return chunk.file().replace('\\', '/') + ":" + chunk.startLine() + "-" + chunk.endLine();
    }

    private record ScoredChunk(CodeChunk chunk, double score) {
    }
}
