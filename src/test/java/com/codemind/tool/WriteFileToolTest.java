package com.codemind.tool;

import com.codemind.policy.ActionRequest;
import com.codemind.service.ApprovalService;
import com.codemind.service.AuditService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentMatchers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WriteFileToolTest {

    @TempDir
    Path tempDir;

    private ApprovalService approvals;
    private AuditService audit;
    private WriteFileTool tool;

    @BeforeEach
    void setUp() {
        approvals = mock(ApprovalService.class);
        audit = mock(AuditService.class);
        tool = new WriteFileTool(approvals, audit);
    }

    // why: only remove the backup/ subtree this test creates — never the whole .codemind
    // directory, which holds tracked files (e.g. chunks.bin) unrelated to these tests.
    @AfterEach
    void cleanupBackups() throws IOException {
        Path backupDir = Path.of(".codemind", "backup");
        if (Files.exists(backupDir)) {
            try (Stream<Path> walk = Files.walk(backupDir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    @Test
    void writesNewFileWhenApproved() {
        when(approvals.approved(ArgumentMatchers.any(ActionRequest.class))).thenReturn(true);
        Path target = tempDir.resolve("New.java");

        String result = tool.writeFile(target.toString(), "class New {}");

        assertThat(result).contains("(new file, no backup)");
        assertThat(target).exists().content().isEqualTo("class New {}");
        verify(audit).record("write_file.applied", target.toString());
    }

    @Test
    void overwritesAndBacksUpExistingFileWhenApproved() throws IOException {
        when(approvals.approved(ArgumentMatchers.any(ActionRequest.class))).thenReturn(true);
        Path target = tempDir.resolve("Old.java");
        Files.writeString(target, "old content");

        String result = tool.writeFile(target.toString(), "new content");

        assertThat(result).contains("backup at");
        assertThat(target).content().isEqualTo("new content");
        verify(audit).record("write_file.applied", target.toString());

        Path backupDir = Path.of(".codemind", "backup");
        assertThat(Files.list(backupDir)).isNotEmpty();
    }

    @Test
    void declinedWriteLeavesFileUntouched() throws IOException {
        when(approvals.approved(ArgumentMatchers.any(ActionRequest.class))).thenReturn(false);
        Path target = tempDir.resolve("Keep.java");
        Files.writeString(target, "original");

        String result = tool.writeFile(target.toString(), "should not be written");

        assertThat(result).isEqualTo("User declined the write to " + target);
        assertThat(target).content().isEqualTo("original");
        verify(audit).record("write_file.rejected", target.toString());
        verify(audit, never()).record(ArgumentMatchers.eq("write_file.applied"), ArgumentMatchers.anyString());
    }

    @Test
    void longContentPreviewIsTruncated() {
        when(approvals.approved(ArgumentMatchers.any(ActionRequest.class))).thenReturn(true);
        Path target = tempDir.resolve("Big.txt");
        String big = "x".repeat(600);

        String result = tool.writeFile(target.toString(), big);

        assertThat(result).contains("Wrote 600 bytes");
        assertThat(target).content().hasSize(600);
    }

    @Test
    void nullContentWritesEmptyFile() {
        when(approvals.approved(ArgumentMatchers.any(ActionRequest.class))).thenReturn(true);
        Path target = tempDir.resolve("Empty.txt");

        String result = tool.writeFile(target.toString(), null);

        assertThat(result).contains("Wrote 0 bytes");
        assertThat(target).exists().content().isEqualTo("");
    }

    @Test
    void blankPathReturnsError() {
        assertThat(tool.writeFile("  ", "x")).isEqualTo("ERROR: filePath is empty.");
    }

    @Test
    void nullPathReturnsError() {
        assertThat(tool.writeFile(null, "x")).isEqualTo("ERROR: filePath is empty.");
    }

    @Test
    void directoryTargetReturnsError() {
        assertThat(tool.writeFile(tempDir.toString(), "x"))
                .startsWith("ERROR: path is a directory");
    }

    @Test
    void writeFailureIsReportedWhenParentIsAFile() throws IOException {
        when(approvals.approved(ArgumentMatchers.any(ActionRequest.class))).thenReturn(true);
        Path blocker = tempDir.resolve("blocker");
        Files.writeString(blocker, "i am a file, not a directory");
        Path target = blocker.resolve("child.txt");

        String result = tool.writeFile(target.toString(), "data");

        assertThat(result).startsWith("ERROR: write failed for");
        verify(audit).record("write_file.failed", target.toString());
    }
}
