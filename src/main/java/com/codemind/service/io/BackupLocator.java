package com.codemind.service.io;

import com.codemind.cli.GitCommitter;

import java.nio.file.Files;
import java.nio.file.Path;

public final class BackupLocator {

    private static final String INDEX_DIR = ".codemind";
    private static final String BACKUP_DIR = "backup";

    private BackupLocator() {
    }

    public static Path findBackupDir(Path anchor) {
        Path repo = findRepoRoot(anchor);
        if (repo != null) {
            return backupUnder(repo);
        }
        Path activeRoot = GitCommitter.activeRoot();
        if (activeRoot != null) {
            return backupUnder(activeRoot);
        }
        return backupUnder(Path.of(System.getProperty("user.dir")));
    }

    private static Path findRepoRoot(Path anchor) {
        if (anchor == null) {
            return null;
        }
        Path home = userHome();
        Path current = anchor.toAbsolutePath().normalize();
        while (current != null) {
            // why: a stray ~/.git (common from dotfile repos or accidental `git init`) would
            // otherwise hijack the walk for any anchor under $HOME — including JUnit @TempDir —
            // and route backups to $HOME/.codemind/backup instead of the active project.
            if (current.equals(home)) {
                return null;
            }
            if (Files.isDirectory(current.resolve(".git"))) {
                return current;
            }
            current = current.getParent();
        }
        return null;
    }

    private static Path userHome() {
        String home = System.getProperty("user.home");
        return home == null ? null : Path.of(home).toAbsolutePath().normalize();
    }

    private static Path backupUnder(Path root) {
        return root.resolve(INDEX_DIR).resolve(BACKUP_DIR);
    }
}
