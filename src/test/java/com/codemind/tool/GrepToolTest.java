package com.codemind.tool;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class GrepToolTest {

    @TempDir
    Path tempDir;

    private GrepTool tool;

    @BeforeEach
    void setUp() {
        tool = new GrepTool();
    }

    @Test
    void findsMatchesWithPathLineAndTrimmedContent() throws IOException {
        Files.writeString(tempDir.resolve("A.java"), "class A {\n    int x = todo();\n}");

        String result = tool.grep(tempDir.toString(), "todo");

        assertThat(result).contains("A.java:2: int x = todo();");
    }

    @Test
    void searchesASingleFile() throws IOException {
        Path file = tempDir.resolve("notes.txt");
        Files.writeString(file, "alpha\nbeta\ngamma");

        String result = tool.grep(file.toString(), "beta");

        assertThat(result).contains("notes.txt:2: beta");
    }

    @Test
    void noMatchesReturnsMessage() throws IOException {
        Files.writeString(tempDir.resolve("A.java"), "class A {}");

        String result = tool.grep(tempDir.toString(), "nonexistent");

        assertThat(result).startsWith("No matches for /nonexistent/");
    }

    @Test
    void invalidRegexReturnsError() throws IOException {
        Files.writeString(tempDir.resolve("A.java"), "class A {}");

        String result = tool.grep(tempDir.toString(), "[unclosed");

        assertThat(result).startsWith("ERROR: invalid regex:");
    }

    @Test
    void skipsBinaryFiles() throws IOException {
        Files.write(tempDir.resolve("data.bin"), new byte[]{(byte) 'h', 'i', 0, (byte) 'x'});
        Files.writeString(tempDir.resolve("text.txt"), "hi there");

        String result = tool.grep(tempDir.toString(), "hi");

        assertThat(result).contains("text.txt:1:").doesNotContain("data.bin");
    }

    @Test
    void skipsNoiseDirectories() throws IOException {
        Files.createDirectory(tempDir.resolve(".git"));
        Files.writeString(tempDir.resolve(".git/config"), "target match");
        Files.writeString(tempDir.resolve("real.txt"), "target match");

        String result = tool.grep(tempDir.toString(), "target");

        assertThat(result).contains("real.txt").doesNotContain(".git");
    }

    @Test
    void enforcesMatchCap() throws IOException {
        StringBuilder content = new StringBuilder();
        for (int i = 0; i < 60; i++) {
            content.append("match line ").append(i).append('\n');
        }
        Files.writeString(tempDir.resolve("big.txt"), content.toString());

        String result = tool.grep(tempDir.toString(), "match");

        assertThat(result).contains("[50-match cap reached]");
    }

    @Test
    void skipsLargeFiles() throws IOException {
        Path big = tempDir.resolve("big.txt");
        byte[] data = new byte[512_001];
        java.util.Arrays.fill(data, (byte) 'a');
        Files.write(big, data);

        String result = tool.grep(tempDir.toString(), "a");

        assertThat(result).startsWith("No matches");
    }

    @Test
    void blankPathReturnsError() {
        assertThat(tool.grep("  ", "x")).isEqualTo("ERROR: pathOrDir is empty.");
    }

    @Test
    void nullPathReturnsError() {
        assertThat(tool.grep(null, "x")).isEqualTo("ERROR: pathOrDir is empty.");
    }

    @Test
    void blankRegexReturnsError() {
        assertThat(tool.grep(tempDir.toString(), "  ")).isEqualTo("ERROR: regex is empty.");
    }

    @Test
    void nullRegexReturnsError() {
        assertThat(tool.grep(tempDir.toString(), null)).isEqualTo("ERROR: regex is empty.");
    }

    @Test
    void missingPathReturnsError() {
        Path missing = tempDir.resolve("nope");

        assertThat(tool.grep(missing.toString(), "x")).startsWith("ERROR: path not found:");
    }
}
