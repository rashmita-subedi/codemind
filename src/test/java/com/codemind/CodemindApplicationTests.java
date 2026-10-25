package com.codemind;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "codemind.shell.interactive=false",
        "codemind.vector-store=memory",
        "spring.ai.vectorstore.chroma.initialize-schema=false",
        "spring.autoconfigure.exclude=org.springframework.ai.vectorstore.chroma.autoconfigure.ChromaVectorStoreAutoConfiguration"
})
class CodemindApplicationTests {

    @Test
    void contextLoads() {
    }

}
