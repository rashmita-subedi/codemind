package com.codemind.cli;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class SessionLaunchOptionsTest {

    @Test
    void noArgsIsAPlainReplLaunch() {
        Optional<SessionLaunchOptions> opts = SessionLaunchOptions.parse(new String[]{});
        assertThat(opts).isPresent();
        assertThat(opts.get().continueLatest()).isFalse();
        assertThat(opts.get().sessionId()).isNull();
    }

    @Test
    void nullArgsIsAPlainReplLaunch() {
        assertThat(SessionLaunchOptions.parse(null)).isPresent();
    }

    @Test
    void continueFlagSetsContinueLatest() {
        assertThat(SessionLaunchOptions.parse(new String[]{"--continue"}).orElseThrow().continueLatest()).isTrue();
        assertThat(SessionLaunchOptions.parse(new String[]{"-c"}).orElseThrow().continueLatest()).isTrue();
    }

    @Test
    void sessionFlagCapturesId() {
        assertThat(SessionLaunchOptions.parse(new String[]{"--session", "feature-x"}).orElseThrow().sessionId())
                .isEqualTo("feature-x");
        assertThat(SessionLaunchOptions.parse(new String[]{"--session=feature-x"}).orElseThrow().sessionId())
                .isEqualTo("feature-x");
    }

    @Test
    void resumeWithIdCapturesId() {
        assertThat(SessionLaunchOptions.parse(new String[]{"--resume", "abc"}).orElseThrow().sessionId())
                .isEqualTo("abc");
        assertThat(SessionLaunchOptions.parse(new String[]{"--resume=abc"}).orElseThrow().sessionId())
                .isEqualTo("abc");
    }

    @Test
    void bareResumeMeansContinueLatest() {
        SessionLaunchOptions opts = SessionLaunchOptions.parse(new String[]{"--resume"}).orElseThrow();
        assertThat(opts.continueLatest()).isTrue();
        assertThat(opts.sessionId()).isNull();
    }

    @Test
    void sessionFlagWithoutValueIsMalformed() {
        assertThat(SessionLaunchOptions.parse(new String[]{"--session"})).isEmpty();
    }

    @Test
    void subcommandIsNotAReplLaunch() {
        assertThat(SessionLaunchOptions.parse(new String[]{"ask", "what is auth"})).isEmpty();
    }

    @Test
    void unknownFlagIsNotAReplLaunch() {
        assertThat(SessionLaunchOptions.parse(new String[]{"--verbose"})).isEmpty();
    }
}
