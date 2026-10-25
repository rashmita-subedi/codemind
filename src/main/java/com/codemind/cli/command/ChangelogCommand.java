package com.codemind.cli.command;

import com.codemind.agent.ChangeAnalyzerAgent;
import com.codemind.agent.core.AgentResult;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Component
@Command(name = "changelog", description = "Generate a changelog from a git revision range.")
public class ChangelogCommand implements Runnable {

    @Parameters(index = "0", arity = "0..1", description = "Git revision range. Defaults to HEAD~1..HEAD.")
    private String range = "HEAD~1..HEAD";

    @Option(names = "--plain", description = "Output a plain-English paragraph instead of the engineer-friendly grouped list.")
    private boolean plain;

    private final ChangeAnalyzerAgent changeAnalyzer;

    public ChangelogCommand(ChangeAnalyzerAgent changeAnalyzer) {
        this.changeAnalyzer = changeAnalyzer;
    }

    @Override
    public void run() {
        AgentResult result = changeAnalyzer.generateChangelog(range, plain);
        System.out.println(result.answer());
    }
}
