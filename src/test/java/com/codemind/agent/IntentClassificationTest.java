package com.codemind.agent;

import com.codemind.agent.IntentClassification.Args;
import com.codemind.agent.IntentClassification.ChainStep;
import com.codemind.agent.IntentClassification.Intent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IntentClassificationTest {

    @Test
    void holdsAllFields() {
        Args args = new Args("q", "p", "r", Boolean.TRUE);
        ChainStep step = new ChainStep(Intent.COMMIT, args);
        IntentClassification c = new IntentClassification(Intent.COMPOUND, 0.92, args, List.of(step));

        assertThat(c.intent()).isEqualTo(Intent.COMPOUND);
        assertThat(c.confidence()).isEqualTo(0.92);
        assertThat(c.args().query()).isEqualTo("q");
        assertThat(c.chain()).containsExactly(step);
        assertThat(step.intent()).isEqualTo(Intent.COMMIT);
    }

    @Test
    void plainOrFalseHandlesTrueFalseAndNull() {
        assertThat(new Args(null, null, null, Boolean.TRUE).plainOrFalse()).isTrue();
        assertThat(new Args(null, null, null, Boolean.FALSE).plainOrFalse()).isFalse();
        assertThat(new Args(null, null, null, null).plainOrFalse()).isFalse();
    }
}
