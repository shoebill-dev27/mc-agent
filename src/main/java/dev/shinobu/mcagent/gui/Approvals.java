package dev.shinobu.mcagent.gui;

import dev.shinobu.mcagent.McAgentRuntime;
import dev.shinobu.mcagent.acp.model.PermissionRequest;
import dev.shinobu.mcagent.security.SessionAccess;
import dev.shinobu.mcagent.session.AgentSession;
import dev.shinobu.mcagent.session.SessionManager;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Getting a permission request in front of the person who has to answer it.
 *
 * <p>Nothing here opens a screen on its own. Being yanked into an inventory
 * while mining is worse than being told, and the request may arrive while the
 * owner is nowhere near their avatar — or not logged in at all. So the avatar
 * turns red and rings, a line appears in chat with the dialog one click away,
 * and anyone who missed it is told again when they next log in. The request
 * waits as long as it takes.
 */
public final class Approvals {

    private Approvals() {
    }

    /** Tells the owner, whenever a session starts waiting on one. */
    public static SessionManager.Observer notifier(McAgentRuntime runtime) {
        return new SessionManager.Observer() {
            @Override
            public void onPermissionRequested(AgentSession session, PermissionRequest request) {
                ServerPlayer owner = runtime.server().getPlayerList().getPlayer(session.ownerId());
                if (owner != null) {
                    owner.sendSystemMessage(announcement(session, request));
                }
            }
        };
    }

    /** Repeats anything still waiting, for someone who has just logged in. */
    public static void greet(McAgentRuntime runtime, ServerPlayer player) {
        for (AgentSession session : waitingFor(runtime, player)) {
            player.sendSystemMessage(announcement(session, session.pendingPermission()));
        }
    }

    // ------------------------------------------------------------------ menus

    /** Opens the approval dialog, or explains why there is nothing to open. */
    public static void open(ServerPlayer player, McAgentRuntime runtime, AgentSession session) {
        PermissionRequest request = session.pendingPermission();
        if (request == null) {
            player.sendSystemMessage(Component.literal(session.name() + " is not waiting on you.")
                    .withStyle(ChatFormatting.GRAY));
            return;
        }
        if (!SessionAccess.canControl(runtime.server(), player, session)) {
            player.sendSystemMessage(Component.literal(
                    session.name() + " belongs to " + session.ownerName() + ".")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        player.openMenu(new SimpleMenuProvider(
                (containerId, inventory, viewer) ->
                        new ApprovalMenu(containerId, inventory, runtime, session, request),
                ApprovalMenu.title(request)));
    }

    /**
     * Opens the diff as a book. Readable by anyone who can see the session -
     * watching is open, and the buttons on the last page are only drawn for
     * someone who may actually answer.
     */
    public static void openDiff(ServerPlayer player, McAgentRuntime runtime, AgentSession session) {
        PermissionRequest request = session.pendingPermission();
        if (request == null) {
            player.sendSystemMessage(Component.literal(session.name() + " is not waiting on you.")
                    .withStyle(ChatFormatting.GRAY));
            return;
        }
        if (!DiffBook.hasContent(request)) {
            player.sendSystemMessage(Component.literal(
                    "That request has no file change to show: " + DiffBook.title(request))
                    .withStyle(ChatFormatting.GRAY));
            return;
        }
        boolean decidable = SessionAccess.canControl(runtime.server(), player, session);
        ItemStack book = DiffBook.build(request, session.name(), decidable);
        player.openMenu(new SimpleMenuProvider(
                (containerId, inventory, viewer) -> new DiffBookMenu(containerId, book),
                Component.literal(DiffBook.title(request))));
    }

    // ------------------------------------------------------------- resolution

    /**
     * Which session a bare {@code /agent approve} means.
     *
     * <p>The one the player is standing at, if it is the one asking; otherwise
     * the only one asking. With more than one waiting and none in focus there
     * is no safe guess, so the caller is expected to say so rather than pick.
     */
    public static AgentSession resolve(McAgentRuntime runtime, ServerPlayer player, String name) {
        List<AgentSession> waiting = waitingFor(runtime, player);
        if (name != null && !name.isBlank()) {
            for (AgentSession session : waiting) {
                if (session.name().equalsIgnoreCase(name.trim())) {
                    return session;
                }
            }
            return null;
        }

        AgentSession focused = runtime.avatars().focusedSession(player);
        if (focused != null && waiting.contains(focused)) {
            return focused;
        }
        return waiting.size() == 1 ? waiting.get(0) : null;
    }

    /** Sessions this player may answer for that are waiting on a decision. */
    public static List<AgentSession> waitingFor(McAgentRuntime runtime, ServerPlayer player) {
        List<AgentSession> waiting = new ArrayList<>();
        for (AgentSession session : runtime.sessions().sessions()) {
            if (session.hasPendingPermission()
                    && SessionAccess.canControl(runtime.server(), player, session)) {
                waiting.add(session);
            }
        }
        return waiting;
    }

    // ------------------------------------------------------------------- text

    private static Component announcement(AgentSession session, PermissionRequest request) {
        MutableComponent line = Component.literal("⚠ ").withStyle(ChatFormatting.RED)
                .append(Component.literal(session.name()).withStyle(ChatFormatting.WHITE))
                .append(Component.literal(" needs approval: ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(request == null ? "?" : DiffBook.title(request))
                        .withStyle(ChatFormatting.YELLOW))
                .append(Component.literal("  "))
                .append(link("[Decide]", "/agent permission " + session.name(), ChatFormatting.AQUA));
        if (request != null && DiffBook.hasContent(request)) {
            line.append(Component.literal(" "))
                    .append(link("[Diff]", "/agent diff " + session.name(), ChatFormatting.AQUA));
        }
        return line;
    }

    private static Component link(String label, String command, ChatFormatting colour) {
        return Component.literal(label).withStyle(style -> style
                .withColor(colour)
                .withClickEvent(new ClickEvent.RunCommand(command)));
    }
}
