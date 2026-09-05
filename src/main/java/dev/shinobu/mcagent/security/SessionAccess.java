package dev.shinobu.mcagent.security;

import dev.shinobu.mcagent.session.AgentSession;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Who is allowed to do what to a session.
 *
 * <p>A session edits real files and runs real commands on the host, so this is
 * the one check that has to be right, and it lives in a single place rather
 * than being repeated at every call site. Every one of those checks belongs on
 * the <em>server</em>: a client is free to send whatever packet it likes.
 *
 * <p>Watching is open to everyone. The terminal wall and the avatars are
 * ordinary entities, so anyone nearby can read them — that is the point of
 * putting agents in a shared world. Acting is not.
 */
public final class SessionAccess {

    private SessionAccess() {
    }

    /**
     * Whether {@code player} may prompt, approve, interrupt or end this
     * session. Its owner may; so may an operator, who can already do anything
     * on the server and needs a way to clear up abandoned sessions.
     */
    public static boolean canControl(MinecraftServer server, ServerPlayer player, AgentSession session) {
        if (player == null || session == null) {
            return false;
        }
        return session.ownerId().equals(player.getUUID()) || isOperator(server, player);
    }

    /** True for players on the server's ops list. */
    public static boolean isOperator(MinecraftServer server, ServerPlayer player) {
        return server != null && player != null
                && server.getPlayerList().isOp(player.nameAndId());
    }
}
