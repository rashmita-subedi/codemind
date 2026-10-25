package com.codemind.cli;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class AnswerRenderer {

    private static final Pattern HEADER = Pattern.compile("^(#{1,6})\\s+(.+)$");
    private static final Pattern BOLD = Pattern.compile("\\*\\*([^*\\n]+)\\*\\*");
    private static final Pattern ITALIC = Pattern.compile("(?<![*\\w])\\*([^*\\n]+)\\*(?![*\\w])");
    private static final Pattern INLINE_CODE = Pattern.compile("`([^`\\n]+)`");
    private static final Pattern FILE_REF = Pattern.compile("(?<![\\w])([A-Za-z]:\\\\[^\\s`'\")\\]]+|[A-Za-z][\\w./_-]*\\.[A-Za-z0-9]+):(\\d+(?:-\\d+)?)");
    private static final Pattern BULLET = Pattern.compile("^(\\s*)([-*])\\s+");
    private static final Pattern NUMBERED = Pattern.compile("^(\\s*)(\\d+)\\.\\s+");
    private static final String INDENT = "  ";

    private AnswerRenderer() {
    }

    public static String renderIndented(String markdown) {
        String body = render(markdown);
        if (body.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(body.length() + 64);
        for (String line : body.split("\n", -1)) {
            out.append(INDENT).append(line).append('\n');
        }
        int len = out.length();
        if (len > 0 && out.charAt(len - 1) == '\n') {
            out.setLength(len - 1);
        }
        return out.toString();
    }

    public static String render(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return "";
        }
        markdown = sanitize(markdown);
        markdown = trimBlankEdges(markdown);
        if (markdown.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(markdown.length() + 64);
        boolean inFence = false;
        for (String raw : markdown.split("\n", -1)) {
            String line = raw;
            if (line.startsWith("```")) {
                inFence = !inFence;
                out.append(Ansi.wrap(line, Ansi.GRAY)).append('\n');
                continue;
            }
            if (inFence) {
                out.append(Ansi.wrap(line, Ansi.GREEN)).append('\n');
                continue;
            }
            out.append(renderInline(line)).append('\n');
        }
        int len = out.length();
        if (len > 0 && out.charAt(len - 1) == '\n') {
            out.setLength(len - 1);
        }
        return out.toString();
    }

    private static String sanitize(String text) {
        // Keep tab, LF, CR, and printable ASCII. Drop everything else (emoji, arrows, box-drawing, NBSP, BOM, etc).
        text = text.replaceAll("[^\\t\\n\\r\\x20-\\x7E]", "");
        // Strip stray ANSI-like patterns the LLM echoes (e.g. "[36m", "[1;33m", or back-to-back "[[36m33m").
        // Loop until stable: one pass can leave an orphaned `[` adjacent to the next code.
        String prev;
        int passes = 0;
        do {
            prev = text;
            text = text.replaceAll("\\[\\d{1,3}(?:;\\d{1,3})*m", "");
        } while (!text.equals(prev) && ++passes < 10);
        // Normalize CRLF and strip trailing spaces per line.
        text = text.replace("\r\n", "\n").replace("\r", "\n");
        text = text.replaceAll("[ \\t]+\\n", "\n");
        // Collapse 3+ consecutive blank lines into a single blank line.
        text = text.replaceAll("\\n{3,}", "\n\n");
        return text;
    }

    private static String trimBlankEdges(String text) {
        return text.replaceAll("^[\\s\\n]+", "").replaceAll("[\\s\\n]+$", "");
    }

    private static String renderInline(String line) {
        // Defensive: strip any stray ANSI-text codes that survived sanitize
        // (e.g. if the response was assembled from sources that bypass render()).
        String prev;
        int passes = 0;
        do {
            prev = line;
            line = line.replaceAll("\\[\\d{1,3}(?:;\\d{1,3})*m", "");
        } while (!line.equals(prev) && ++passes < 10);
        Matcher headerMatcher = HEADER.matcher(line);
        if (headerMatcher.matches()) {
            int level = headerMatcher.group(1).length();
            String text = headerMatcher.group(2);
            String accent = switch (level) {
                case 1 -> Ansi.BRIGHT_CYAN;
                case 2 -> Ansi.BRIGHT_MAGENTA;
                case 3 -> Ansi.BRIGHT_YELLOW;
                default -> Ansi.CYAN;
            };
            return Ansi.wrap(text, Ansi.BOLD, accent);
        }

        Matcher bullet = BULLET.matcher(line);
        if (bullet.find()) {
            line = bullet.replaceFirst(bullet.group(1) + Ansi.wrap("- ", Ansi.BRIGHT_MAGENTA));
        } else {
            Matcher numbered = NUMBERED.matcher(line);
            if (numbered.find()) {
                line = numbered.replaceFirst(numbered.group(1)
                        + Ansi.wrap(numbered.group(2) + ".", Ansi.BRIGHT_MAGENTA) + " ");
            }
        }

        line = INLINE_CODE.matcher(line).replaceAll(matcher -> Matcher.quoteReplacement(Ansi.wrap(matcher.group(1), Ansi.YELLOW)));
        line = BOLD.matcher(line).replaceAll(matcher -> Matcher.quoteReplacement(Ansi.wrap(matcher.group(1), Ansi.BOLD)));
        line = ITALIC.matcher(line).replaceAll(matcher -> Matcher.quoteReplacement(Ansi.wrap(matcher.group(1), Ansi.ITALIC)));
        line = FILE_REF.matcher(line).replaceAll(matcher -> Matcher.quoteReplacement(Ansi.wrap(matcher.group(0), Ansi.CYAN, Ansi.UNDERLINE)));
        return line;
    }
}
