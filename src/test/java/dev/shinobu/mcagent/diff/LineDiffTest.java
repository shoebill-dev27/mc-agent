package dev.shinobu.mcagent.diff;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The diff is what a player reads before letting an agent write to their disk,
 * so "+1 -0" had better mean one line added and none lost.
 */
class LineDiffTest {

    /** Renders a result the way the book does, for readable assertions. */
    private static String render(LineDiff.Result result) {
        return result.lines().stream()
                .map(line -> switch (line.kind()) {
                    case ADDED -> "+" + line.text();
                    case REMOVED -> "-" + line.text();
                    case CONTEXT -> " " + line.text();
                    case GAP -> "~";
                })
                .collect(Collectors.joining("\n"));
    }

    @Test
    void identicalTextHasNoDiffAtAll() {
        LineDiff.Result result = LineDiff.of("a\nb\nc", "a\nb\nc");

        assertTrue(result.isEmpty());
        assertTrue(result.lines().isEmpty(), "nothing changed, so there is nothing to show");
    }

    /**
     * How ACP reports a file being created: no old text at all. It must not
     * come out as one removed empty line.
     */
    @Test
    void creatingAFileIsAllAdditions() {
        LineDiff.Result result = LineDiff.of(null, "hello\nworld\n");

        assertEquals(2, result.added());
        assertEquals(0, result.removed());
        assertEquals("+hello\n+world", render(result));
    }

    @Test
    void emptyingAFileIsAllRemovals() {
        LineDiff.Result result = LineDiff.of("hello\nworld\n", "");

        assertEquals(0, result.added());
        assertEquals(2, result.removed());
    }

    /** A trailing newline ends the last line; it does not start an empty one. */
    @Test
    void aTrailingNewlineIsNotItsOwnLine() {
        assertEquals(0, LineDiff.of("a\nb\n", "a\nb").added());
        assertEquals(0, LineDiff.of("a\nb\n", "a\nb").removed());
    }

    @Test
    void aRewrittenLineShowsTheOldOneFirst() {
        LineDiff.Result result = LineDiff.of("a\nold\nc", "a\nnew\nc");

        assertEquals(1, result.added());
        assertEquals(1, result.removed());
        assertEquals(" a\n-old\n+new\n c", render(result));
        assertEquals("+1 -1", result.summary());
    }

    @Test
    void insertionsAndDeletionsAreNotConfusedForEachOther() {
        LineDiff.Result result = LineDiff.of("a\nb\nc", "a\nb\nx\nc");

        assertEquals(1, result.added());
        assertEquals(0, result.removed());
        assertEquals(" a\n b\n+x\n c", render(result));
    }

    /**
     * The case the book exists for: one edit deep inside a long file. Only the
     * change and its context survive, with gaps standing in for the rest.
     */
    @Test
    void unchangedRunsFarFromAChangeCollapseIntoAGap() {
        String before = IntStream.range(0, 50).mapToObj(Integer::toString)
                .collect(Collectors.joining("\n"));
        String after = before.replace("\n25\n", "\ntwenty-five\n");

        LineDiff.Result result = LineDiff.of(before, after, 3);

        assertEquals(1, result.added());
        assertEquals(1, result.removed());
        assertEquals("~\n 22\n 23\n 24\n-25\n+twenty-five\n 26\n 27\n 28\n~", render(result));
    }

    @Test
    void separateChangesEachKeepTheirOwnContext() {
        String before = IntStream.range(0, 40).mapToObj(Integer::toString)
                .collect(Collectors.joining("\n"));
        String after = before.replace("\n5\n", "\nfive\n").replace("\n30\n", "\nthirty\n");

        LineDiff.Result result = LineDiff.of(before, after, 2);

        List<LineDiff.Line> gaps = result.lines().stream()
                .filter(line -> line.kind() == LineDiff.Kind.GAP)
                .toList();
        assertEquals(3, gaps.size(), "before, between and after the two changes: " + render(result));
    }

    /**
     * A whole-file rewrite of something enormous. The table would be far too
     * big, so the diff gives up on aligning and says so - but the counts and
     * the content must still be right.
     */
    @Test
    void anEnormousRewriteFallsBackWithoutLying() {
        String before = IntStream.range(0, 600).mapToObj(i -> "old " + i)
                .collect(Collectors.joining("\n"));
        String after = IntStream.range(0, 600).mapToObj(i -> "new " + i)
                .collect(Collectors.joining("\n"));

        LineDiff.Result result = LineDiff.of(before, after);

        assertFalse(result.minimal(), "600x600 is past the point of aligning line by line");
        assertEquals(600, result.added());
        assertEquals(600, result.removed());
    }

    /** The same size of file, edited in one place, stays cheap and exact. */
    @Test
    void anEnormousFileWithOneEditIsStillDiffedProperly() {
        String before = IntStream.range(0, 5000).mapToObj(i -> "line " + i)
                .collect(Collectors.joining("\n"));
        String after = before.replace("\nline 2500\n", "\nline 2500 edited\n");

        LineDiff.Result result = LineDiff.of(before, after);

        assertTrue(result.minimal(), "trimming the common head and tail should keep this small");
        assertEquals(1, result.added());
        assertEquals(1, result.removed());
    }

    @Test
    void countHelpersAgreeWithTheDiff() {
        assertEquals(1, LineDiff.addedLines("a\nb", "a\nb\nc"));
        assertEquals(0, LineDiff.removedLines("a\nb", "a\nb\nc"));
        assertEquals(1, LineDiff.addedLines(null, "hello"));
        assertEquals(0, LineDiff.removedLines(null, "hello"));
        assertEquals(0, LineDiff.addedLines(null, null));
    }
}
