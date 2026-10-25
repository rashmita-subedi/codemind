package com.codemind.cli;

import java.util.Optional;

// Parses the top-level REPL launch flags. Present only when args are empty or consist
// solely of session flags — anything else (a subcommand, an unknown flag) yields empty,
// signalling that Picocli should handle the invocation instead of the REPL.
public record SessionLaunchOptions(boolean continueLatest, String sessionId) {

    public static Optional<SessionLaunchOptions> parse(String[] args) {
        if (args == null || args.length == 0) {
            return Optional.of(new SessionLaunchOptions(false, null));
        }
        boolean continueLatest = false;
        String sessionId = null;
        for (int i = 0; i < args.length; i++) {
            String arg = args[i] == null ? "" : args[i].trim();
            if (arg.equals("--continue") || arg.equals("-c")) {
                continueLatest = true;
            } else if (arg.equals("--resume")) {
                if (i + 1 < args.length && !args[i + 1].startsWith("-")) {
                    sessionId = args[++i];
                } else {
                    continueLatest = true;
                }
            } else if (arg.equals("--session")) {
                if (i + 1 >= args.length) {
                    return Optional.empty();
                }
                sessionId = args[++i];
            } else if (arg.startsWith("--session=")) {
                sessionId = arg.substring("--session=".length());
            } else if (arg.startsWith("--resume=")) {
                sessionId = arg.substring("--resume=".length());
            } else {
                return Optional.empty();
            }
        }
        return Optional.of(new SessionLaunchOptions(continueLatest, sessionId));
    }
}
