package com.codemind.service;

import com.codemind.cli.Ansi;
import com.codemind.cli.InteractivePrompter;
import com.codemind.cli.SessionMode;
import com.codemind.policy.ActionRequest;
import com.codemind.policy.SafetyLevel;
import org.springframework.stereotype.Service;

/**
 * Centralised confirmation gate for destructive operations.
 *
 * <p>Behaviour is driven entirely by {@link SessionMode}:</p>
 * <ul>
 *   <li>{@code ask} (default) — prompts the user with a y/N read via
 *       {@link InteractivePrompter} (which uses JLine when the REPL is running and
 *       falls back to the system console otherwise).</li>
 *   <li>{@code agentic} — auto-approves and returns {@code true} immediately.</li>
 * </ul>
 */
@Service
public class ApprovalService {

    private final SessionMode sessionMode;
    private int preApprovedDepth;

    public ApprovalService(SessionMode sessionMode) {
        this.sessionMode = sessionMode;
    }

    // Opened by the AgentExecutor once it has already approved a tool invocation, so a
    // CONFIRM_REQUIRED tool that self-gates (per the WriteFileTool pattern) does not prompt the
    // human a second time for the same action. Re-entrant; always paired with exitPreApproved().
    public void enterPreApproved() {
        preApprovedDepth++;
    }

    public void exitPreApproved() {
        if (preApprovedDepth > 0) {
            preApprovedDepth--;
        }
    }

    /**
     * Asks the user a yes/no question.
     *
     * @return {@code true} if approved (or auto-approved in agentic mode).
     */
    public boolean confirm(String prompt) {
        if (sessionMode.isAgentic()) {
            return true;
        }
        String message = "  " + Ansi.wrap(prompt + " [y/N] ", Ansi.YELLOW);
        String input = InteractivePrompter.prompt(message).trim().toLowerCase();
        return "yes".equals(input) || "y".equals(input);
    }

    public boolean approved(ActionRequest actionRequest) {
        if (actionRequest.safetyLevel() == SafetyLevel.SAFE) {
            return true;
        }
        if (preApprovedDepth > 0) {
            return true;
        }
        if (sessionMode.isAgentic()) {
            System.out.println("  " + Ansi.wrap("agentic mode: auto-applying ", Ansi.DIM)
                    + Ansi.wrap(actionRequest.action(), Ansi.YELLOW));
            return true;
        }
        System.out.println();
        System.out.println("  " + Ansi.wrap("Approval required for: ", Ansi.BOLD)
                + Ansi.wrap(actionRequest.action(), Ansi.YELLOW));
        if (actionRequest.preview() != null && !actionRequest.preview().isBlank()) {
            System.out.println(actionRequest.preview());
        }
        String message = "  " + Ansi.wrap("Approve? [y/N] ", Ansi.YELLOW);
        String input = InteractivePrompter.prompt(message).trim().toLowerCase();
        return "yes".equals(input) || "y".equals(input);
    }
}
