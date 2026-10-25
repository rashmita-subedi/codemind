package com.codemind.retrieval;

import com.codemind.model.CodeChunk;

import java.io.Serializable;

public record IndexedCodeChunk(CodeChunk chunk, float[] embedding) implements Serializable {
}
