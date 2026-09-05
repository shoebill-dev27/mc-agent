package dev.shinobu.mcagent.gui;

import dev.shinobu.mcagent.McAgentRuntime;
import dev.shinobu.mcagent.acp.model.PermissionRequest;
import dev.shinobu.mcagent.acp.model.PermissionRequest.PermissionOption;
import dev.shinobu.mcagent.acp.model.ToolCallDelta;
import dev.shinobu.mcagent.acp.model.ToolContent;
import dev.shinobu.mcagent.diff.LineDiff;
import dev.shinobu.mcagent.security.SessionAccess;
import dev.shinobu.mcagent.session.AgentSession;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The approval dialog: what the agent wants to do, and the buttons to answer.
 *
 * <p>The buttons come from the request rather than from a fixed list, because
 * they are the agent's to offer — a different ACP agent may word them
 * differently or offer a fourth. Only their placement and their icons are ours,
 * and those are fixed by {@code kind} so that "allow once" is always in the
 * same place whatever the agent calls it. An option with a kind this version
 * does not recognise still gets a button, with the agent's own wording on it.
 *
 * <p>"Always allow" is the one that cannot be taken back within a session, so
 * it does not act on the first click: it redraws the dialog as a confirmation,
 * with the confirming button in the far corner from where the click just was.
 */
public final class ApprovalMenu extends ReadOnlyChestMenu {

    private static final int ROWS = 3;
    private static final int SUMMARY_SLOT = 4;
    private static final int LATER_SLOT = 22;
    private static final int BACK_SLOT = 18;
    private static final int CONFIRM_SLOT = 26;

    private final McAgentRuntime runtime;
    private final AgentSession session;
    private final PermissionRequest request;
    private final List<PermissionOption> options;
    private final Map<Integer, PermissionOption> optionSlots = new HashMap<>();

    /** Non-null while the dialog is showing the second confirmation. */
    private PermissionOption confirming;

    public ApprovalMenu(int containerId, Inventory inventory, McAgentRuntime runtime,
                        AgentSession session, PermissionRequest request) {
        super(containerId, inventory, ROWS);
        this.runtime = runtime;
        this.session = session;
        this.request = request;
        this.options = ordered(request);
        drawChoice();
    }

    /** The screen's title. */
    public static Component title(PermissionRequest request) {
        String tool = DiffBook.title(request);
        return Component.literal(tool.length() > 32 ? tool.substring(0, 31) + "…" : tool);
    }

    // ---------------------------------------------------------------- clicks

    @Override
    protected void onButton(ServerPlayer player, int slot, int button) {
        if (!SessionAccess.canControl(runtime.server(), player, session)) {
            // Ownership can change under an open screen; an op can be demoted.
            player.closeContainer();
            return;
        }
        if (session.pendingPermission() != request) {
            player.closeContainer();
            player.sendSystemMessage(Component.literal(
                    "That request is no longer waiting on you.").withStyle(ChatFormatting.GRAY));
            return;
        }

        if (confirming != null) {
            if (slot == CONFIRM_SLOT) {
                decide(player, confirming);
            } else if (slot == BACK_SLOT) {
                confirming = null;
                drawChoice();
                redraw();
            }
            return;
        }

        if (slot == LATER_SLOT) {
            player.closeContainer();
            player.sendSystemMessage(Component.literal(
                    session.name() + " is still waiting. /agent permission reopens this.")
                    .withStyle(ChatFormatting.GRAY));
            return;
        }
        if (slot == SUMMARY_SLOT && DiffBook.hasContent(request)) {
            Approvals.openDiff(player, runtime, session);
            return;
        }

        PermissionOption option = optionSlots.get(slot);
        if (option == null) {
            return;
        }
        if (needsConfirmation(option)) {
            confirming = option;
            drawConfirmation();
            redraw();
        } else {
            decide(player, option);
        }
    }

    /**
     * Durable allows are confirmed twice. Durable refusals are not: the worst a
     * misclick there can do is make the agent ask again later.
     */
    private boolean needsConfirmation(PermissionOption option) {
        return option.allows() && option.isDurable() && runtime.config().approval.confirmAllowAlways;
    }

    private void decide(ServerPlayer player, PermissionOption option) {
        boolean answered = runtime.sessions().decide(session, option.optionId());
        player.closeContainer();
        if (answered) {
            player.sendSystemMessage(Component.literal(session.name() + ": " + label(option))
                    .withStyle(option.allows() ? ChatFormatting.GREEN : ChatFormatting.RED));
        } else {
            player.sendSystemMessage(Component.literal(
                    "Nothing was waiting on that answer.").withStyle(ChatFormatting.GRAY));
        }
    }

    // ---------------------------------------------------------------- layout

    private void drawChoice() {
        clearAll();
        optionSlots.clear();

        put(SUMMARY_SLOT, summaryIcon());

        int[] slots = slotsFor(options.size());
        for (int i = 0; i < options.size() && i < slots.length; i++) {
            optionSlots.put(slots[i], options.get(i));
            put(slots[i], optionIcon(options.get(i)));
        }

        put(LATER_SLOT, Icons.button(Items.BARRIER,
                Component.literal("Decide later").withStyle(ChatFormatting.GRAY),
                List.of(Component.literal("The agent keeps waiting.").withStyle(ChatFormatting.DARK_GRAY),
                        Component.literal("/agent permission reopens this.").withStyle(ChatFormatting.DARK_GRAY))));
        fillEmpty();
    }

