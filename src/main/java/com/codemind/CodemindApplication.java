package com.codemind;

import com.codemind.cli.CodemindCli;
import com.codemind.cli.SessionLaunchOptions;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import picocli.CommandLine;
import picocli.spring.PicocliSpringFactory;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

@SpringBootApplication
public class CodemindApplication {

    public static void main(String[] args) {
        System.setOut(new PrintStream(System.out, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(System.err, true, StandardCharsets.UTF_8));

        if (isHelpOrVersion(args)) {
            int exit = new CommandLine(new CodemindCli()).execute(args);
            System.exit(exit);
        }

        SpringApplication app = new SpringApplication(CodemindApplication.class);
        app.setBannerMode(Banner.Mode.OFF);
        ConfigurableApplicationContext ctx = app.run(args);
        printPrivacyBanner(ctx);
        // No args, or only session-resume flags → the REPL already ran inside app.run via
        // CliRunner. Anything else is a Picocli subcommand.
        if (SessionLaunchOptions.parse(args).isPresent()) {
            return;
        }
        int exitCode = new CommandLine(ctx.getBean(CodemindCli.class), new PicocliSpringFactory(ctx))
                .execute(args);
        ctx.close();
        System.exit(exitCode);
    }

    private static boolean isHelpOrVersion(String[] args) {
        for (String arg : args) {
            if ("--help".equals(arg) || "-h".equals(arg)
                    || "--version".equals(arg) || "-V".equals(arg)) {
                return true;
            }
        }
        return false;
    }

    private static void printPrivacyBanner(ConfigurableApplicationContext ctx) {
        String chatUrl = ctx.getEnvironment().getProperty("spring.ai.openai.base-url", "(unset)");
        String embedUrl = ctx.getEnvironment().getProperty("spring.ai.ollama.base-url", "(unset)");
        System.out.println("Using chat endpoint: " + chatUrl);
        System.out.println("Using embedding endpoint: " + embedUrl);
    }
}
