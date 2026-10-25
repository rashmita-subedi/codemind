package com.codemind.cli;

import com.codemind.cli.command.AskCommand;
import com.codemind.cli.command.ChangelogCommand;
import com.codemind.cli.command.CommitCommand;
import com.codemind.cli.command.DoCommand;
import com.codemind.cli.command.IndexCommand;
import com.codemind.cli.command.RefactorCommand;
import com.codemind.cli.command.RollbackCommand;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;

@Component
@Command(
        name = "codemind",
        mixinStandardHelpOptions = true,
        version = "codemind 0.0.1-SNAPSHOT",
        description = "Org-internal CLI code intelligence — index, ask, refactor, commit, changelog.",
        subcommands = {
                IndexCommand.class,
                AskCommand.class,
                RefactorCommand.class,
                CommitCommand.class,
                ChangelogCommand.class,
                DoCommand.class,
                RollbackCommand.class
        }
)
public class CodemindCli implements Runnable {

    @Override
    public void run() {
        System.out.println("codemind: no subcommand given. Use --help to list available commands, or run with no args for the interactive REPL.");
    }
}
