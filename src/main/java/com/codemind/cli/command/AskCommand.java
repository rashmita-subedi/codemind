package com.codemind.cli.command;

import com.codemind.service.QaService;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.List;

@Component
@Command(name = "ask", description = "Ask a natural-language question about the indexed codebase.")
public class AskCommand implements Runnable {

    @Parameters(arity = "1..*", description = "The question. Wrap in quotes if it contains spaces.")
    private List<String> question;

    @Option(names = "--verbose", description = "Show the full ReAct reasoning trace.")
    private boolean verbose;

    private final QaService qaService;

    public AskCommand(QaService qaService) {
        this.qaService = qaService;
    }

    @Override
    public void run() {
        qaService.ask(String.join(" ", question));
    }
}
