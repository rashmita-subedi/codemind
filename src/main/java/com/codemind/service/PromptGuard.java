package com.codemind.service;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

@Service
public class PromptGuard {

    private final boolean enabled;
    private final AuditService audit;
    private final List<Pattern> injectionPatterns;
    private final List<CompiledSecret> secretPatterns;

    public PromptGuard(PromptGuardConfig config, AuditService audit) {
        this.enabled = config.isEnabled();
        this.audit = audit;

        List<String> activeInjection = new ArrayList<>();
        for (String def : PromptGuardConfig.DEFAULT_INJECTION_PATTERNS) {
            if (!matchesAny(def, config.getDisabledDefaultInjectionPatterns())) {
                activeInjection.add(def);
            }
        }
        activeInjection.addAll(config.getInjectionPatterns());
        this.injectionPatterns = compileInjection(activeInjection);

        List<PromptGuardConfig.SecretPattern> activeSecrets = new ArrayList<>();
        for (PromptGuardConfig.SecretPattern def : PromptGuardConfig.DEFAULT_SECRET_PATTERNS) {
            if (!config.getDisabledDefaultSecretLabels().contains(def.getLabel())) {
                activeSecrets.add(def);
            }
        }
        activeSecrets.addAll(config.getSecretPatterns());
        this.secretPatterns = compileSecrets(activeSecrets);
    }

    private static boolean matchesAny(String defaultRegex, List<String> needles) {
        for (String needle : needles) {
            if (defaultRegex.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    public String sanitize(String input) {
        if (input == null) {
            return "";
        }
        if (!enabled) {
            return input;
        }
        return neutralizeInjections(redactSecrets(input));
    }

    private String redactSecrets(String input) {
        String current = input;
        for (CompiledSecret sp : secretPatterns) {
            Matcher matcher = sp.pattern().matcher(current);
            if (matcher.find()) {
                audit.record("promptguard.secret.redacted", sp.label());
                current = matcher.replaceAll("[REDACTED:" + sp.label() + "]");
            }
        }
        return current;
    }

    private String neutralizeInjections(String input) {
        String current = input;
        for (Pattern p : injectionPatterns) {
            Matcher matcher = p.matcher(current);
            if (matcher.find()) {
                audit.record("promptguard.injection.blocked", matcher.group());
                current = matcher.replaceAll("[BLOCKED:injection]");
            }
        }
        return current;
    }

    private static List<Pattern> compileInjection(List<String> regexes) {
        List<Pattern> out = new ArrayList<>(regexes.size());
        for (String r : regexes) {
            try {
                out.add(Pattern.compile(r));
            } catch (PatternSyntaxException e) {
                throw new IllegalStateException("Invalid injection regex: " + r, e);
            }
        }
        return List.copyOf(out);
    }

    private static List<CompiledSecret> compileSecrets(List<PromptGuardConfig.SecretPattern> patterns) {
        List<CompiledSecret> out = new ArrayList<>(patterns.size());
        for (PromptGuardConfig.SecretPattern p : patterns) {
            try {
                out.add(new CompiledSecret(p.getLabel(), Pattern.compile(p.getRegex())));
            } catch (PatternSyntaxException e) {
                throw new IllegalStateException("Invalid secret regex for label " + p.getLabel() + ": " + p.getRegex(), e);
            }
        }
        return List.copyOf(out);
    }

    private record CompiledSecret(String label, Pattern pattern) {
    }
}
