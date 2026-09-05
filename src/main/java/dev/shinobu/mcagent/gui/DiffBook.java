package dev.shinobu.mcagent.gui;

import dev.shinobu.mcagent.acp.model.PermissionRequest;
import dev.shinobu.mcagent.acp.model.ToolContent;
import dev.shinobu.mcagent.diff.LineDiff;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WrittenBookContent;

import java.util.ArrayList;
import java.util.List;

/**
 * The diff, as a written book.
 *
 * <p>A book page is about nineteen characters wide and thirteen lines tall and
 * cannot scroll sideways, which rules out the usual diff layout. So: no line
 * numbers, three lines of context, long lines clipped with an ellipsis, and
 * unchanged stretches replaced by a single marker. What matters is "what is it
 * about to write, and where", not a faithful rendering of a patch.
 *
 * <p>The last page carries the decision itself as clickable text, so a player
 * who has just read the change can answer without closing the book and finding
 * the dialog again. Those run the same {@code /agent} commands the dialog does,
 * and the server re-checks who is asking.
 */
public final class DiffBook {

    /** Characters that fit across a page in the default font. */
    private static final int COLUMNS = 19;

    /** Lines of body text on one page. */
    private static final int LINES_PER_PAGE = 13;

    /** Vanilla allows a hundred; leave room for the index and the actions. */
    private static final int MAX_PAGES = 90;

    private DiffBook() {
    }

    /** Whether there is anything worth opening a book for. */
    public static boolean hasContent(PermissionRequest request) {
        return request != null && !request.diffs().isEmpty();
    }

    /**
     * Builds the book for a request.
     *
     * @param decidable whether to offer the decision on the last page; false
     *                  for a reader who is not allowed to answer
     */
    public static ItemStack build(PermissionRequest request, String sessionName, boolean decidable) {
        List<Filterable<Component>> pages = new ArrayList<>();
        List<ToolContent.Diff> diffs = request.diffs();

        if (diffs.size() > 1) {
            pages.add(page(index(diffs)));
        }
        for (ToolContent.Diff diff : diffs) {
            int budget = MAX_PAGES - pages.size();
            if (budget <= 0) {
                pages.add(page(List.of(line("(too much to show)", ChatFormatting.DARK_GRAY))));
                break;
            }
            pages.addAll(pagesFor(diff, budget));
        }
        pages.add(page(actions(request, decidable)));

        ItemStack book = new ItemStack(Items.WRITTEN_BOOK);
        book.set(DataComponents.WRITTEN_BOOK_CONTENT, new WrittenBookContent(
                Filterable.passThrough(clip(title(request), WrittenBookContent.TITLE_MAX_LENGTH)),
                sessionName,
                0,
                pages,
                // Already resolved: the pages hold finished components, so the
                // server must not try to expand selectors in them.
                true));
        return book;
    }

    public static String title(PermissionRequest request) {
        if (request.toolCall() != null && request.toolCall().title() != null) {
            return request.toolCall().title();
        }
        return "Approval";
    }

    // ------------------------------------------------------------------ pages

    private static List<Component> index(List<ToolContent.Diff> diffs) {
        List<Component> lines = new ArrayList<>();
        lines.add(line(diffs.size() + " files", ChatFormatting.BLACK));
        lines.add(Component.empty());
        for (ToolContent.Diff diff : diffs) {
            LineDiff.Result result = LineDiff.of(diff.oldText(), diff.newText());
            lines.add(line(shortPath(diff.path()), ChatFormatting.DARK_GRAY));
            lines.add(line("  " + result.summary(),
                    result.added() >= result.removed() ? ChatFormatting.DARK_GREEN : ChatFormatting.DARK_RED));
        }
        return lines;
    }

