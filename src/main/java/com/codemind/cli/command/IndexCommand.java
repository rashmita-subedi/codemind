package com.codemind.cli.command;

import com.codemind.service.IndexService;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;

@Component
@Command(name = "index", description = "Index a repository for semantic search.")
public class IndexCommand implements Runnable {

    @Parameters(index = "0", arity = "0..1", description = "Path to index. Defaults to current directory.")
    private String path = ".";

    @Option(names = "--force", description = "Re-index all files even if unchanged.")
    private boolean force;

    private final IndexService indexService;

    public IndexCommand(IndexService indexService) {
        this.indexService = indexService;
    }

    @Override
    public void run() {
        indexService.index(Path.of(path));
    }
}
