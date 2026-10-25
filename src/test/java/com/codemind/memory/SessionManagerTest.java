package com.codemind.memory;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SessionManagerTest {

    @Test
    void currentSessionIdIsStableBetweenReads() {
        SessionManager manager = new SessionManager();

        String first = manager.currentSessionId();
        String second = manager.currentSessionId();

        assertThat(first).isNotBlank().isEqualTo(second);
    }

    @Test
    void resetRotatesToANewId() {
        SessionManager manager = new SessionManager();
        String before = manager.currentSessionId();

        String returned = manager.reset();

        assertThat(returned).isEqualTo(manager.currentSessionId());
        assertThat(returned).isNotBlank().isNotEqualTo(before);
    }

    @Test
    void setCurrentSessionIdAdoptsTheGivenId() {
        SessionManager manager = new SessionManager();

        String adopted = manager.setCurrentSessionId("feature-x");

        assertThat(adopted).isEqualTo("feature-x");
        assertThat(manager.currentSessionId()).isEqualTo("feature-x");
    }

    @Test
    void setCurrentSessionIdSanitisesUnsafeCharacters() {
        SessionManager manager = new SessionManager();

        String adopted = manager.setCurrentSessionId("../../etc/passwd");

        assertThat(adopted).doesNotContain("/").doesNotContain("..");
        assertThat(adopted).matches("[A-Za-z0-9_-]+");
    }

    @Test
    void nullIdFallsBackToAFreshId() {
        assertThat(SessionManager.sanitize(null)).isNotBlank();
    }

    @Test
    void allUnsafeIdFallsBackToAFreshId() {
        assertThat(SessionManager.sanitize("")).isNotBlank();
    }
}
