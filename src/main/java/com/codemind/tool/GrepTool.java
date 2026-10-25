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
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;

@Component
public class GrepTool {

    private static final int MAX_MATCHES = 50;
    private static final int BINARY_SNIFF_BYTES = 8_192;
    private static final long MAX_FILE_BYTES = 512_000L;
    private static final Set<String> SKIP_DIRS = Set.of(
            ".git", "node_modules", "target", "build", ".idea", "dist", ".codemind");

    @Tool(name = "grep", description = "Regex search across files under a path (file or directory). Returns relativePath:line: matchedLine, capped at 50 matches. Skips binary and noise directories.")
    @SafeTool(SafetyLevel.SAFE)
    public String grep(
            @ToolParam(description = "File or directory to search") String pathOrDir,
            @ToolParam(description = "Java regular expression to match against each line") String regex
    ) {
        if (pathOrDir == null || pathOrDir.isBlank()) {
            return "ERROR: pathOrDir is empty.";
        }
        if (regex == null || regex.isBlank()) {
            return "ERROR: regex is empty.";
        }
        Path root = Path.of(pathOrDir);
        if (!Files.exists(root)) {
            return "ERROR: path not found: " + pathOrDir;
        }

        Pattern pattern;
        try {
            pattern = Pattern.compile(regex);
        } catch (PatternSyntaxException e) {
            return "ERROR: invalid regex: " + e.getMessage();
        }

        StringBuilder sb = new StringBuilder();
        int matches = 0;
        for (Path file : walkFiles(root)) {
            byte[] bytes = read(file);
            if (bytes.length == 0 || isBinary(bytes)) {
                continue;
            }
            String relative = relativize(root, file);
            List<String> lines = new String(bytes, StandardCharsets.UTF_8).lines().toList();
            for (int i = 0; i < lines.size(); i++) {
                if (pattern.matcher(lines.get(i)).find()) {
                    sb.append("%s:%d: %s%n".formatted(relative, i + 1, lines.get(i).strip()));
                    if (++matches >= MAX_MATCHES) {
                        sb.append("... [").append(MAX_MATCHES).append("-match cap reached]\n");
                        return sb.toString().stripTrailing();
                    }
                }
            }
        }

        if (matches == 0) {
            return "No matches for /" + regex + "/ under " + pathOrDir;
        }
        return sb.toString().stripTrailing();
    }

    @Generated
    private List<Path> walkFiles(Path root) {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk
                    .filter(Files::isRegularFile)
                    .filter(p -> !isInSkippedDir(root, p))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    @Generated
    private byte[] read(Path file) {
        try {
            if (Files.size(file) > MAX_FILE_BYTES) {
                return new byte[0];
            }
            return Files.readAllBytes(file);
        } catch (IOException e) {
            return new byte[0];
        }
    }

    private boolean isInSkippedDir(Path root, Path file) {
        Path relative = root.relativize(file);
        for (Path part : relative) {
            if (SKIP_DIRS.contains(part.toString())) {
                return true;
            }
        }
        return false;
    }

    private String relativize(Path root, Path file) {
        Path base = Files.isDirectory(root) ? root : root.getParent();
        return base.relativize(file).toString().replace('\\', '/');
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
}
