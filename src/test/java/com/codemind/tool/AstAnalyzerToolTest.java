package com.codemind.tool;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class AstAnalyzerToolTest {

    @TempDir
    Path tempDir;

    private AstAnalyzerTool tool;

    @BeforeEach
    void setUp() {
        tool = new AstAnalyzerTool();
    }

    private Path write(String name, String content) throws IOException {
        Path p = tempDir.resolve(name);
        Files.writeString(p, content);
        return p;
    }

    @Test
    void extractsJavaClassAndMethods() throws IOException {
        Path file = write("A.java", """
                public class Account {
                    public void deposit(int amount) {
                    }
                    private int balance() {
                        return 0;
                    }
                }
                interface Ledger {
                }
                enum State {
                }
                """);

        String result = tool.analyze(file.toString());

        assertThat(result)
                .contains("class Account (line 1)")
                .contains("method deposit (line 2)")
                .contains("method balance (line 4)")
                .contains("interface Ledger (line 8)")
                .contains("enum State (line 10)");
    }

    @Test
    void extractsPythonClassAndFunctions() throws IOException {
        Path file = write("svc.py", """
                class Service:
                    def handle(self):
                        pass

                def helper():
                    return 1
                """);

        String result = tool.analyze(file.toString());

        assertThat(result)
                .contains("class Service (line 1)")
                .contains("def handle (line 2)")
                .contains("def helper (line 5)");
    }

    @Test
    void extractsJavaScriptFunctionsAndArrows() throws IOException {
        Path file = write("app.js", """
                class Widget {}
                function render() {}
                const load = (id) => id;
                """);

        String result = tool.analyze(file.toString());

        assertThat(result)
                .contains("class Widget (line 1)")
                .contains("function render (line 2)")
                .contains("function load (line 3)");
    }

    @Test
    void unsupportedLanguageReturnsError() throws IOException {
        Path file = write("notes.txt", "just text");

        assertThat(tool.analyze(file.toString())).isEqualTo("ERROR: unsupported language: txt");
    }

    @Test
    void fileWithNoExtensionReturnsError() throws IOException {
        Path file = write("Makefile", "all:");

        assertThat(tool.analyze(file.toString())).isEqualTo("ERROR: unsupported language: ");
    }

    @Test
    void fileWithNoDeclarationsReportsNone() throws IOException {
        Path file = write("Empty.java", "// just a comment\nint x = 1;");

        assertThat(tool.analyze(file.toString())).startsWith("No declarations found");
    }

    @Test
    void blankPathReturnsError() {
        assertThat(tool.analyze("  ")).isEqualTo("ERROR: filePath is empty.");
    }

    @Test
    void nullPathReturnsError() {
        assertThat(tool.analyze(null)).isEqualTo("ERROR: filePath is empty.");
    }

    @Test
    void missingFileReturnsError() {
        Path missing = tempDir.resolve("nope.java");

        assertThat(tool.analyze(missing.toString())).startsWith("ERROR: file not found:");
    }

    @Test
    void directoryReturnsError() {
        assertThat(tool.analyze(tempDir.toString())).startsWith("ERROR: path is a directory");
    }

    @Test
    void largeFileIsGuarded() throws IOException {
        Path big = tempDir.resolve("Big.java");
        Files.write(big, new byte[512_001]);

        assertThat(tool.analyze(big.toString())).startsWith("ERROR: file too large");
    }

    @Test
    void binaryFileIsRejected() throws IOException {
        Path bin = tempDir.resolve("Bin.java");
        Files.write(bin, new byte[]{1, 0, 2});

        assertThat(tool.analyze(bin.toString())).startsWith("ERROR: binary file");
    }
}
