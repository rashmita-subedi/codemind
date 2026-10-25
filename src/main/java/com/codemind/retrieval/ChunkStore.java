package com.codemind.retrieval;

import com.codemind.model.CodeChunk;

import java.nio.file.Path;
import java.util.List;

public interface ChunkStore {

    void replaceAll(List<CodeChunk> newChunks);

    void replaceAllAndPersist(List<CodeChunk> newChunks, Path root);

    List<CodeChunk> search(String query, int limit);

    List<CodeChunk> searchForCodebaseQuestion(String query, int limit);

    String summary();

    String navigationDigest(String query);

    int size();

    boolean load(Path root);
}
