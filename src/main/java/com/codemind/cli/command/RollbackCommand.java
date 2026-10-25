package com.codemind.cli.command;

import com.codemind.cli.Ansi;
import com.codemind.service.AuditService;
import com.codemind.service.RollbackService;
import com.codemind.service.RollbackService.RestoredFile;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.util.List;

@Component
@Command(name = "rollback", description = "Restore files from the most recent refactor --apply (reads .codemind/backup/).")
public class RollbackCommand implements Runnable {

    @Parameters(index = "0", arity = "0..1",
            description = "How many backups to restore (newest first). 0 (default) restores the most recent refactor session, clustered within 5 seconds.")
    private int howMany = 0;

    private final RollbackService rollbackService;
    private final AuditService audit;

    public RollbackCommand(RollbackService rollbackService, AuditService audit) {
        this.rollbackService = rollbackService;
        this.audit = audit;
    }

    @Override
    public void run() {
        List<RestoredFile> restored = rollbackService.rollbackLatest(howMany);
        if (restored.isEmpty()) {
            System.out.println();
            System.out.println("  " + Ansi.wrap("Nothing restored.", Ansi.YELLOW)
                    + " " + Ansi.wrap("(no backups, or rollback was declined)", Ansi.DIM));
            return;
        }
        System.out.println();
        System.out.println("  " + Ansi.wrap("Rollback", Ansi.BOLD, Ansi.BRIGHT_CYAN)
                + Ansi.wrap("  restored " + restored.size() + " file(s)", Ansi.DIM));
        for (RestoredFile r : restored) {
            System.out.println("  " + Ansi.wrap(String.format("%10d bytes", r.bytes()), Ansi.DIM)
                    + "  " + r.originalPath());
        }
        audit.record("rollback.applied", restored.size() + " files");
    }
}
