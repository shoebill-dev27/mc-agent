package dev.shinobu.mcagent;

import dev.shinobu.mcagent.acp.AgentProcessPool;
import dev.shinobu.mcagent.config.ModConfig;
import dev.shinobu.mcagent.entity.AvatarManager;
import dev.shinobu.mcagent.entity.SessionAvatar;
import dev.shinobu.mcagent.gui.Approvals;
import dev.shinobu.mcagent.gui.KillMenu;
import dev.shinobu.mcagent.gui.SessionText;
import dev.shinobu.mcagent.session.SessionManager;
import net.minecraft.server.MinecraftServer;

import java.nio.file.Path;
import java.time.Duration;
import java.util.function.BiConsumer;

/**
 * Everything the mod owns while one server is running.
 *
 * <p>Scoped to a server rather than being static so that leaving a
 * singleplayer world and opening another starts clean, with no agent from the
 * previous world still running and no session pointing at a level that no
 * longer exists.
 */
public final class McAgentRuntime {

    private final MinecraftServer server;
    private final ModConfig config;
    private final AgentProcessPool pool;
    private final SessionManager sessions;
    private final AvatarManager avatars;

    private McAgentRuntime(MinecraftServer server, ModConfig config, AgentProcessPool pool,
                           SessionManager sessions, AvatarManager avatars) {
        this.server = server;
        this.config = config;
        this.pool = pool;
        this.sessions = sessions;
        this.avatars = avatars;
    }

    public static McAgentRuntime start(MinecraftServer server) {
        BiConsumer<String, Throwable> diagnostics = (message, error) -> {
            if (error == null) {
                McAgent.LOGGER.warn(message);
            } else {
                McAgent.LOGGER.warn(message, error);
            }
        };

        Path configFile = server.getFile("config").resolve(McAgent.MOD_ID + ".json");
        ModConfig config = ModConfig.load(configFile, diagnostics);

        AgentProcessPool pool = new AgentProcessPool(
                Duration.ofSeconds(config.process.idleShutdownSeconds), null, null, diagnostics);

        // server::execute is the hop onto the server thread that every ACP
        // event takes before it is allowed to touch the world.
        SessionManager sessions = new SessionManager(server::execute, pool, diagnostics);

        AvatarManager avatars = new AvatarManager(server,
                new SessionAvatar.AvatarSettings(config.avatar.panelOffsetY, config.avatar.panelScale,
                        config.avatar.orbitRadius, config.avatar.viewRange),
                config.avatar.focusRadius, diagnostics);
        sessions.addObserver(avatars);

        McAgentRuntime runtime = new McAgentRuntime(server, config, pool, sessions, avatars);
        sessions.addObserver(Approvals.notifier(runtime));

        // Poking an avatar is the in-world way of asking it what it wants.
        avatars.setUseAction((player, session) -> {
            if (session.hasPendingPermission()) {
                Approvals.open(player, runtime, session);
            } else {
                player.sendSystemMessage(SessionText.summary(session));
            }
        });

        // Hitting one is how a session ends, once you say you meant it.
        avatars.setHitAction((player, session) -> KillMenu.open(player, runtime, session));

        McAgent.LOGGER.info("mc-agent ready: {} agent(s) configured, {} workspace root(s)",
                config.agents.size(), config.workspaceRoots.size());
        return runtime;
    }

    public MinecraftServer server() {
        return server;
    }

    public ModConfig config() {
        return config;
    }

    public SessionManager sessions() {
        return sessions;
    }

    public AvatarManager avatars() {
        return avatars;
    }

    /** Called once per server tick, from the end-of-tick event. */
    public void tick() {
        avatars.tick();
    }

    /**
     * Shuts everything down. Closing the sessions closes the pool with them,
     * which is what stops an adapter outliving the server that started it.
     */
    public void stop() {
        avatars.removeAll();
        sessions.close();
    }
}
