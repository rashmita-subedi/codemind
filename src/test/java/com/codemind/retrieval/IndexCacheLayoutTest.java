package com.codemind.retrieval;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class IndexCacheLayoutTest {

    @Test
    void writesGitignoreThatIgnoresEverything(@TempDir Path dir) throws IOException {
        IndexCacheLayout.markIgnored(dir);

        Path gitignore = dir.resolve(".gitignore");
        assertThat(gitignore).exists();
        assertThat(Files.readString(gitignore)).contains("*");
    }

    @Test
    void leavesAnExistingGitignoreUntouched(@TempDir Path dir) throws IOException {
        Path gitignore = dir.resolve(".gitignore");
        Files.writeString(gitignore, "keep-me\n");

        IndexCacheLayout.markIgnored(dir);

        assertThat(Files.readString(gitignore)).isEqualTo("keep-me\n");
    }
}
