package com.codemind.service;

import com.codemind.policy.ActionRequest;
import com.codemind.service.RollbackService.RestoredFile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentMatchers;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RollbackServiceTest {

    @TempDir
    Path tempDir;

    private Path backupDir;
    private Path workspace;
    private ApprovalService approvals;
    private AuditService audit;
    private RollbackService service;

    @BeforeEach
    void setUp() throws IOException {
        backupDir = tempDir.resolve("backup");
        workspace = tempDir.resolve("workspace");
        Files.createDirectories(backupDir);
        Files.createDirectories(workspace);
        approvals = mock(ApprovalService.class);
        audit = mock(AuditService.class);
        service = new RollbackService(approvals, audit, backupDir.toString());
    }

    @Test
    void noBackupsReturnsEmptyAndNeverPromptsApproval() {
        List<RestoredFile> restored = service.rollbackLatest(0);

        assertThat(restored).isEmpty();
        verify(approvals, never()).approved(ArgumentMatchers.any());
    }

    @Test
    void missingBackupDirReturnsEmpty() throws IOException {
        Files.delete(backupDir);

        List<RestoredFile> restored = service.rollbackLatest(0);

        assertThat(restored).isEmpty();
        verify(approvals, never()).approved(ArgumentMatchers.any());
    }

    @Test
    void restoresLatestClusterWhenApproved() throws IOException {
        Path origA = workspace.resolve("A.java");
        Path origB = workspace.resolve("sub").resolve("B.java");
        Files.createDirectories(origB.getParent());
        Files.writeString(origA, "current A");
        Files.writeString(origB, "current B");

        long now = System.nanoTime();
        writeBackup(origA, "old A", now);
        writeBackup(origB, "old B", now - TimeUnit.SECONDS.toNanos(2));

        when(approvals.approved(ArgumentMatchers.any(ActionRequest.class))).thenReturn(true);

        List<RestoredFile> restored = service.rollbackLatest(0);

        assertThat(restored).hasSize(2);
        assertThat(Files.readString(origA)).isEqualTo("old A");
        assertThat(Files.readString(origB)).isEqualTo("old B");
        verify(audit).record("rollback.restored", origA.toString());
        verify(audit).record("rollback.restored", origB.toString());
    }

    @Test
    void clusterWindowExcludesBackupsOlderThanFiveSeconds() throws IOException {
        Path origA = workspace.resolve("A.java");
        Path origB = workspace.resolve("B.java");
        Files.writeString(origA, "current A");
        Files.writeString(origB, "current B");

        long now = System.nanoTime();
        writeBackup(origA, "old A", now);
        writeBackup(origB, "ancient B", now - TimeUnit.SECONDS.toNanos(10));

        when(approvals.approved(ArgumentMatchers.any(ActionRequest.class))).thenReturn(true);

        List<RestoredFile> restored = service.rollbackLatest(0);

        assertThat(restored).hasSize(1);
        assertThat(Files.readString(origA)).isEqualTo("old A");
        assertThat(Files.readString(origB)).isEqualTo("current B");
    }

    @Test
    void explicitCountLimitsToNNewest() throws IOException {
        Path origA = workspace.resolve("A.java");
        Path origB = workspace.resolve("B.java");
        Path origC = workspace.resolve("C.java");
        Files.writeString(origA, "current A");
        Files.writeString(origB, "current B");
        Files.writeString(origC, "current C");

        long now = System.nanoTime();
        writeBackup(origA, "old A", now);
        writeBackup(origB, "older B", now - 1_000_000L);
        writeBackup(origC, "oldest C", now - 2_000_000L);

        when(approvals.approved(ArgumentMatchers.any(ActionRequest.class))).thenReturn(true);

        List<RestoredFile> restored = service.rollbackLatest(2);

        assertThat(restored).hasSize(2);
        assertThat(Files.readString(origA)).isEqualTo("old A");
        assertThat(Files.readString(origB)).isEqualTo("older B");
        assertThat(Files.readString(origC)).isEqualTo("current C");
    }

    @Test
    void declinedApprovalRestoresNothingAndAudits() throws IOException {
        Path origA = workspace.resolve("A.java");
        Files.writeString(origA, "current");
        writeBackup(origA, "old", System.nanoTime());

        when(approvals.approved(ArgumentMatchers.any(ActionRequest.class))).thenReturn(false);

        List<RestoredFile> restored = service.rollbackLatest(0);

        assertThat(restored).isEmpty();
        assertThat(Files.readString(origA)).isEqualTo("current");
        verify(audit).record("rollback.rejected", "1 files");
    }

    @Test
    void backupFilesAreNotDeletedAfterRestore() throws IOException {
        Path origA = workspace.resolve("A.java");
        Files.writeString(origA, "current");
        Path bak = writeBackup(origA, "old", System.nanoTime());
        when(approvals.approved(ArgumentMatchers.any(ActionRequest.class))).thenReturn(true);

        service.rollbackLatest(0);

        assertThat(bak).exists();
    }

    @Test
    void malformedBackupFilenamesAreIgnored() throws IOException {
        Files.writeString(backupDir.resolve("not_a_backup.txt"), "nope");
        Files.writeString(backupDir.resolve("missing-timestamp.bak"), "nope");

        Path origA = workspace.resolve("A.java");
        Files.writeString(origA, "current");
        writeBackup(origA, "old", System.nanoTime());
        when(approvals.approved(ArgumentMatchers.any(ActionRequest.class))).thenReturn(true);

        List<RestoredFile> restored = service.rollbackLatest(0);

        assertThat(restored).hasSize(1);
        assertThat(Files.readString(origA)).isEqualTo("old");
    }

    @Test
    void previewListsEveryFileToRestore() throws IOException {
        Path origA = workspace.resolve("A.java");
        Path origB = workspace.resolve("B.java");
        Files.writeString(origA, "current A");
        Files.writeString(origB, "current B");
        writeBackup(origA, "old A", System.nanoTime());
        writeBackup(origB, "old B", System.nanoTime() - 1_000L);

        when(approvals.approved(ArgumentMatchers.argThat(req -> {
            String preview = req.preview();
            return preview != null
                    && preview.contains(origA.toString())
                    && preview.contains(origB.toString())
                    && preview.contains("Will restore 2 file(s)");
        }))).thenReturn(true);

        List<RestoredFile> restored = service.rollbackLatest(0);

        assertThat(restored).hasSize(2);
    }

    private Path writeBackup(Path original, String content, long nanoTime) throws IOException {
        String encoded = com.codemind.service.io.BackupNaming.encode(original);
        Path bak = backupDir.resolve(encoded + "." + nanoTime + ".bak");
        Files.writeString(bak, content, StandardCharsets.UTF_8);
        return bak;
    }
}
