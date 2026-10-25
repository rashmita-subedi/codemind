package com.codemind.service;

import com.codemind.cli.GitCommitter;
import com.codemind.cli.Spinner;
import com.codemind.model.CodeChunk;
import com.codemind.retrieval.ChunkStore;
import com.codemind.retrieval.LanguagePatterns;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

@Service
public class IndexService {

    private static final int CHUNK_SIZE_LINES = 30;
    private final ChunkStore chunkStore;
    private final LanguagePatterns patterns;
    private Path currentRoot;

    public IndexService(ChunkStore chunkStore, LanguagePatterns patterns) {
        this.chunkStore = chunkStore;
        this.patterns = patterns;
    }

    /**
     * Returns the most recently indexed (or loaded) repository root, or {@code null}
     * if the vector store has not been populated this session.
     */
    public Path currentRoot() {
        return currentRoot;
    }

    public void index(Path root) {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        if (!Files.exists(normalizedRoot)) {
            throw new IllegalArgumentException("Path does not exist: " + normalizedRoot);
        }
        if (!Files.isDirectory(normalizedRoot)) {
            throw new IllegalArgumentException("Path is not a directory: " + normalizedRoot);
        }

        List<CodeChunk> chunks = new ArrayList<>();
        IndexStats stats = new IndexStats();
        try (Spinner spinner = new Spinner("Scanning files...")) {
            Files.walkFileTree(normalizedRoot, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    return patterns.isIgnoredPath(dir.toString()) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (!Files.isRegularFile(file) || !patterns.isCodeFile(file.toString())) {
                        return FileVisitResult.CONTINUE;
                    }
                    if (attrs.size() > patterns.getMaxFileSizeBytes()) {
                        stats.oversize++;
                        return FileVisitResult.CONTINUE;
                    }
                    List<CodeChunk> fileChunks = chunk(normalizedRoot, file);
                    chunks.addAll(fileChunks);
                    stats.files++;
                    spinner.update("Scanning files... " + stats.files + " files, " + chunks.size() + " chunks");
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    stats.skipped++;
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new RuntimeException("Failed to index repository " + normalizedRoot + ": " + e.getMessage(), e);
        }
        long startNanos = System.nanoTime();
        chunkStore.replaceAllAndPersist(chunks, normalizedRoot);
        currentRoot = normalizedRoot;
        GitCommitter.setActiveRoot(normalizedRoot);
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
        System.out.printf("Indexed %d chunks from %d files in %s (%.1fs)", chunks.size(), stats.files, normalizedRoot, elapsedMs / 1000.0);
        if (stats.skipped > 0) {
            System.out.printf(" (skipped %d unreadable paths)", stats.skipped);
        }
        if (stats.oversize > 0) {
            System.out.printf(" (skipped %d files larger than %d bytes)", stats.oversize, patterns.getMaxFileSizeBytes());
        }
        System.out.println();
    }

    public void indexCurrentDirectory() {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        if (chunkStore.load(root)) {
            currentRoot = root;
            GitCommitter.setActiveRoot(root);
            System.out.printf("Loaded persisted index with %d chunks from %s%n", chunkStore.size(), root.resolve(".codemind"));
            return;
        }
        index(root);
    }

    public void load(Path root) {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        if (!chunkStore.load(normalizedRoot)) {
            throw new IllegalArgumentException("No persisted index found at " + normalizedRoot.resolve(".codemind"));
        }
        currentRoot = normalizedRoot;
        GitCommitter.setActiveRoot(normalizedRoot);
        System.out.printf("Loaded persisted index with %d chunks from %s%n", chunkStore.size(), normalizedRoot.resolve(".codemind"));
    }

    private List<CodeChunk> chunk(Path root, Path path) {
        try {
            List<String> lines = Files.readAllLines(path);
            List<CodeChunk> chunks = new ArrayList<>();
            int start = 1;
            while (start <= lines.size()) {
                int end = Math.min(start + CHUNK_SIZE_LINES - 1, lines.size());
                String content = String.join("\n", lines.subList(start - 1, end));
                chunks.add(new CodeChunk(path.toString(), serviceName(root, path), kindFor(path, content), start, end, content));
                start = end + 1;
            }
            return chunks;
        } catch (IOException e) {
            return List.of();
        }
    }

    private String serviceName(Path root, Path path) {
        Path relative = root.relativize(path);
        if (relative.getNameCount() <= 1) {
            return root.getFileName().toString();
        }
        String first = relative.getName(0).toString();
        if ("src".equalsIgnoreCase(first)) {
            return root.getFileName().toString();
        }
        return first;
    }

    private String kindFor(Path path, String content) {
        String pathStr = path.toString();
        if (patterns.isContractFile(pathStr)) {
            return "contract";
        }
        String classified = patterns.classify(pathStr + "\n" + content);
        if ("code".equals(classified)
                && (pathStr.toLowerCase().endsWith(".yaml")
                || pathStr.toLowerCase().endsWith(".yml")
                || pathStr.toLowerCase().endsWith(".properties")
                || pathStr.toLowerCase().endsWith(".toml"))) {
            return "config";
        }
        return classified;
    }

    private static class IndexStats {
        private int files;
        private int skipped;
        private int oversize;
    }
}
