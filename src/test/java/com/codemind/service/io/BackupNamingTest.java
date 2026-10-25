package com.codemind.service.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class BackupNamingTest {

    @TempDir
    Path tempDir;

    @Test
    void encodeRelativizesPathInsideCwdAndReplacesSeparators() {
        Path cwd = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        Path target = cwd.resolve("src").resolve("main").resolve("Foo.java");

        String encoded = BackupNaming.encode(target);

        assertThat(encoded).isEqualTo("src__main__Foo.java");
    }

    @Test
    void encodeStripsColonOnAbsolutePathsOutsideCwd() {
        Path target = tempDir.resolve("Outside.java");

        String encoded = BackupNaming.encode(target);

        assertThat(encoded).doesNotContain(":");
        assertThat(encoded).doesNotContain("/");
        assertThat(encoded).doesNotContain("\\");
        assertThat(encoded).endsWith("__Outside.java");
    }

    @Test
    void decodeRoundTripsRelativePathInsideCwd() {
        Path cwd = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        Path original = cwd.resolve("src").resolve("Foo.java");
        String encoded = BackupNaming.encode(original);

        Path decoded = BackupNaming.decode(encoded);

        assertThat(decoded).isEqualTo(original.normalize());
    }

    @Test
    void decodeWithExplicitBaseResolvesAgainstBase() {
        Path base = tempDir;
        String encoded = "sub__File.java";

        Path decoded = BackupNaming.decode(encoded, base);

        assertThat(decoded).isEqualTo(base.resolve("sub").resolve("File.java").normalize());
    }

    @Test
    void decodeReturnsAbsoluteWhenEncodedStartsWithRootSlash() {
        String encoded = "__tmp__foo.txt";

        Path decoded = BackupNaming.decode(encoded, tempDir);

        // Leading "__" decodes to leading "/" → absolute on POSIX. We assert structural,
        // not absoluteness (Windows behaviour differs), via the path ending.
        assertThat(decoded.toString().replace('\\', '/')).endsWith("/tmp/foo.txt");
    }

    @Test
    void parseValidBackupFilenameReturnsEncodedPathAndNanoTime() {
        Optional<BackupNaming.Parsed> parsed = BackupNaming.parse("src__main__Foo.java.1234567890.bak");

        assertThat(parsed).isPresent();
        assertThat(parsed.get().encodedPath()).isEqualTo("src__main__Foo.java");
        assertThat(parsed.get().nanoTime()).isEqualTo(1234567890L);
    }

    @Test
    void parseRejectsFilenameWithoutBakSuffix() {
        assertThat(BackupNaming.parse("src__main__Foo.java.1234567890")).isEmpty();
    }

    @Test
    void parseRejectsFilenameWithoutNumericTimestamp() {
        assertThat(BackupNaming.parse("src__main__Foo.java.notanumber.bak")).isEmpty();
    }

    @Test
    void parseRejectsCompletelyMalformedFilename() {
        assertThat(BackupNaming.parse("garbage")).isEmpty();
    }

    @Test
    void parseHandlesPathsThatContainDots() {
        Optional<BackupNaming.Parsed> parsed = BackupNaming.parse("a.b.c.999.bak");

        assertThat(parsed).isPresent();
        assertThat(parsed.get().encodedPath()).isEqualTo("a.b.c");
        assertThat(parsed.get().nanoTime()).isEqualTo(999L);
    }
}
