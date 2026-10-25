package com.codemind.memory;

import com.codemind.memory.ConversationTurn.Role;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationTurnTest {

    @Test
    void exposesRoleAndContent() {
        ConversationTurn turn = new ConversationTurn(Role.USER, "hello");

        assertThat(turn.role()).isEqualTo(Role.USER);
        assertThat(turn.content()).isEqualTo("hello");
    }

    @Test
    void charCountReflectsContentLength() {
        assertThat(new ConversationTurn(Role.ASSISTANT, "abcd").charCount()).isEqualTo(4);
    }

    @Test
    void charCountIsZeroForNullContent() {
        assertThat(new ConversationTurn(Role.USER, null).charCount()).isZero();
    }
}
