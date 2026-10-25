package com.codemind.service.io;

import com.codemind.cli.GitCommitter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class BackupLocatorTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void clearActiveRoot() {
        GitCommitter.setActiveRoot(null);
    }

    @AfterEach
    void resetActiveRoot() {
        GitCommitter.setActiveRoot(null);
    }

    @Test
    void findsRepoRootByWalkingUpForDotGit() throws IOException {
        Path repo = tempDir.resolve("repo");
        Files.createDirectories(repo.resolve(".git"));
        Path nested = repo.resolve("src").resolve("main").resolve("java");
        Files.createDirectories(nested);

        Path backupDir = BackupLocator.findBackupDir(nested);

        assertThat(backupDir).isEqualTo(repo.resolve(".codemind").resolve("backup"));
    }

    @Test
    void fallsBackToActiveRootWhenNoGitFound() {
        Path active = tempDir.resolve("workspace");
        GitCommitter.setActiveRoot(active);

        Path backupDir = BackupLocator.findBackupDir(tempDir.resolve("loose").resolve("file"));

        assertThat(backupDir).isEqualTo(active.resolve(".codemind").resolve("backup"));
    }

    @Test
    void fallsBackToCwdWhenNoGitAndNoActiveRoot() {
        Path backupDir = BackupLocator.findBackupDir(tempDir.resolve("loose"));

        Path expected = Path.of(System.getProperty("user.dir")).resolve(".codemind").resolve("backup");
        assertThat(backupDir).isEqualTo(expected);
    }

    @Test
    void doesNotLatchOntoGitAtUserHome() throws IOException {
        // Simulates a developer with a stray ~/.git: the walk must not return $HOME as the repo.
        Path fakeHome = tempDir.resolve("fakeHome");
        Path nested = fakeHome.resolve("AppData").resolve("Local").resolve("Temp").resolve("junit").resolve("file");
        Files.createDirectories(nested.getParent());
        Files.createDirectories(fakeHome.resolve(".git"));

        Path active = tempDir.resolve("workspace");
        GitCommitter.setActiveRoot(active);

        String original = System.getProperty("user.home");
        System.setProperty("user.home", fakeHome.toString());
        try {
            Path backupDir = BackupLocator.findBackupDir(nested);
            assertThat(backupDir).isEqualTo(active.resolve(".codemind").resolve("backup"));
        } finally {
            System.setProperty("user.home", original);
        }
    }

    @Test
    void nullAnchorFallsThroughToActiveRootOrCwd() {
        Path active = tempDir.resolve("workspace");
        GitCommitter.setActiveRoot(active);

        Path backupDir = BackupLocator.findBackupDir(null);

        assertThat(backupDir).isEqualTo(active.resolve(".codemind").resolve("backup"));
    }
}
