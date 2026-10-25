package com.codemind.cli.command;

import com.codemind.agent.DirectorAgent;
import com.codemind.agent.core.AgentResult;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.List;

@Component
@Command(name = "do", hidden = true, description = "Free-form dispatch: the DirectorAgent classifies your request and routes it to the right specialist (or chains specialists).")
public class DoCommand implements Runnable {

    @Parameters(arity = "1..*", description = "Free-form natural-language request.")
    private List<String> input;

    @Option(names = "--verbose", description = "Show the full ReAct reasoning trace.")
    private boolean verbose;

    private final DirectorAgent director;

    public DoCommand(DirectorAgent director) {
        this.director = director;
    }

    @Override
    public void run() {
        AgentResult result = director.dispatch(String.join(" ", input));
        ResultPrinter.print(result, verbose);
    }
}
