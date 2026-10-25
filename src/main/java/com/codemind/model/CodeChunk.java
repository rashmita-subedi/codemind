package com.codemind.model;

import java.io.Serializable;

public record CodeChunk(String file, String service, String kind, int startLine, int endLine, String content) implements Serializable {
}
