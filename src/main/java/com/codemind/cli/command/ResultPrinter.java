package com.codemind.cli.command;

import com.codemind.agent.core.AgentResult;
import com.codemind.agent.core.ReActStep;

final class ResultPrinter {

    private ResultPrinter() {
    }

    static void print(AgentResult result, boolean verbose) {
        System.out.println(result.answer());
        if (!verbose) {
            return;
        }
        System.out.println();
        System.out.println("--- trace (" + result.totalSteps() + " steps, "
                + result.totalDurationMs() + " ms"
                + (result.hitMaxSteps() ? ", hit step limit" : "") + ") ---");
        for (ReActStep step : result.steps()) {
            System.out.println(step.index() + ". " + step.action()
                    + (step.approved() ? "" : " [not applied]")
                    + " — " + step.observation());
        }
    }
}
