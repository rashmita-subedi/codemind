package com.codemind.tool;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class FileReaderToolTest {

    @TempDir
    Path tempDir;

    private FileReaderTool tool;

    @BeforeEach
    void setUp() {
        tool = new FileReaderTool();
    }

    private Path write(String name, String content) throws IOException {
        Path p = tempDir.resolve(name);
        Files.writeString(p, content);
        return p;
    }

    @Test
    void readsWholeFileWithLineNumbers() throws IOException {
        Path file = write("Hello.java", "public class Hello {\n    void hi() {}\n}");

        String result = tool.readFile(file.toString(), 0, 0);

        assertThat(result)
                .contains("     1\tpublic class Hello {")
                .contains("     2\t    void hi() {}")
                .contains("     3\t}");
    }

    @Test
    void readsInclusiveLineRange() throws IOException {
        Path file = write("Multi.txt", "a\nb\nc\nd\ne");

        String result = tool.readFile(file.toString(), 2, 4);

        assertThat(result).contains("\tb").contains("\tc").contains("\td");
        assertThat(result).doesNotContain("\ta").doesNotContain("\te");
    }

    @Test
    void startLineZeroWithEndLineReadsFromTop() throws IOException {
        Path file = write("Multi.txt", "a\nb\nc");

        String result = tool.readFile(file.toString(), 0, 2);

        assertThat(result).contains("\ta").contains("\tb").doesNotContain("\tc");
    }

    @Test
    void endLineZeroWithStartLineReadsToBottom() throws IOException {
        Path file = write("Multi.txt", "a\nb\nc");

        String result = tool.readFile(file.toString(), 2, 0);

        assertThat(result).doesNotContain("\ta");
        assertThat(result).contains("\tb").contains("\tc");
    }

    @Test
    void reversedRangeIsSwapped() throws IOException {
        Path file = write("Multi.txt", "a\nb\nc\nd");

        String result = tool.readFile(file.toString(), 3, 1);

        assertThat(result).contains("\ta").contains("\tb").contains("\tc");
        assertThat(result).doesNotContain("\td");
    }

    @Test
    void rangeOutOfBoundsIsClamped() throws IOException {
        Path file = write("Multi.txt", "a\nb");

        String result = tool.readFile(file.toString(), 5, 99);

        assertThat(result).contains("     2\tb");
    }

    @Test
    void emptyFilePathReturnsError() {
        assertThat(tool.readFile("   ", 0, 0)).isEqualTo("ERROR: filePath is empty.");
    }

    @Test
    void nullFilePathReturnsError() {
        assertThat(tool.readFile(null, 0, 0)).isEqualTo("ERROR: filePath is empty.");
    }

    @Test
    void missingFileReturnsError() {
        Path missing = tempDir.resolve("nope.txt");

        assertThat(tool.readFile(missing.toString(), 0, 0))
                .startsWith("ERROR: file not found:");
    }

    @Test
    void directoryReturnsError() {
        assertThat(tool.readFile(tempDir.toString(), 0, 0))
                .startsWith("ERROR: path is a directory");
    }

    @Test
    void largeFileIsGuarded() throws IOException {
        Path big = tempDir.resolve("big.txt");
        Files.write(big, new byte[512_001]);

        assertThat(tool.readFile(big.toString(), 0, 0))
                .startsWith("ERROR: file too large");
    }

    @Test
    void binaryFileIsRejected() throws IOException {
        Path bin = tempDir.resolve("data.bin");
        Files.write(bin, new byte[]{1, 2, 0, 4, 5});

        assertThat(tool.readFile(bin.toString(), 0, 0))
                .startsWith("ERROR: binary file");
    }

    @Test
    void emptyFileReturnsPlaceholder() throws IOException {
        Path empty = write("empty.txt", "");

        assertThat(tool.readFile(empty.toString(), 0, 0)).isEqualTo("(empty file)");
    }
}
