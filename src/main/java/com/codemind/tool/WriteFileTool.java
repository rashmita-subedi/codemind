package com.codemind.tool;

import com.codemind.policy.ActionRequest;
import com.codemind.policy.SafeTool;
import com.codemind.policy.SafetyLevel;
import com.codemind.service.ApprovalService;
import com.codemind.service.AuditService;
import com.codemind.service.io.AtomicFileWriter;
import com.codemind.service.io.BackupLocator;
import com.codemind.service.io.BackupNaming;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

@Component
public class WriteFileTool {

    private final ApprovalService approvals;
    private final AuditService audit;

    public WriteFileTool(ApprovalService approvals, AuditService audit) {
        this.approvals = approvals;
        this.audit = audit;
    }

    @Tool(name = "write_file", description = "Write content to a file. Backs up any existing file to .codemind/backup/ first, then writes atomically. Requires approval.")
    @SafeTool(SafetyLevel.CONFIRM_REQUIRED)
    public String writeFile(
            @ToolParam(description = "Path to the file to write") String filePath,
            @ToolParam(description = "Full new content of the file") String newContent
    ) {
        if (filePath == null || filePath.isBlank()) {
            return "ERROR: filePath is empty.";
        }
        String content = newContent == null ? "" : newContent;
        Path target = Path.of(filePath);
        if (Files.isDirectory(target)) {
            return "ERROR: path is a directory, not a file: " + filePath;
        }

        var request = new ActionRequest(
                "write_file:" + filePath,
                SafetyLevel.CONFIRM_REQUIRED,
                diffPreview(target, content));
        if (!approvals.approved(request)) {
            audit.record("write_file.rejected", filePath);
            return "User declined the write to " + filePath;
        }

        try {
            String backup = backup(target);
            atomicWrite(target, content);
            audit.record("write_file.applied", filePath);
            String suffix = backup == null ? " (new file, no backup)" : "; backup at " + backup;
            return "Wrote " + content.getBytes(StandardCharsets.UTF_8).length + " bytes to " + filePath + suffix;
        } catch (IOException e) {
            audit.record("write_file.failed", filePath);
            return "ERROR: write failed for " + filePath + ": " + e.getMessage();
        }
    }

    private String backup(Path target) throws IOException {
        if (!Files.exists(target)) {
            return null;
        }
        Path backupDir = BackupLocator.findBackupDir(target.toAbsolutePath().getParent());
        Files.createDirectories(backupDir);
        String name = BackupNaming.encode(target) + "." + System.nanoTime() + ".bak";
        Path backup = backupDir.resolve(name);
        Files.copy(target, backup, StandardCopyOption.REPLACE_EXISTING);
        return backup.toString();
    }

    private void atomicWrite(Path target, String content) throws IOException {
        AtomicFileWriter.writeString(target, content);
    }

    private String diffPreview(Path target, String newContent) {
        if (!Files.exists(target)) {
            return "(new file) " + target + "\n+++ " + preview(newContent);
        }
        return "--- " + preview(readExisting(target)) + "\n+++ " + preview(newContent);
    }

    @Generated
    private String readExisting(Path target) {
        try {
            return Files.readString(target, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "(unreadable)";
        }
    }

    private String preview(String content) {
        String trimmed = content.length() > 500 ? content.substring(0, 500) + "..." : content;
        return trimmed.replace("\n", "\\n");
    }
}
