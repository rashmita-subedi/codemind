package com.codemind.cli;

public final class OperationRegistry {

    private static volatile Runnable cancelHook;
    private static volatile String currentLabel;

    private OperationRegistry() {
    }

    public static synchronized void register(Runnable hook, String label) {
        cancelHook = hook;
        currentLabel = label;
    }

    public static synchronized void clear() {
        cancelHook = null;
        currentLabel = null;
    }

    public static synchronized boolean cancelCurrent() {
        Runnable hook = cancelHook;
        if (hook == null) {
            return false;
        }
        try {
            hook.run();
        } catch (Throwable ignored) {
            // best effort
        }
        return true;
    }

    public static String currentLabel() {
        return currentLabel;
    }

    public static boolean hasActive() {
        return cancelHook != null;
    }
}
