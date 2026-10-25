package com.codemind.tool;

import com.codemind.policy.SafeTool;
import com.codemind.policy.SafetyLevel;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

@Component
public class FileListTool {

    private static final int DEFAULT_DEPTH = 3;
    private static final int MAX_DEPTH = 10;
    private static final int MAX_ENTRIES = 500;
    private static final Set<String> SKIP_DIRS = Set.of(
            ".git", "node_modules", "target", "build", ".idea", "dist", ".codemind");

    @Tool(name = "file_list", description = "List a directory as an indented tree up to a max depth. Skips .git, node_modules, target, build, and other noise directories.")
    @SafeTool(SafetyLevel.SAFE)
    public String listFiles(
            @ToolParam(description = "Path to the directory to list") String dirPath,
            @ToolParam(description = "Maximum tree depth (1-10). Use 0 for the default of 3.") int maxDepth
    ) {
        if (dirPath == null || dirPath.isBlank()) {
            return "ERROR: dirPath is empty.";
        }
        Path root = Path.of(dirPath);
        if (!Files.exists(root)) {
            return "ERROR: directory not found: " + dirPath;
        }
        if (!Files.isDirectory(root)) {
            return "ERROR: path is not a directory: " + dirPath;
        }

        int depth = maxDepth <= 0 ? DEFAULT_DEPTH : Math.min(maxDepth, MAX_DEPTH);
        StringBuilder sb = new StringBuilder();
        int[] count = {0};
        boolean truncated = walk(root, 0, depth, sb, count);

        if (count[0] == 0) {
            return "(empty directory)";
        }
        if (truncated) {
            sb.append("... [truncated at ").append(MAX_ENTRIES).append(" entries]\n");
        }
        return sb.toString().stripTrailing();
    }

    private boolean walk(Path dir, int level, int maxDepth, StringBuilder sb, int[] count) {
        for (Path child : children(dir)) {
            String name = child.getFileName().toString();
            boolean isDir = Files.isDirectory(child);
            if (isDir && SKIP_DIRS.contains(name)) {
                continue;
            }
            if (count[0] >= MAX_ENTRIES) {
                return true;
            }
            sb.append("  ".repeat(level)).append(name).append(isDir ? "/" : "").append('\n');
            count[0]++;
            if (isDir && level + 1 < maxDepth) {
                if (walk(child, level + 1, maxDepth, sb, count)) {
                    return true;
                }
            }
        }
        return false;
    }

    @Generated
    private List<Path> children(Path dir) {
        try (var stream = Files.list(dir)) {
            return stream
                    .sorted(Comparator
                            .comparing((Path p) -> Files.isDirectory(p) ? 0 : 1)
                            .thenComparing(p -> p.getFileName().toString().toLowerCase()))
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }
}
