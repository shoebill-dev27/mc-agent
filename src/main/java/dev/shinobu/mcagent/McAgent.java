package dev.shinobu.mcagent;

import dev.shinobu.mcagent.command.AgentCommands;
import dev.shinobu.mcagent.gui.Approvals;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.world.InteractionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Common entrypoint. Runs on dedicated servers and on the integrated server in
 * singleplayer, and owns everything that touches the world: sessions, avatars,
 * terminal walls, GUIs and the ACP connections behind them.
 */
public class McAgent implements ModInitializer {

    public static final String MOD_ID = "mcagent";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    /** Non-null only while a server is running. */
    private static volatile McAgentRuntime runtime;

    /** The running server's state, or null between worlds. */
    public static McAgentRuntime runtime() {
        return runtime;
    }

    @Override
    public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> runtime = McAgentRuntime.start(server));

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            McAgentRuntime stopping = runtime;
            runtime = null;
            if (stopping != null) {
                // Before the world goes: an adapter left running would outlive
                // the server that started it.
                stopping.stop();
            }
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            McAgentRuntime current = runtime;
            if (current != null) {
                current.tick();
            }
        });

        // Hitting an avatar is how a session is ended. The damage itself is
        // always swallowed — the body is invulnerable and ending a session goes
        // through a confirmation, so a stray swing costs nothing.
        AttackEntityCallback.EVENT.register((player, level, hand, entity, hitResult) -> {
            McAgentRuntime current = runtime;
            if (current == null || level.isClientSide()) {
                return InteractionResult.PASS;
            }
            return current.avatars().handleAttack(player, entity)
                    ? InteractionResult.FAIL
                    : InteractionResult.PASS;
        });

        // Right-clicking an avatar asks it what it wants. Always consumed, for
        // everyone: an allay's own use behaviour is to pocket the item you are
        // holding, and a session avatar must never take someone's tools.
        UseEntityCallback.EVENT.register((player, level, hand, entity, hitResult) -> {
            McAgentRuntime current = runtime;
            if (current == null || level.isClientSide()) {
                return InteractionResult.PASS;
            }
            return current.avatars().handleUse(player, entity)
                    ? InteractionResult.SUCCESS_SERVER
                    : InteractionResult.PASS;
        });

        CommandRegistrationCallback.EVENT.register(
                (dispatcher, registryAccess, environment) -> AgentCommands.register(dispatcher));

        // Requests wait indefinitely by default, so someone who logged off
        // mid-approval needs telling what is still hanging on them.
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            McAgentRuntime current = runtime;
            if (current != null) {
                Approvals.greet(current, handler.getPlayer());
            }
        });

        LOGGER.info("mc-agent initialised");
    }
}
