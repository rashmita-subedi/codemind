package com.codemind.cli.command;

import com.codemind.agent.RefactorAgent;
import com.codemind.agent.core.AgentResult;
import com.codemind.service.ApprovalService;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Component
@Command(name = "refactor", description = "Analyse a file for code smells and propose a phased refactor plan.")
public class RefactorCommand implements Runnable {

    @Parameters(index = "0", arity = "0..1", description = "File to refactor. Defaults to 'src'.")
    private String target = "src";

    @Option(names = "--apply", description = "Apply Phase 1 safe extractions after analysis.")
    private boolean apply;

    @Option(names = "--verbose", description = "Show the full ReAct reasoning trace.")
    private boolean verbose;

    private final RefactorAgent refactorAgent;
    private final ApprovalService approvalService;

    public RefactorCommand(RefactorAgent refactorAgent, ApprovalService approvalService) {
        this.refactorAgent = refactorAgent;
        this.approvalService = approvalService;
    }

    @Override
    public void run() {
        AgentResult analysis = refactorAgent.analyze(target);
        ResultPrinter.print(analysis, verbose);
        if (!apply) {
            return;
        }
        if (!approvalService.confirm("Apply Phase-1 refactor to " + target + "?")) {
            System.out.println("  Skipped.");
            return;
        }
        AgentResult result = refactorAgent.apply(target, analysis.answer());
        ResultPrinter.print(result, verbose);
    }
}
