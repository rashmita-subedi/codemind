package com.codemind.service.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AtomicFileWriterTest {

    @TempDir
    Path tempDir;

    @Test
    void writeStringCreatesFileWithExactContent() throws IOException {
        Path target = tempDir.resolve("a.txt");

        AtomicFileWriter.writeString(target, "hello world");

        assertThat(target).exists().content().isEqualTo("hello world");
    }

    @Test
    void writeStringOverwritesExistingFile() throws IOException {
        Path target = tempDir.resolve("a.txt");
        Files.writeString(target, "old");

        AtomicFileWriter.writeString(target, "new");

        assertThat(target).content().isEqualTo("new");
    }

    @Test
    void writeStringWithNullProducesEmptyFile() throws IOException {
        Path target = tempDir.resolve("empty.txt");

        AtomicFileWriter.writeString(target, null);

        assertThat(target).exists().content().isEqualTo("");
    }

    @Test
    void writeBytesPreservesBinaryPayload() throws IOException {
        Path target = tempDir.resolve("bin.dat");
        byte[] payload = new byte[]{0x00, 0x01, 0x02, (byte) 0xFF};

        AtomicFileWriter.writeBytes(target, payload);

        assertThat(Files.readAllBytes(target)).isEqualTo(payload);
    }

    @Test
    void writeStringCreatesMissingParentDirectories() throws IOException {
        Path target = tempDir.resolve("nested").resolve("deep").resolve("a.txt");

        AtomicFileWriter.writeString(target, "deep");

        assertThat(target).exists().content().isEqualTo("deep");
    }

    @Test
    void writeStringFailsWhenParentIsAFile() throws IOException {
        Path blocker = tempDir.resolve("blocker");
        Files.writeString(blocker, "i am a file");
        Path target = blocker.resolve("child.txt");

        assertThatThrownBy(() -> AtomicFileWriter.writeString(target, "x"))
                .isInstanceOf(IOException.class);
    }

    @Test
    void writeStringEncodesUtf8() throws IOException {
        Path target = tempDir.resolve("utf8.txt");

        AtomicFileWriter.writeString(target, "café");

        assertThat(Files.readString(target, StandardCharsets.UTF_8)).isEqualTo("café");
    }
}