    private void drawConfirmation() {
        clearAll();
        put(SUMMARY_SLOT, Icons.button(Items.PAPER,
                Component.literal("Allow this every time?").withStyle(ChatFormatting.GOLD),
                List.of(Component.literal(label(confirming)).withStyle(ChatFormatting.YELLOW),
                        Component.literal("applies for the rest of this").withStyle(ChatFormatting.GRAY),
                        Component.literal("session. You will not be asked").withStyle(ChatFormatting.GRAY),
                        Component.literal("again, and files will change").withStyle(ChatFormatting.GRAY),
                        Component.literal("without you seeing them first.").withStyle(ChatFormatting.GRAY))));
        put(BACK_SLOT, Icons.button(Items.STAINED_GLASS_PANE.lightGray(),
                Component.literal("Back").withStyle(ChatFormatting.WHITE)));
        put(CONFIRM_SLOT, Icons.button(Items.STAINED_GLASS_PANE.yellow(),
                Component.literal("Yes, always allow").withStyle(ChatFormatting.GOLD)));
        fillEmpty();
    }

    /** What is being asked about: the diff if there is one, else the tool call. */
    private ItemStack summaryIcon() {
        List<ToolContent.Diff> diffs = request.diffs();
        if (!diffs.isEmpty()) {
            List<Component> lore = new ArrayList<>();
            for (ToolContent.Diff diff : diffs) {
                LineDiff.Result result = LineDiff.of(diff.oldText(), diff.newText());
                lore.add(Component.literal(DiffBook.shortPath(diff.path()) + "  " + result.summary())
                        .withStyle(ChatFormatting.GRAY));
            }
            lore.add(Component.literal("Click to read the change.").withStyle(ChatFormatting.DARK_GRAY));
            return Icons.button(Items.WRITTEN_BOOK,
                    Component.literal("View the change").withStyle(ChatFormatting.AQUA), lore);
        }

        ToolCallDelta call = request.toolCall();
        List<Component> lore = new ArrayList<>();
        if (call != null && call.kind() != null) {
            lore.add(Component.literal(call.kind()).withStyle(ChatFormatting.GRAY));
        }
        if (call != null && call.locations() != null) {
            for (ToolCallDelta.Location location : call.locations()) {
                lore.add(Component.literal(DiffBook.shortPath(location.path()))
                        .withStyle(ChatFormatting.GRAY));
            }
        }
        return Icons.button(Items.PAPER,
                Component.literal(DiffBook.title(request)).withStyle(ChatFormatting.WHITE), lore);
    }

    private static ItemStack optionIcon(PermissionOption option) {
        String kind = option.kind() == null ? "" : option.kind();
        return switch (kind) {
            case PermissionOption.ALLOW_ONCE -> Icons.button(Items.STAINED_GLASS_PANE.lime(),
                    Component.literal(label(option)).withStyle(ChatFormatting.GREEN),
                    List.of(Component.literal("This time only.").withStyle(ChatFormatting.GRAY)));
            case PermissionOption.ALLOW_ALWAYS -> Icons.button(Items.STAINED_GLASS_PANE.yellow(),
                    Component.literal(label(option)).withStyle(ChatFormatting.GOLD),
                    List.of(Component.literal("For the rest of the session.").withStyle(ChatFormatting.GRAY),
                            Component.literal("Asks you once more first.").withStyle(ChatFormatting.DARK_GRAY)));
            case PermissionOption.REJECT_ONCE -> Icons.button(Items.STAINED_GLASS_PANE.red(),
                    Component.literal(label(option)).withStyle(ChatFormatting.RED),
                    List.of(Component.literal("Nothing is written.").withStyle(ChatFormatting.GRAY),
                            Component.literal("The agent carries on.").withStyle(ChatFormatting.DARK_GRAY)));
            case PermissionOption.REJECT_ALWAYS -> Icons.button(Items.STAINED_GLASS_PANE.red(),
                    Component.literal(label(option)).withStyle(ChatFormatting.DARK_RED),
                    List.of(Component.literal("For the rest of the session.").withStyle(ChatFormatting.GRAY)));
            // A kind this version has never seen. Pass it through rather than
            // hide it: the agent knows what it means even if we do not.
            default -> Icons.button(Items.STAINED_GLASS_PANE.lightGray(),
                    Component.literal(label(option)).withStyle(ChatFormatting.WHITE),
                    List.of(Component.literal(kind.isEmpty() ? "(no kind given)" : kind)
                            .withStyle(ChatFormatting.DARK_GRAY)));
        };
    }

    /** An option's wording, falling back to its id if the agent sent no name. */
    private static String label(PermissionOption option) {
        return option.name() == null || option.name().isBlank() ? option.optionId() : option.name();
    }

    /**
     * Puts the buttons in the middle row, spread out. Allow and refuse are kept
     * apart so a fast click cannot land on the wrong one.
     */
    private static int[] slotsFor(int count) {
        return switch (count) {
            case 0 -> new int[0];
            case 1 -> new int[]{13};
            case 2 -> new int[]{11, 15};
            case 3 -> new int[]{10, 13, 16};
            case 4 -> new int[]{10, 12, 14, 16};
            default -> new int[]{9, 10, 11, 12, 13, 14, 15, 16, 17};
        };
    }

    /**
     * Our order, not the agent's: allow once, allow always, refuse, then
     * anything unrecognised. The dialog should not rearrange itself because an
     * agent listed its options differently.
     */
    private static List<PermissionOption> ordered(PermissionRequest request) {
        List<PermissionOption> ordered = new ArrayList<>();
        for (String kind : List.of(PermissionOption.ALLOW_ONCE, PermissionOption.ALLOW_ALWAYS,
                PermissionOption.REJECT_ONCE, PermissionOption.REJECT_ALWAYS)) {
            for (PermissionOption option : request.options()) {
                if (kind.equals(option.kind())) {
                    ordered.add(option);
                }
            }
        }
        for (PermissionOption option : request.options()) {
            if (!ordered.contains(option)) {
                ordered.add(option);
            }
        }
        return ordered;
    }
}
