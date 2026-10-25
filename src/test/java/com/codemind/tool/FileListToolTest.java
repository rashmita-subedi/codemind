package com.codemind.tool;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class FileListToolTest {

    @TempDir
    Path tempDir;

    private FileListTool tool;

    @BeforeEach
    void setUp() {
        tool = new FileListTool();
    }

    @Test
    void listsFilesAndDirectoriesWithDirsFirst() throws IOException {
        Files.createDirectory(tempDir.resolve("src"));
        Files.writeString(tempDir.resolve("README.md"), "hi");
        Files.writeString(tempDir.resolve("src/Main.java"), "class Main {}");

        String result = tool.listFiles(tempDir.toString(), 0);

        assertThat(result).contains("src/").contains("README.md").contains("  Main.java");
        assertThat(result.indexOf("src/")).isLessThan(result.indexOf("README.md"));
    }

    @Test
    void depthOneDoesNotRecurse() throws IOException {
        Files.createDirectory(tempDir.resolve("src"));
        Files.writeString(tempDir.resolve("src/Deep.java"), "x");

        String result = tool.listFiles(tempDir.toString(), 1);

        assertThat(result).contains("src/").doesNotContain("Deep.java");
    }

    @Test
    void defaultDepthStopsAtThreeLevels() throws IOException {
        Files.createDirectories(tempDir.resolve("a/b/c"));
        Files.writeString(tempDir.resolve("a/b/c/Deep.txt"), "x");
        Files.writeString(tempDir.resolve("a/Shallow.txt"), "y");

        String result = tool.listFiles(tempDir.toString(), 0);

        assertThat(result).contains("a/").contains("b/").contains("Shallow.txt");
        assertThat(result).doesNotContain("Deep.txt");
    }

    @Test
    void largeMaxDepthIsClampedButStillLists() throws IOException {
        Files.createDirectory(tempDir.resolve("src"));
        Files.writeString(tempDir.resolve("src/Main.java"), "x");

        String result = tool.listFiles(tempDir.toString(), 100);

        assertThat(result).contains("src/").contains("Main.java");
    }

    @Test
    void skipsNoiseDirectories() throws IOException {
        Files.createDirectory(tempDir.resolve(".git"));
        Files.createDirectory(tempDir.resolve("node_modules"));
        Files.createDirectory(tempDir.resolve("kept"));
        Files.writeString(tempDir.resolve(".git/config"), "x");

        String result = tool.listFiles(tempDir.toString(), 0);

        assertThat(result).contains("kept/");
        assertThat(result).doesNotContain(".git").doesNotContain("node_modules");
    }

    @Test
    void emptyDirectoryReturnsPlaceholder() {
        assertThat(tool.listFiles(tempDir.toString(), 0)).isEqualTo("(empty directory)");
    }

    @Test
    void truncatesAtEntryCap() throws IOException {
        for (int i = 0; i < 501; i++) {
            Files.writeString(tempDir.resolve("f" + i + ".txt"), "x");
        }

        String result = tool.listFiles(tempDir.toString(), 1);

        assertThat(result).contains("[truncated at 500 entries]");
    }

    @Test
    void truncationPropagatesFromNestedDirectory() throws IOException {
        Path sub = Files.createDirectory(tempDir.resolve("sub"));
        for (int i = 0; i < 501; i++) {
            Files.writeString(sub.resolve("f" + i + ".txt"), "x");
        }

        String result = tool.listFiles(tempDir.toString(), 3);

        assertThat(result).contains("sub/").contains("[truncated at 500 entries]");
    }

    @Test
    void blankPathReturnsError() {
        assertThat(tool.listFiles("  ", 0)).isEqualTo("ERROR: dirPath is empty.");
    }

    @Test
    void nullPathReturnsError() {
        assertThat(tool.listFiles(null, 0)).isEqualTo("ERROR: dirPath is empty.");
    }

    @Test
    void missingPathReturnsError() {
        Path missing = tempDir.resolve("nope");

        assertThat(tool.listFiles(missing.toString(), 0)).startsWith("ERROR: directory not found:");
    }

    @Test
    void fileInsteadOfDirectoryReturnsError() throws IOException {
        Path file = tempDir.resolve("a.txt");
        Files.writeString(file, "x");

        assertThat(tool.listFiles(file.toString(), 0)).startsWith("ERROR: path is not a directory:");
    }
}
