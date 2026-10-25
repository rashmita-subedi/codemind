package com.codemind.cli.command;

import com.codemind.agent.ChangeAnalyzerAgent;
import com.codemind.agent.core.AgentResult;
import com.codemind.cli.GitCommitter;
import com.codemind.service.ApprovalService;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;

@Component
@Command(name = "commit", description = "Generate a Conventional Commits message from staged git changes.")
public class CommitCommand implements Runnable {

    private final ChangeAnalyzerAgent changeAnalyzer;
    private final ApprovalService approvalService;

    public CommitCommand(ChangeAnalyzerAgent changeAnalyzer, ApprovalService approvalService) {
        this.changeAnalyzer = changeAnalyzer;
        this.approvalService = approvalService;
    }

    @Override
    public void run() {
        if (!GitCommitter.ensureStaged()) {
            System.out.println("  Nothing to commit (working tree clean, or not a git repo).");
            return;
        }
        AgentResult result = changeAnalyzer.generateCommitMessage();
        System.out.println(result.answer());
        if (!approvalService.confirm("Apply this commit?")) {
            System.out.println("  Skipped.");
            return;
        }
        GitCommitter.commit(result.answer());
    }
}
