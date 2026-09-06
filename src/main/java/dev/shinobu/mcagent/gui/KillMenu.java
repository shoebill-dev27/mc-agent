package dev.shinobu.mcagent.gui;

import dev.shinobu.mcagent.McAgentRuntime;
import dev.shinobu.mcagent.security.SessionAccess;
import dev.shinobu.mcagent.session.AgentSession;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

/**
 * "You hit this session. Did you mean to end it?"
 *
 * <p>Hitting an avatar is how a session dies, but a swing is cheap and a
 * session is not — the turn in flight is lost and the adapter may go with it —
 * so the hit only opens this. Confirming sits in the bottom-right corner, as
 * far from the middle of the screen as the layout allows.
 */
public final class KillMenu extends ReadOnlyChestMenu {

    private static final int ROWS = 3;
    private static final int SUMMARY_SLOT = 4;
    private static final int KEEP_SLOT = 18;
    private static final int END_SLOT = 26;

    private final McAgentRuntime runtime;
    private final AgentSession session;

    public KillMenu(int containerId, Inventory inventory, McAgentRuntime runtime, AgentSession session) {
        super(containerId, inventory, ROWS);
        this.runtime = runtime;
        this.session = session;
        draw();
    }

    /** Asks a player whether to end a session, if they are allowed to. */
    public static void open(ServerPlayer player, McAgentRuntime runtime, AgentSession session) {
        if (!SessionAccess.canControl(runtime.server(), player, session)) {
            return;
        }
        if (player.containerMenu instanceof KillMenu already && already.isAsking(session)) {
            // A second swing while the question is up is the same question.
            return;
        }
        player.openMenu(new SimpleMenuProvider(
                (containerId, inventory, viewer) -> new KillMenu(containerId, inventory, runtime, session),
                Component.literal("End " + session.name() + "?")));
    }

    boolean isAsking(AgentSession about) {
        return this.session == about;
    }

    @Override
    protected void onButton(ServerPlayer player, int slot, int button) {
        if (!SessionAccess.canControl(runtime.server(), player, session)) {
            player.closeContainer();
            return;
        }
        if (slot == KEEP_SLOT) {
            player.closeContainer();
            return;
        }
        if (slot != END_SLOT) {
            return;
        }

        player.closeContainer();
        if (session.isClosed()) {
            player.sendSystemMessage(Component.literal(session.name() + " had already ended.")
                    .withStyle(ChatFormatting.GRAY));
            return;
        }
        runtime.sessions().close(session);
        player.sendSystemMessage(Component.literal("Ended " + session.name())
                .withStyle(ChatFormatting.GRAY));
    }

    private void draw() {
        clearAll();

        List<Component> lore = new ArrayList<>();
        lore.add(Component.literal(session.workspace()).withStyle(ChatFormatting.DARK_GRAY));
        lore.add(Component.literal(SessionText.activity(session)).withStyle(ChatFormatting.GRAY));
        if (session.hasPendingPermission()) {
            lore.add(Component.literal("Its pending request will be refused.")
                    .withStyle(ChatFormatting.RED));
        }
        lore.add(Component.literal("The conversation is not kept.").withStyle(ChatFormatting.GRAY));

        put(SUMMARY_SLOT, Icons.button(Items.PAPER,
                Component.literal(session.name()).withStyle(ChatFormatting.WHITE), lore));
        put(KEEP_SLOT, Icons.button(Items.STAINED_GLASS_PANE.lightGray(),
                Component.literal("Leave it running").withStyle(ChatFormatting.WHITE)));
        put(END_SLOT, Icons.button(Items.STAINED_GLASS_PANE.red(),
                Component.literal("End the session").withStyle(ChatFormatting.RED)));
        fillEmpty();
    }
}
