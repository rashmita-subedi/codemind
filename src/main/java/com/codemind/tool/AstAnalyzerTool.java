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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class AstAnalyzerTool {

    private static final long MAX_BYTES = 512_000L;
    private static final int BINARY_SNIFF_BYTES = 8_192;

    private static final Pattern JAVA_TYPE = Pattern.compile(
            "\\b(class|interface|enum|record)\\s+(\\w+)");
    private static final Pattern JAVA_METHOD = Pattern.compile(
            "\\b(?:public|private|protected|static|final|abstract|synchronized|\\s)*[\\w<>\\[\\],.?]+\\s+(\\w+)\\s*\\([^;]*\\)\\s*\\{");
    private static final Pattern PY_DEF = Pattern.compile(
            "^\\s*(class|def)\\s+(\\w+)");
    private static final Pattern JS_FUNC = Pattern.compile(
            "\\b(class|function)\\s+(\\w+)");
    private static final Pattern JS_ARROW = Pattern.compile(
            "\\b(?:const|let|var)\\s+(\\w+)\\s*=\\s*(?:async\\s*)?\\([^)]*\\)\\s*=>");

    @Tool(name = "ast_analyze", description = "Extract class and function declarations from a Java, Python, or JS/TS file via regex. Returns 'kind name (line N)' lines.")
    @SafeTool(SafetyLevel.SAFE)
    public String analyze(
            @ToolParam(description = "Path to the source file to analyze") String filePath
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

        String language = languageOf(filePath);
        if (language == null) {
            return "ERROR: unsupported language: " + extensionOf(filePath);
        }

        Loaded loaded = load(path);
        if (loaded.error() != null) {
            return loaded.error();
        }

        List<String> lines = loaded.text().lines().toList();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            extract(language, lines.get(i), i + 1, sb);
        }
        return sb.isEmpty() ? "No declarations found in " + filePath : sb.toString().stripTrailing();
    }

    private void extract(String language, String line, int lineNo, StringBuilder sb) {
        if (language.equals("java")) {
            emit(JAVA_TYPE, line, lineNo, sb, 1, 2);
            emitMethod(JAVA_METHOD, line, lineNo, sb);
        } else if (language.equals("python")) {
            emit(PY_DEF, line, lineNo, sb, 1, 2);
        } else {
            emit(JS_FUNC, line, lineNo, sb, 1, 2);
            emitArrow(JS_ARROW, line, lineNo, sb);
        }
    }

    private void emit(Pattern pattern, String line, int lineNo, StringBuilder sb, int kindGroup, int nameGroup) {
        Matcher m = pattern.matcher(line);
        if (m.find()) {
            sb.append("%s %s (line %d)%n".formatted(m.group(kindGroup), m.group(nameGroup), lineNo));
        }
    }

    private void emitMethod(Pattern pattern, String line, int lineNo, StringBuilder sb) {
        if (line.contains("class ") || line.contains("interface ") || line.contains("enum ")) {
            return;
        }
        Matcher m = pattern.matcher(line);
        if (m.find()) {
            sb.append("method %s (line %d)%n".formatted(m.group(1), lineNo));
        }
    }

    private void emitArrow(Pattern pattern, String line, int lineNo, StringBuilder sb) {
        Matcher m = pattern.matcher(line);
        if (m.find()) {
            sb.append("function %s (line %d)%n".formatted(m.group(1), lineNo));
        }
    }

    @Generated
    private Loaded load(Path path) {
        try {
            if (Files.size(path) > MAX_BYTES) {
                return new Loaded(null, "ERROR: file too large; cannot analyze.");
            }
            byte[] bytes = Files.readAllBytes(path);
            if (isBinary(bytes)) {
                return new Loaded(null, "ERROR: binary file, not analyzed: " + path);
            }
            return new Loaded(new String(bytes, StandardCharsets.UTF_8), null);
        } catch (IOException e) {
            return new Loaded(null, "ERROR: could not read " + path + ": " + e.getMessage());
        }
    }

    private static final Set<String> JS_EXTENSIONS = Set.of("js", "jsx", "ts", "tsx");

    private String languageOf(String filePath) {
        String ext = extensionOf(filePath);
        if (ext.equals("java")) {
            return "java";
        }
        if (ext.equals("py")) {
            return "python";
        }
        if (JS_EXTENSIONS.contains(ext)) {
            return "javascript";
        }
        return null;
    }

    private String extensionOf(String filePath) {
        int dot = filePath.lastIndexOf('.');
        return dot < 0 ? "" : filePath.substring(dot + 1).toLowerCase();
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

    private record Loaded(String text, String error) {
    }
}
