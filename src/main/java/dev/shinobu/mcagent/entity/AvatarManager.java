package dev.shinobu.mcagent.entity;

import dev.shinobu.mcagent.security.SessionAccess;
import dev.shinobu.mcagent.session.AgentSession;
import dev.shinobu.mcagent.session.SessionManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * Keeps an avatar in the world for every live session.
 *
 * <p>Sits between {@link SessionManager}, which knows what sessions are doing,
 * and {@link SessionAvatar}, which knows how to show it. All of it runs on the
 * server thread: the manager has already marshalled events across by the time
 * they arrive here.
 */
public final class AvatarManager implements SessionManager.Observer {

    /** How far a player can be from an avatar and still have it in focus. */
    private final double focusRadius;

    private final Map<UUID, SessionAvatar> avatars = new LinkedHashMap<>();

    /** Remembers who each player last dealt with, so commands need no argument. */
    private final Map<UUID, UUID> focus = new LinkedHashMap<>();

    private final MinecraftServer server;
    private final SessionAvatar.AvatarSettings settings;
    private final BiConsumer<String, Throwable> diagnostics;

    /** Asked before a session is ended by force, so a misclick cannot do it. */
    private KillConfirmation killConfirmation = (player, session) -> true;

    /** Opens whatever a player should see when they walk up and ask. */
    private Interaction interaction = (player, session) -> {
    };

    /** What right-clicking an avatar does. Set by the mod; the GUI lives elsewhere. */
    @FunctionalInterface
    public interface Interaction {
        void onUse(ServerPlayer player, AgentSession session);
    }

    /** Decides whether hitting an avatar should end its session now. */
    @FunctionalInterface
    public interface KillConfirmation {
        /**
         * @return true to end the session immediately; false to swallow the hit,
         *         having presumably opened a confirmation screen instead
         */
        boolean confirm(ServerPlayer player, AgentSession session);
    }

    public AvatarManager(MinecraftServer server, SessionAvatar.AvatarSettings settings,
                         double focusRadius, BiConsumer<String, Throwable> diagnostics) {
        this.server = server;
        this.settings = settings == null ? SessionAvatar.AvatarSettings.defaults() : settings;
        this.focusRadius = focusRadius;
        this.diagnostics = diagnostics == null ? (message, error) -> {
        } : diagnostics;
    }

    public void setKillConfirmation(KillConfirmation killConfirmation) {
        this.killConfirmation = killConfirmation;
    }

    public void setInteraction(Interaction interaction) {
        this.interaction = interaction;
    }

    // ----------------------------------------------------------------- spawn

    /**
     * Puts a session into the world in front of {@code player}.
     *
     * <p>Two blocks along their line of sight at eye height: close enough to
     * read, far enough not to sit in the way. That spot becomes the anchor the
     * avatar orbits and the destination of {@code /agent tp}.
     */
    public SessionAvatar spawnFor(AgentSession session, ServerPlayer player) {
        Vec3 anchor = player.getEyePosition().add(player.getLookAngle().normalize().scale(2.0));
        return spawnFor(session, player.level(), anchor);
    }