    private static List<Filterable<Component>> pagesFor(ToolContent.Diff diff, int budget) {
        LineDiff.Result result = LineDiff.of(diff.oldText(), diff.newText());

        List<Component> body = new ArrayList<>();
        body.add(line(shortPath(diff.path()), ChatFormatting.BLACK));
        body.add(line(result.summary(), ChatFormatting.DARK_GRAY));
        if (!result.minimal()) {
            body.add(line("(rewritten whole)", ChatFormatting.DARK_GRAY));
        }
        if (result.isEmpty()) {
            body.add(line("no change", ChatFormatting.DARK_GRAY));
        }
        for (LineDiff.Line change : result.lines()) {
            body.add(switch (change.kind()) {
                case ADDED -> line(clip("+" + change.text(), COLUMNS), ChatFormatting.DARK_GREEN);
                case REMOVED -> line(clip("-" + change.text(), COLUMNS), ChatFormatting.DARK_RED);
                case CONTEXT -> line(clip(" " + change.text(), COLUMNS), ChatFormatting.DARK_GRAY);
                case GAP -> line("   …", ChatFormatting.GRAY);
            });
        }

        List<Filterable<Component>> pages = new ArrayList<>();
        for (int from = 0; from < body.size() && pages.size() < budget; from += LINES_PER_PAGE) {
            pages.add(page(body.subList(from, Math.min(body.size(), from + LINES_PER_PAGE))));
        }
        return pages;
    }

    private static List<Component> actions(PermissionRequest request, boolean decidable) {
        List<Component> lines = new ArrayList<>();
        lines.add(line("Decision", ChatFormatting.BLACK));
        lines.add(Component.empty());
        if (!decidable) {
            lines.add(line("Not yours to answer.", ChatFormatting.DARK_GRAY));
            return lines;
        }
        request.optionOfKind(PermissionRequest.PermissionOption.ALLOW_ONCE)
                .ifPresent(option -> lines.add(command("[ " + option.name() + " ]",
                        "/agent approve", ChatFormatting.DARK_GREEN)));
        lines.add(Component.empty());
        request.safeRefusal()
                .ifPresent(option -> lines.add(command("[ " + option.name() + " ]",
                        "/agent deny", ChatFormatting.DARK_RED)));
        lines.add(Component.empty());
        lines.add(line("Always-allow lives in", ChatFormatting.GRAY));
        lines.add(line("the dialog, behind a", ChatFormatting.GRAY));
        lines.add(line("confirmation.", ChatFormatting.GRAY));
        return lines;
    }

    // ----------------------------------------------------------------- pieces

    private static Filterable<Component> page(List<Component> lines) {
        MutableComponent page = Component.empty();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                page.append("\n");
            }
            page.append(lines.get(i));
        }
        return Filterable.passThrough(page);
    }

    private static Component line(String text, ChatFormatting colour) {
        return Component.literal(text).withStyle(colour);
    }

    private static Component command(String label, String command, ChatFormatting colour) {
        return Component.literal(label).withStyle(style -> style
                .withColor(colour)
                .withBold(Boolean.TRUE)
                .withClickEvent(new ClickEvent.RunCommand(command)));
    }

    /** The tail of a path, which is the part that identifies the file. */
    static String shortPath(String path) {
        if (path == null || path.isBlank()) {
            return "(unknown file)";
        }
        String normalised = path.replace('\\', '/');
        int lastSlash = normalised.lastIndexOf('/');
        int previousSlash = lastSlash <= 0 ? -1 : normalised.lastIndexOf('/', lastSlash - 1);
        String tail = previousSlash < 0 ? normalised : normalised.substring(previousSlash + 1);
        if (tail.length() <= COLUMNS) {
            return tail;
        }
        return "…" + tail.substring(tail.length() - (COLUMNS - 1));
    }

    private static String clip(String text, int max) {
        String flat = text.replace("\t", "  ").replace("\r", "");
        if (flat.length() <= max) {
            return flat;
        }
        return flat.substring(0, max - 1) + "…";
    }
}
