package dev.shinobu.mcagent.acp;

import dev.shinobu.mcagent.acp.model.PermissionRequest;
import dev.shinobu.mcagent.acp.model.SessionUpdate;

import java.util.concurrent.CompletableFuture;

/**
 * Everything one session pushes at us.
 *
 * <p><b>Threading:</b> every method here is called on the connection's reader
 * thread, never on the Minecraft server thread. Implementations must not touch
 * the world directly — hand the event to the server thread (via
 * {@code MinecraftServer.execute}) and return immediately.
 */
public interface SessionListener {

    /** A streamed message, thought, tool state change, or usage report. */
    void onUpdate(SessionUpdate update);

    /**
     * The agent wants permission to run a tool.
     *
     * @return the {@code optionId} the player chose. Completing with null (or
     *         returning null) is treated as cancelling the request. The future
     *         may be completed much later — while a player walks over to the
     *         avatar and reads the diff — without blocking anything else.
     */
    CompletableFuture<String> onPermissionRequest(PermissionRequest request);

    /** The connection died: process exit, transport failure, or local close. */
    default void onDisconnected(String reason) {
    }
}
