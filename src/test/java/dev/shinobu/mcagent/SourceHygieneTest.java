package dev.shinobu.mcagent;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards against invisible characters getting into the source tree.
 *
 * <p>This has happened twice: a stray NUL byte inside a string literal, in
 * both cases where a space was intended. It compiles, so nothing complains —
 * but git switches the file to binary, diffs become unreadable, and a
 * separator silently stops being what it looks like. A deliberate control
 * character should be written as an escape ({@code '\\u0000'}), which this
 * check allows because the escape itself is plain ASCII.
 */
class SourceHygieneTest {

    /** Everything except tab, newline and carriage return. */
    private static boolean isForbidden(byte b) {
        int value = b & 0xFF;
        return value < 0x09 || value == 0x0B || value == 0x0C || (value >= 0x0E && value <= 0x1F);
    }

    @Test
    void noSourceFileContainsAStrayControlCharacter() throws IOException {
        Path root = Path.of("src");
        assertTrue(Files.isDirectory(root), "expected to run from the project root");

        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                byte[] bytes = Files.readAllBytes(file);
                for (int i = 0; i < bytes.length; i++) {
                    if (isForbidden(bytes[i])) {
                        offenders.add("%s at byte %d (0x%02X): %s"
                                .formatted(file, i, bytes[i], excerpt(bytes, i)));
                        break;
                    }
                }
            }
        }

        assertTrue(offenders.isEmpty(),
                "control characters found; write them as escapes instead:\n  "
                        + String.join("\n  ", offenders));
    }

    private static String excerpt(byte[] bytes, int index) {
        int from = Math.max(0, index - 40);
        int to = Math.min(bytes.length, index + 20);
        return new String(bytes, from, to - from, StandardCharsets.ISO_8859_1)
                .replace("\n", "\\n")
                .replace("\u0000", "<NUL>");
    }
}
