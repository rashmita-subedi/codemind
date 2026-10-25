package com.codemind.service;

import com.codemind.policy.ActionRequest;
import com.codemind.policy.SafetyLevel;
import com.codemind.service.io.AtomicFileWriter;
import com.codemind.service.io.BackupLocator;
import com.codemind.service.io.BackupNaming;
import com.codemind.tool.Generated;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

@Service
public class RollbackService {

    private static final long CLUSTER_WINDOW_NANOS = TimeUnit.SECONDS.toNanos(5);
    private static final String DEFAULT_BACKUP_DIR = ".codemind/backup";

    private final ApprovalService approvals;
    private final AuditService audit;
    private final Path explicitBackupDir;

    public RollbackService(ApprovalService approvals, AuditService audit,
                           @Value("${codemind.backup.directory:" + DEFAULT_BACKUP_DIR + "}") String backupDirectory) {
        this.approvals = approvals;
        this.audit = audit;
        // why: the default cwd-relative ".codemind/backup" puts backups in whichever directory
        // the JVM was launched from, even when the indexed/refactored repo is elsewhere. When
        // the config sticks with the default, resolve dynamically against the current dir's
        // repo root at call time (see backupDir()). An explicit override is used as-is.
        this.explicitBackupDir = DEFAULT_BACKUP_DIR.equals(backupDirectory) ? null : Path.of(backupDirectory);
    }

    private Path backupDir() {
        if (explicitBackupDir != null) {
            return explicitBackupDir;
        }
        return BackupLocator.findBackupDir(Path.of(System.getProperty("user.dir")));
    }

    public List<RestoredFile> rollbackLatest(int n) {
        List<BackupRecord> backups = findBackups();
        if (backups.isEmpty()) {
            return List.of();
        }

        List<BackupRecord> selected = select(backups, n);
        if (selected.isEmpty()) {
            return List.of();
        }

        ActionRequest request = new ActionRequest(
                "rollback:" + selected.size() + " files",
                SafetyLevel.CONFIRM_REQUIRED,
                buildPreview(selected));
        if (!approvals.approved(request)) {
            audit.record("rollback.rejected", selected.size() + " files");
            return List.of();
        }

        List<RestoredFile> restored = new ArrayList<>();
        for (BackupRecord record : selected) {
            try {
                byte[] bytes = Files.readAllBytes(record.backupPath());
                AtomicFileWriter.writeBytes(record.originalPath(), bytes);
                restored.add(new RestoredFile(record.originalPath(), record.backupPath(), bytes.length));
                audit.record("rollback.restored", record.originalPath().toString());
            } catch (IOException e) {
                audit.record("rollback.failed", record.originalPath() + ": " + e.getMessage());
            }
        }
        return List.copyOf(restored);
    }

    private List<BackupRecord> findBackups() {
        Path dir = backupDir();
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> stream = listBackupDir(dir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .map(this::toRecord)
                    .filter(Objects::nonNull)
                    .sorted(Comparator.comparingLong(BackupRecord::nanoTime).reversed())
                    .toList();
        }
    }

    @Generated
    private Stream<Path> listBackupDir(Path dir) {
        try {
            return Files.list(dir);
        } catch (IOException e) {
            return Stream.empty();
        }
    }

    private BackupRecord toRecord(Path backupPath) {
        return BackupNaming.parse(backupPath.getFileName().toString())
                .map(parsed -> new BackupRecord(
                        BackupNaming.decode(parsed.encodedPath()),
                        backupPath,
                        parsed.nanoTime()))
                .orElse(null);
    }

    private List<BackupRecord> select(List<BackupRecord> sorted, int n) {
        if (n > 0) {
            return sorted.stream().limit(n).toList();
        }
        long newest = sorted.get(0).nanoTime();
        return sorted.stream()
                .filter(r -> (newest - r.nanoTime()) <= CLUSTER_WINDOW_NANOS)
                .toList();
    }

    private String buildPreview(List<BackupRecord> records) {
        StringBuilder sb = new StringBuilder("Will restore ").append(records.size()).append(" file(s):\n");
        for (BackupRecord r : records) {
            sb.append("  ").append(r.originalPath())
                    .append("  <- ").append(r.backupPath().getFileName())
                    .append('\n');
        }
        return sb.toString();
    }

    private record BackupRecord(Path originalPath, Path backupPath, long nanoTime) {
    }

    public record RestoredFile(Path originalPath, Path backupPath, long bytes) {
    }
}
