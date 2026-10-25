package com.codemind.service.io;

import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class BackupNaming {

    private static final String SEP = "__";
    private static final Pattern BACKUP_PATTERN = Pattern.compile("^(.+)\\.(\\d+)\\.bak$");

    private BackupNaming() {
    }

    public static String encode(Path target) {
        Path absolute = target.toAbsolutePath().normalize();
        Path cwd = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        Path relativeOrAbsolute;
        try {
            relativeOrAbsolute = cwd.relativize(absolute);
        } catch (IllegalArgumentException e) {
            // why: Windows can throw when cwd and target live on different drive roots;
            // fall back to the absolute path with the drive colon stripped so the result
            // is a legal filename component on every OS.
            relativeOrAbsolute = absolute;
        }
        return relativeOrAbsolute.toString()
                .replace('\\', '/')
                .replace(":", "")
                .replace("/", SEP);
    }

    public static Path decode(String encodedPart) {
        Path cwd = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        return decode(encodedPart, cwd);
    }

    public static Path decode(String encodedPart, Path base) {
        String slashed = encodedPart.replace(SEP, "/");
        Path candidate = Path.of(slashed.replace('/', java.io.File.separatorChar));
        if (candidate.isAbsolute()) {
            return candidate.normalize();
        }
        return base.resolve(candidate).normalize();
    }

    public static Optional<Parsed> parse(String backupFilename) {
        Matcher m = BACKUP_PATTERN.matcher(backupFilename);
        if (!m.matches()) {
            return Optional.empty();
        }
        try {
            long nanos = Long.parseLong(m.group(2));
            return Optional.of(new Parsed(m.group(1), nanos));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    public record Parsed(String encodedPath, long nanoTime) {
    }
}
