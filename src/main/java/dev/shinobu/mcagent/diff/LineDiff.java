package dev.shinobu.mcagent.diff;

import java.util.ArrayList;
import java.util.List;

/**
 * A line diff, for showing a player what a tool is about to change.
 *
 * <p>ACP hands over the whole before and after text of an edit rather than a
 * patch, so the mod has to work out what actually changed. Deliberately free
 * of Minecraft types: it is the part of the approval flow with real logic in
 * it, and it is worth being able to test without booting a game.
 *
 * <p>Cost is kept down by matching off the common head and tail first. A
 * typical agent edit rewrites a few lines of a long file, so the quadratic
 * table is only ever built over the handful of lines in between. When even
 * that is too big the diff degrades to "all of this went, all of that
 * arrived", which is still true, just not minimal.
 */
public final class LineDiff {

    /** Above this many cells the table is not worth building. */
    private static final int MAX_CELLS = 250_000;

    /** Lines either side of a change that are shown for context. */
    public static final int DEFAULT_CONTEXT = 3;

    public enum Kind {
        CONTEXT,
        ADDED,
        REMOVED,
        /** Stands in for the unchanged lines that were left out. */
        GAP
    }

    public record Line(Kind kind, String text) {
    }

    /**
     * @param lines   the rendered diff, unchanged runs already collapsed
     * @param added   lines the change introduces
     * @param removed lines the change takes away
     * @param minimal false when the inputs were too large to align properly
     */
    public record Result(List<Line> lines, int added, int removed, boolean minimal) {

        public boolean isEmpty() {
            return added == 0 && removed == 0;
        }

        /** The "+12 -3" shown next to the diff button. */
        public String summary() {
            return "+" + added + " -" + removed;
        }
    }

    private LineDiff() {
    }

    public static Result of(String oldText, String newText) {
        return of(oldText, newText, DEFAULT_CONTEXT);
    }

    public static Result of(String oldText, String newText, int context) {
        String[] before = split(oldText);
        String[] after = split(newText);

        int prefix = 0;
        while (prefix < before.length && prefix < after.length
                && before[prefix].equals(after[prefix])) {
            prefix++;
        }
        int suffix = 0;
        while (suffix < before.length - prefix && suffix < after.length - prefix
                && before[before.length - 1 - suffix].equals(after[after.length - 1 - suffix])) {
            suffix++;
        }

        int oldCount = before.length - prefix - suffix;
        int newCount = after.length - prefix - suffix;

        List<Line> middle;
        boolean minimal = true;
        if ((long) oldCount * newCount > MAX_CELLS) {
            minimal = false;
            middle = coarse(before, after, prefix, oldCount, newCount);
        } else {
            middle = script(before, after, prefix, oldCount, newCount);
        }

        List<Line> all = new ArrayList<>(middle.size() + prefix + suffix);
        for (int i = 0; i < prefix; i++) {
            all.add(new Line(Kind.CONTEXT, before[i]));
        }
        all.addAll(middle);
        for (int i = before.length - suffix; i < before.length; i++) {
            all.add(new Line(Kind.CONTEXT, before[i]));
        }

        int added = 0;
        int removed = 0;
        for (Line line : all) {
            if (line.kind() == Kind.ADDED) {
                added++;
            } else if (line.kind() == Kind.REMOVED) {
                removed++;
            }
        }
        if (added == 0 && removed == 0) {
            return new Result(List.of(), 0, 0, minimal);
        }
        return new Result(collapse(all, context), added, removed, minimal);
    }

    /** Lines a change introduces, for a summary that does not need the diff itself. */
    public static int addedLines(String oldText, String newText) {
        return of(oldText, newText, 0).added();
    }

    public static int removedLines(String oldText, String newText) {
        return of(oldText, newText, 0).removed();
    }

    // -------------------------------------------------------------- internals

    /**
     * Splits into lines the way a diff wants them: a trailing newline ends the
     * last line rather than starting an empty one, and no text at all - which
     * is how ACP reports a file being created - is no lines rather than one
     * empty line.
     */
    private static String[] split(String text) {
        if (text == null || text.isEmpty()) {
            return new String[0];
        }
        String body = text.endsWith("\n") ? text.substring(0, text.length() - 1) : text;
        return body.split("\n", -1);
    }

    /** The real diff: longest common subsequence over the differing middle. */
    private static List<Line> script(String[] before, String[] after, int offset,
                                     int oldCount, int newCount) {
        int[][] common = new int[oldCount + 1][newCount + 1];
        for (int i = oldCount - 1; i >= 0; i--) {
            for (int j = newCount - 1; j >= 0; j--) {
                common[i][j] = before[offset + i].equals(after[offset + j])
                        ? common[i + 1][j + 1] + 1
                        : Math.max(common[i + 1][j], common[i][j + 1]);
            }
        }

        List<Line> out = new ArrayList<>();
        int i = 0;
        int j = 0;
        while (i < oldCount && j < newCount) {
            if (before[offset + i].equals(after[offset + j])) {
                out.add(new Line(Kind.CONTEXT, before[offset + i]));
                i++;
                j++;
            } else if (common[i + 1][j] >= common[i][j + 1]) {
                // Removals win a tie, so a rewritten line reads as "-old +new"
                // rather than the other way round.
                out.add(new Line(Kind.REMOVED, before[offset + i]));
                i++;
            } else {
                out.add(new Line(Kind.ADDED, after[offset + j]));
                j++;
            }
        }
        while (i < oldCount) {
            out.add(new Line(Kind.REMOVED, before[offset + i++]));
        }
        while (j < newCount) {
            out.add(new Line(Kind.ADDED, after[offset + j++]));
        }
        return out;
    }

    /** The fallback for inputs too large to align line by line. */
    private static List<Line> coarse(String[] before, String[] after, int offset,
                                     int oldCount, int newCount) {
        List<Line> out = new ArrayList<>(oldCount + newCount);
        for (int i = 0; i < oldCount; i++) {
            out.add(new Line(Kind.REMOVED, before[offset + i]));
        }
        for (int j = 0; j < newCount; j++) {
            out.add(new Line(Kind.ADDED, after[offset + j]));
        }
        return out;
    }

    /**
     * Drops unchanged lines that are far from any change, leaving one gap
     * marker in their place. A book page holds about thirteen lines, so
     * showing an untouched file in full would bury the edit.
     */
    private static List<Line> collapse(List<Line> lines, int context) {
        boolean[] keep = new boolean[lines.size()];
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).kind() == Kind.CONTEXT) {
                continue;
            }
            int from = Math.max(0, i - context);
            int to = Math.min(lines.size() - 1, i + context);
            for (int k = from; k <= to; k++) {
                keep[k] = true;
            }
        }

        List<Line> out = new ArrayList<>();
        boolean inGap = false;
        for (int i = 0; i < lines.size(); i++) {
            if (keep[i]) {
                out.add(lines.get(i));
                inGap = false;
            } else if (!inGap) {
                out.add(new Line(Kind.GAP, "…"));
                inGap = true;
            }
        }
        return out;
    }
}
