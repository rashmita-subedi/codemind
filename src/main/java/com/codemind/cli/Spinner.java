package com.codemind.cli;

public final class Spinner implements AutoCloseable {

    private static final String[] FRAMES = {"|", "/", "-", "\\"};

    private final Thread thread;
    private final long startNanos = System.nanoTime();
    private volatile boolean running = true;
    private volatile String label;
    private int lastLineLength;

    public Spinner(String label) {
        this.label = label;
        this.thread = Thread.ofVirtual().name("codemind-spinner").unstarted(this::run);
        this.thread.start();
    }

    public void update(String newLabel) {
        this.label = newLabel;
    }

    @Override
    public void close() {
        running = false;
        try {
            thread.join(300);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        clearLine();
    }

    private void run() {
        int i = 0;
        while (running) {
            long elapsedSec = (System.nanoTime() - startNanos) / 1_000_000_000L;
            String line = "  " + Ansi.wrap(FRAMES[i % FRAMES.length], Ansi.BRIGHT_MAGENTA)
                    + "  " + Ansi.wrap(label, Ansi.DIM)
                    + Ansi.wrap("  " + elapsedSec + "s", Ansi.GRAY);
            int visibleLength = Ansi.strip(line).length();
            synchronized (System.out) {
                System.out.print("\r" + line);
                int padding = lastLineLength - visibleLength;
                if (padding > 0) {
                    System.out.print(" ".repeat(padding));
                }
                System.out.flush();
                lastLineLength = visibleLength;
            }
            i++;
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private void clearLine() {
        synchronized (System.out) {
            if (lastLineLength > 0) {
                System.out.print("\r" + " ".repeat(lastLineLength) + "\r");
                System.out.flush();
            }
        }
    }
}
