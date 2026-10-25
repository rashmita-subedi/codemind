package com.codemind.agent;

import java.util.List;

// Strict-JSON schema the DirectorAgent deserializes the classification call into.
// Mirrors prompts/director-system.st. Missing fields deserialize to null/0.0.
public record IntentClassification(
        Intent intent,
        double confidence,
        Args args,
        List<ChainStep> chain) {

    public enum Intent { QNA, REFACTOR, COMMIT, CHANGELOG, COMPOUND }

    public record Args(String query, String path, String range, Boolean plain) {
        public boolean plainOrFalse() {
            return Boolean.TRUE.equals(plain);
        }
    }

    public record ChainStep(Intent intent, Args args) {
    }
}
