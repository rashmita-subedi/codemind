package com.codemind.cli;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class CommandParser {
    private static final Pattern TOKEN_PATTERN = Pattern.compile("\"([^\"]*)\"|'([^']*)'|(\\S+)");

    public ParsedCommand parse(String[] args) {
        if (args == null || args.length == 0) {
            return new ParsedCommand("help", List.of());
        }
        String name = args[0].trim().toLowerCase();
        List<String> values = new ArrayList<>();
        for (int i = 1; i < args.length; i++) {
            values.add(args[i].trim());
        }
        return new ParsedCommand(name, values);
    }

    public ParsedCommand parseLine(String line) {
        if (line == null || line.isBlank()) {
            return new ParsedCommand("help", List.of());
        }
        List<String> tokens = new ArrayList<>();
        Matcher matcher = TOKEN_PATTERN.matcher(line);
        while (matcher.find()) {
            if (matcher.group(1) != null) {
                tokens.add(matcher.group(1));
            } else if (matcher.group(2) != null) {
                tokens.add(matcher.group(2));
            } else {
                tokens.add(matcher.group(3));
            }
        }
        return parse(tokens.toArray(String[]::new));
    }
}
