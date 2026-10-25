package com.codemind.service;

import com.codemind.agent.ReActEngine;
import com.codemind.agent.ReActResult;
import com.codemind.cli.Ansi;
import com.codemind.cli.AnswerRenderer;
import com.codemind.retrieval.ChunkStore;
import org.springframework.stereotype.Service;

@Service
public class QaService {

    private final ReActEngine reActEngine;
    private final ChunkStore chunkStore;

    public QaService(ReActEngine reActEngine, ChunkStore chunkStore) {
        this.reActEngine = reActEngine;
        this.chunkStore = chunkStore;
    }

    public void ask(String question) {
        if (question == null || question.isBlank()) {
            System.out.println("  " + Ansi.wrap("Please provide a question after `ask`.", Ansi.YELLOW));
            return;
        }
        long startNanos = System.nanoTime();
        ReActResult result;
        try {
            result = reActEngine.answer(question);
        } catch (Exception e) {
            System.out.println();
            System.out.println("  " + Ansi.wrap("Ask failed", Ansi.BOLD, Ansi.RED));
            System.out.println("  " + Ansi.wrap(String.valueOf(e.getMessage()), Ansi.RED));
            System.out.println("  " + Ansi.wrap("Check `CODEMIND_AIHUB_KEY`, endpoint reachability, and model name.", Ansi.DIM));
            return;
        }
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;

        if (result == null || result.answer() == null || result.answer().isBlank()) {
            System.out.println();
            System.out.println("  " + Ansi.wrap("No answer could be produced.", Ansi.BOLD, Ansi.RED));
            System.out.println("  " + Ansi.wrap("The model returned an empty response after retry.", Ansi.DIM));
            return;
        }
        printAnswer(result.answer(), result.steps() == null ? 0 : result.steps().size(), elapsedMs);
    }

    public void map(String question) {
        String query = question == null || question.isBlank() ? "api flow integration service repository" : question;
        System.out.println();
        System.out.println("  " + Ansi.wrap("Code navigation map", Ansi.BOLD, Ansi.BRIGHT_MAGENTA));
        System.out.println();
        String digest = chunkStore.navigationDigest(query);
        for (String line : digest.split("\n", -1)) {
            System.out.println("  " + Ansi.wrap(line, Ansi.DIM));
        }
        System.out.println();
    }

    private void printAnswer(String answer, int stepCount, long elapsedMs) {
        System.out.println();
        System.out.println("  " + Ansi.wrap("CodeMind", Ansi.BOLD, Ansi.BRIGHT_CYAN));
        System.out.println();
        System.out.println(AnswerRenderer.renderIndented(answer));
        System.out.println();
        String footer = stepCount + " steps  " + String.format("%.1fs", elapsedMs / 1000.0);
        System.out.println("  " + Ansi.wrap(footer, Ansi.DIM));
    }
}
