package com.codemind.tool;

import com.codemind.policy.SafeTool;
import com.codemind.policy.SafetyLevel;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@Component
public class FileReaderTool {

    private static final long MAX_BYTES = 512_000L;
    private static final int BINARY_SNIFF_BYTES = 8_192;

    @Tool(name = "read_file", description = "Read a file's content with line numbers. Pass startLine=0 and endLine=0 to read the whole file, or a 1-based inclusive line range.")
    @SafeTool(SafetyLevel.SAFE)
    public String readFile(
            @ToolParam(description = "Path to the file to read") String filePath,
            @ToolParam(description = "1-based start line, or 0 for the whole file") int startLine,
            @ToolParam(description = "1-based inclusive end line, or 0 for the whole file") int endLine
    ) {
        if (filePath == null || filePath.isBlank()) {
            return "ERROR: filePath is empty.";
        }
        Path path = Path.of(filePath);
        if (!Files.exists(path)) {
            return "ERROR: file not found: " + filePath;
        }
        if (Files.isDirectory(path)) {
            return "ERROR: path is a directory, not a file: " + filePath;
        }

        Loaded loaded = load(path);
        if (loaded.error() != null) {
            return loaded.error();
        }

        List<String> lines = loaded.text().lines().toList();
        if (lines.isEmpty()) {
            return "(empty file)";
        }

        int from;
        int to;
        if (startLine == 0 && endLine == 0) {
            from = 1;
            to = lines.size();
        } else {
            int lo = startLine == 0 ? 1 : startLine;
            int hi = endLine == 0 ? lines.size() : endLine;
            if (lo > hi) {
                int tmp = lo;
                lo = hi;
                hi = tmp;
            }
            from = clamp(lo, 1, lines.size());
            to = clamp(hi, 1, lines.size());
        }

        StringBuilder sb = new StringBuilder();
        for (int i = from; i <= to; i++) {
            sb.append("%6d\t%s%n".formatted(i, lines.get(i - 1)));
        }
        return sb.toString().stripTrailing();
    }

    @Generated
    private Loaded load(Path path) {
        try {
            long size = Files.size(path);
            if (size > MAX_BYTES) {
                return new Loaded(null, "ERROR: file too large (" + size + " bytes); use a line range or GrepTool.");
            }
            byte[] bytes = Files.readAllBytes(path);
            if (isBinary(bytes)) {
                return new Loaded(null, "ERROR: binary file, not shown: " + path);
            }
            return new Loaded(new String(bytes, StandardCharsets.UTF_8), null);
        } catch (IOException e) {
            return new Loaded(null, "ERROR: could not read " + path + ": " + e.getMessage());
        }
    }

    private boolean isBinary(byte[] bytes) {
        int limit = Math.min(bytes.length, BINARY_SNIFF_BYTES);
        for (int i = 0; i < limit; i++) {
            if (bytes[i] == 0) {
                return true;
            }
        }
        return false;
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private record Loaded(String text, String error) {
    }
}
