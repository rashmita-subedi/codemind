package com.codemind.retrieval;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

final class IndexCacheLayout {

    private IndexCacheLayout() {
    }

    // Drops a self-contained `.gitignore` (`*`) inside the .codemind index dir so the cache
    // CodeMind writes into a target repo is fully ignored by git, without ever modifying that
    // repo's own root .gitignore. Idempotent: leaves an existing .gitignore untouched.
    static void markIgnored(Path indexDir) throws IOException {
        Path gitignore = indexDir.resolve(".gitignore");
        if (!Files.exists(gitignore)) {
            Files.writeString(gitignore,
                    "# Created by CodeMind. Local index cache; not for version control.\n*\n",
                    StandardCharsets.UTF_8);
        }
    }
}
