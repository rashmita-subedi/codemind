package com.codemind.service;

import com.codemind.cli.ConversationMode;
import com.codemind.cli.InteractivePrompter;
import com.codemind.cli.SessionMode;
import com.codemind.policy.ActionRequest;
import com.codemind.policy.SafetyLevel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class ApprovalServiceTest {

    private final InputStream realIn = System.in;

    @AfterEach
    void restoreStdin() {
        System.setIn(realIn);
        InteractivePrompter.clear();
    }

    private ApprovalService withStdin(String input, SessionMode mode) {
        System.setIn(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)));
        return new ApprovalService(mode);
    }

    private SessionMode ask() {
        SessionMode m = new SessionMode();
        m.set(ConversationMode.ASK);
        return m;
    }

    private SessionMode agentic() {
        SessionMode m = new SessionMode();
        m.set(ConversationMode.AGENTIC);
        return m;
    }

    private ActionRequest confirmReq() {
        return new ActionRequest("write_file:x", SafetyLevel.CONFIRM_REQUIRED, "preview");
    }

    @Test
    void safeActionsAreAlwaysApproved() {
        ApprovalService service = withStdin("", ask());
        assertThat(service.approved(new ActionRequest("read", SafetyLevel.SAFE, "p"))).isTrue();
    }

    @Test
    void agenticModeApprovesConfirmWithoutPrompting() {
        ApprovalService service = withStdin("", agentic());
        assertThat(service.approved(confirmReq())).isTrue();
    }

    @Test
    void askModePromptApprovesOnYes() {
        ApprovalService service = withStdin("yes\n", ask());
        assertThat(service.approved(confirmReq())).isTrue();
    }

    @Test
    void askModePromptDeclinesOnNo() {
        ApprovalService service = withStdin("no\n", ask());
        assertThat(service.approved(confirmReq())).isFalse();
    }

    @Test
    void preApprovedScopeSuppressesTheSecondPrompt() {
        ApprovalService service = withStdin("", ask());
        service.enterPreApproved();
        assertThat(service.approved(confirmReq())).isTrue();
    }

    @Test
    void exitingPreApprovedScopeRestoresPrompting() {
        ApprovalService service = withStdin("no\n", ask());
        service.enterPreApproved();
        service.exitPreApproved();
        assertThat(service.approved(confirmReq())).isFalse();
    }

    @Test
    void exitGuardDoesNotUnderflowBelowZero() {
        ApprovalService service = withStdin("no\n", ask());
        service.exitPreApproved();
        // Depth must still be 0 (not -1), so a real action still prompts and is declined.
        assertThat(service.approved(confirmReq())).isFalse();
    }

    @Test
    void confirmReturnsTrueInAgenticMode() {
        ApprovalService service = withStdin("", agentic());
        assertThat(service.confirm("apply?")).isTrue();
    }

    @Test
    void confirmPromptsInAskModeAndAcceptsY() {
        ApprovalService service = withStdin("y\n", ask());
        assertThat(service.confirm("apply?")).isTrue();
    }

    @Test
    void confirmPromptsInAskModeAndRejectsN() {
        ApprovalService service = withStdin("n\n", ask());
        assertThat(service.confirm("apply?")).isFalse();
    }
}