    public SessionAvatar spawnFor(AgentSession session, net.minecraft.world.level.Level level, Vec3 anchor) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return null;
        }
        SessionAvatar avatar = SessionAvatar.spawn(session, serverLevel, anchor, settings);
        if (avatar == null) {
            diagnostics.accept("could not spawn an avatar for session " + session.name(), null);
            return null;
        }
        avatars.put(session.id(), avatar);
        focus.put(session.ownerId(), session.id());
        return avatar;
    }

    // ------------------------------------------------------------------ focus

    /**
     * The session a player's commands act on: the nearest avatar within the
     * focus radius, else whichever they last dealt with.
     */
    public AgentSession focusedSession(ServerPlayer player) {
        SessionAvatar nearest = null;
        double nearestDistance = focusRadius * focusRadius;
        for (SessionAvatar avatar : avatars.values()) {
            double distance = avatar.position().distanceToSqr(player.position());
            if (distance <= nearestDistance) {
                nearestDistance = distance;
                nearest = avatar;
            }
        }
        if (nearest != null) {
            focus.put(player.getUUID(), nearest.session().id());
            return nearest.session();
        }

        UUID remembered = focus.get(player.getUUID());
        SessionAvatar avatar = remembered == null ? null : avatars.get(remembered);
        return avatar == null ? null : avatar.session();
    }

    public SessionAvatar avatarOf(AgentSession session) {
        return avatars.get(session.id());
    }

    public int avatarCount() {
        return avatars.size();
    }

    // ------------------------------------------------------------------- tick

    /** Drives every avatar. Called once per server tick. */
    public void tick() {
        long now = System.currentTimeMillis();
        for (SessionAvatar avatar : new ArrayList<>(avatars.values())) {
            try {
                avatar.tick(now, ownerOf(avatar));
            } catch (RuntimeException e) {
                // One misbehaving avatar must not stop the rest, or the tick.
                diagnostics.accept("avatar tick failed for " + avatar.session().name(), e);
            }
        }
    }

    /**
     * The owner, if they are online and in the same world as their avatar.
     *
     * <p>Null otherwise, which is what makes an avatar waiting on an absent
     * player simply hover: there is nobody to fly to and nobody to ring at.
     */
    private ServerPlayer ownerOf(SessionAvatar avatar) {
        ServerPlayer owner = server.getPlayerList().getPlayer(avatar.session().ownerId());
        if (owner == null || owner.level() != avatar.level()) {
            return null;
        }
        return owner;
    }

    // ------------------------------------------------------------------ hits

    /**
     * Handles a player hitting an entity.
     *
     * <p>Damage is always swallowed — the body is invulnerable anyway, and a
     * session should end through a deliberate confirmation rather than a stray
     * swing. Returns true when the hit was on an avatar and should not be
     * passed on.
     */
    public boolean handleAttack(Player player, Entity target) {
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return false;
        }
        SessionAvatar avatar = avatarFor(target);
        if (avatar == null) {
            return false;
        }

        AgentSession session = avatar.session();
        if (!SessionAccess.canControl(server, serverPlayer, session)) {
            // Someone else's session. Theirs to watch, not to end.
            return true;
        }

        killConfirmation.confirm(serverPlayer, session);
        return true;
    }

    /**
     * Handles a player right-clicking an entity.
     *
     * <p>Always consumes the interaction when it lands on an avatar, whoever
     * did it: an allay's own right-click behaviour is to take the item you are
     * holding, which would quietly swallow a pickaxe. For the owner it also
     * takes focus and opens whatever the session is waiting to show them.
     *
     * @return true when the interaction was on an avatar and must not go on
     */
    public boolean handleUse(Player player, Entity target) {
        SessionAvatar avatar = avatarFor(target);
        if (avatar == null) {
            return false;
        }
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return true;
        }

        AgentSession session = avatar.session();
        if (!SessionAccess.canControl(server, serverPlayer, session)) {
            return true;
        }
        // Walking up to a session and poking it is as clear a statement of
        // "this is the one I mean" as there is.
        focus.put(serverPlayer.getUUID(), session.id());
        interaction.onUse(serverPlayer, session);
        return true;
    }

    private SessionAvatar avatarFor(Entity entity) {
        for (SessionAvatar avatar : avatars.values()) {
            if (avatar.isBody(entity)) {
                return avatar;
            }
        }
        return null;
    }

    // -------------------------------------------------------- observer hooks

    @Override
    public void onStateChanged(AgentSession session) {
        SessionAvatar avatar = avatars.get(session.id());
        if (avatar != null) {
            avatar.refresh(System.currentTimeMillis(), ownerOf(avatar));
        }
    }

    @Override
    public void onClosed(AgentSession session) {
        SessionAvatar avatar = avatars.remove(session.id());
        if (avatar != null) {
            avatar.remove();
        }
        focus.values().removeIf(id -> id.equals(session.id()));
    }

    /** Removes every avatar, for a server shutting down. */
    public void removeAll() {
        List<SessionAvatar> snapshot = new ArrayList<>(avatars.values());
        avatars.clear();
        focus.clear();
        for (SessionAvatar avatar : snapshot) {
            avatar.remove();
        }
    }
}
