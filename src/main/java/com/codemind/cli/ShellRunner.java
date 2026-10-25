package com.codemind.cli;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

public final class ShellRunner {

    private static final boolean IS_WINDOWS = System.getProperty("os.name", "")
            .toLowerCase(Locale.ROOT)
            .startsWith("win");

    private ShellRunner() {
    }

    public static int run(String command) {
        if (command == null || command.isBlank()) {
            return 0;
        }
        ProcessBuilder pb = IS_WINDOWS
                ? new ProcessBuilder("cmd.exe", "/c", command)
                : new ProcessBuilder("bash", "-lc", command);
        pb.directory(new File(System.getProperty("user.dir")));
        pb.redirectErrorStream(true);
        Process proc = null;
        try {
            proc = pb.start();
            Thread interruptWatcher = relayInterrupt(proc);
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    System.out.println("  " + line);
                }
            }
            boolean finished = proc.waitFor(2, TimeUnit.MINUTES);
            interruptWatcher.interrupt();
            if (!finished) {
                proc.destroyForcibly();
                System.out.println("  " + Ansi.wrap("killed: 2 minute timeout exceeded", Ansi.YELLOW));
                return -1;
            }
            int exit = proc.exitValue();
            if (exit != 0) {
                System.out.println();
                System.out.println("  " + Ansi.wrap("exit " + exit, Ansi.YELLOW));
            }
            return exit;
        } catch (IOException e) {
            System.out.println("  " + Ansi.wrap("shell error: " + e.getMessage(), Ansi.RED));
            return -1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (proc != null && proc.isAlive()) {
                proc.destroyForcibly();
            }
            System.out.println();
            System.out.println("  " + Ansi.wrap("cancelled", Ansi.YELLOW));
            return -1;
        }
    }

    private static Thread relayInterrupt(Process proc) {
        Thread caller = Thread.currentThread();
        return Thread.ofVirtual().name("shell-int-relay").start(() -> {
            while (proc.isAlive()) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    return;
                }
                if (caller.isInterrupted()) {
                    proc.destroyForcibly();
                    return;
                }
            }
        });
    }
}
