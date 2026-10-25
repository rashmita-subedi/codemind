package com.codemind.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@ConfigurationProperties("codemind.promptguard")
public class PromptGuardConfig {

    public static final List<String> DEFAULT_INJECTION_PATTERNS = List.of(
            "(?i)ignore\\s+(?:all\\s+|the\\s+)?(?:previous|prior|above|preceding)\\s+(?:instructions?|prompts?|rules?|directives?)",
            "(?i)disregard\\s+(?:all\\s+|the\\s+)?(?:previous|prior|above|system)\\s+(?:instructions?|prompts?|rules?)",
            "(?i)forget\\s+(?:everything|all|your\\s+(?:instructions?|prompts?))",
            "(?i)you\\s+are\\s+now\\s+(?:a|an)\\s+\\w+",
            "(?i)new\\s+instructions?\\s*:",
            "(?i)system\\s+prompt\\s*:",
            "(?i)developer\\s+mode",
            "(?i)jailbreak",
            "(?i)(?:reveal|print|show|leak)\\s+(?:your\\s+)?(?:system\\s+)?prompt"
    );

    public static final List<SecretPattern> DEFAULT_SECRET_PATTERNS = List.of(
            new SecretPattern("aws-access-key", "AKIA[0-9A-Z]{16}"),
            new SecretPattern("openai-key", "sk-[A-Za-z0-9]{20,}"),
            new SecretPattern("github-pat", "gh[pousr]_[A-Za-z0-9]{20,}"),
            new SecretPattern("jwt", "eyJ[A-Za-z0-9_\\-]+\\.eyJ[A-Za-z0-9_\\-]+\\.[A-Za-z0-9_\\-]+"),
            new SecretPattern("private-key-block", "-----BEGIN\\s+(?:RSA\\s+|EC\\s+|DSA\\s+|OPENSSH\\s+|PGP\\s+)?PRIVATE\\s+KEY-----[\\s\\S]*?-----END\\s+(?:RSA\\s+|EC\\s+|DSA\\s+|OPENSSH\\s+|PGP\\s+)?PRIVATE\\s+KEY-----"),
            new SecretPattern("credential-assignment", "(?i)(?:password|passwd|pwd|secret|api[_-]?key|access[_-]?token)\\s*[:=]\\s*[\"']?[A-Za-z0-9_\\-./+=]{8,}[\"']?")
    );

    private boolean enabled = true;
    private List<String> injectionPatterns = new ArrayList<>();
    private List<SecretPattern> secretPatterns = new ArrayList<>();
    private List<String> disabledDefaultInjectionPatterns = new ArrayList<>();
    private List<String> disabledDefaultSecretLabels = new ArrayList<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public List<String> getInjectionPatterns() {
        return injectionPatterns;
    }

    public void setInjectionPatterns(List<String> injectionPatterns) {
        this.injectionPatterns = injectionPatterns;
    }

    public List<SecretPattern> getSecretPatterns() {
        return secretPatterns;
    }

    public void setSecretPatterns(List<SecretPattern> secretPatterns) {
        this.secretPatterns = secretPatterns;
    }

    public List<String> getDisabledDefaultInjectionPatterns() {
        return disabledDefaultInjectionPatterns;
    }

    public void setDisabledDefaultInjectionPatterns(List<String> disabledDefaultInjectionPatterns) {
        this.disabledDefaultInjectionPatterns = disabledDefaultInjectionPatterns;
    }

    public List<String> getDisabledDefaultSecretLabels() {
        return disabledDefaultSecretLabels;
    }

    public void setDisabledDefaultSecretLabels(List<String> disabledDefaultSecretLabels) {
        this.disabledDefaultSecretLabels = disabledDefaultSecretLabels;
    }

    public static class SecretPattern {
        private String label;
        private String regex;

        public SecretPattern() {
        }

        public SecretPattern(String label, String regex) {
            this.label = label;
            this.regex = regex;
        }

        public String getLabel() {
            return label;
        }

        public void setLabel(String label) {
            this.label = label;
        }

        public String getRegex() {
            return regex;
        }

        public void setRegex(String regex) {
            this.regex = regex;
        }
    }
}
