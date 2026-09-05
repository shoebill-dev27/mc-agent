package dev.shinobu.mcagent.entity;

import dev.shinobu.mcagent.session.AgentSession;
import dev.shinobu.mcagent.session.SessionState;
import dev.shinobu.mcagent.session.ToolCallState;
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.allay.Allay;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.TeamColor;

import java.util.Optional;

/**
 * The allay that <em>is</em> a session, and the panel floating above it.
 *
 * <p>The body's AI is switched off and its position driven from here every
 * tick. Vanilla pathing would wander, get stuck on terrain and generally make
 * the state hard to read; a coded orbit is deterministic, which is the whole
 * point of an avatar you are supposed to be able to glance at.
 *
 * <p>The panel is a separate entity rather than a passenger. Riding a mob
 * makes a Display interpolate against the vehicle in ways that jitter; driving
 * both from the same loop keeps them locked together.
 */
public final class SessionAvatar {

    /** Vanilla multiplies this by 64 to get the range in blocks. */
    private static final float VIEW_RANGE_BLOCKS_PER_UNIT = 64f;

    /** Ticks the client is given to slide the panel to its new position. */
    private static final int PANEL_INTERPOLATION_TICKS = 3;

    /** Semi-transparent black, as ARGB. */
    private static final int PANEL_BACKGROUND = 0x50000000;

    private static final byte FLAG_SEE_THROUGH_AND_SHADOW =
            (byte) (Display.TextDisplay.FLAG_SEE_THROUGH | Display.TextDisplay.FLAG_SHADOW);

    /** Panels refresh at most this often; the elapsed clock only needs seconds. */
    private static final long PANEL_REFRESH_MS = 250;

    /** How long the bell keeps ringing before it gives up nagging. */
    private static final int MAX_ATTENTION_BELLS = 3;
    private static final long BELL_INTERVAL_MS = 3_000;

    private final AgentSession session;
    private final ServerLevel level;
    private final Allay body;
    private final Display.TextDisplay panel;
    private final AvatarSettings settings;

    private Vec3 anchor;
    private double orbitPhase;
    private SessionState lastState = SessionState.IDLE;
    private String lastPanelText = "";
    private long lastPanelRefreshMs;
    private long lastBellMs;
    private int bellsRung;

    private SessionAvatar(AgentSession session, ServerLevel level, Vec3 anchor,
                          Allay body, Display.TextDisplay panel, AvatarSettings settings) {
        this.session = session;
        this.level = level;
        this.anchor = anchor;
        this.body = body;
        this.panel = panel;
        this.settings = settings;
    }

    /** Tunables the config file drives; see {@code config/mcagent.json}. */
    public record AvatarSettings(double panelOffsetY, float panelScale, double orbitRadius,
                                 double viewRangeBlocks) {
        public static AvatarSettings defaults() {
            return new AvatarSettings(0.9, 0.5f, 0.6, 32);
        }
    }

    /**
     * Puts a session into the world at {@code anchor}.
     *
     * @return the avatar, or null if either entity could not be created
     */
    public static SessionAvatar spawn(AgentSession session, ServerLevel level, Vec3 anchor,
                                      AvatarSettings settings) {
        Allay body = EntityTypes.ALLAY.create(level, EntitySpawnReason.COMMAND);
        Display.TextDisplay panel = EntityTypes.TEXT_DISPLAY.create(level, EntitySpawnReason.COMMAND);
        if (body == null || panel == null) {
            return null;
        }

        body.setPos(anchor.x, anchor.y, anchor.z);
        // Driven from tick(), so vanilla must not also be steering it.
        body.setNoAi(true);
        body.setPersistenceRequired();
        body.setNoGravity(true);
        body.setSilent(true);
        // Nothing in the world should be able to shove, burn or shoot a
        // session out of existence; only its owner ends it, through the
        // confirmation dialog.
        body.setInvulnerable(true);
        // Allays hoard items given half a chance.
        body.setCanPickUpLoot(false);
        body.setCustomName(Component.literal(session.name()));
        // The panel says the name already; a nameplate on top would double it.
        body.setCustomNameVisible(false);

        panel.setPos(anchor.x, anchor.y + settings.panelOffsetY(), anchor.z);
        panel.setBillboardConstraints(Display.BillboardConstraints.CENTER);
        panel.setViewRange((float) (settings.viewRangeBlocks() / VIEW_RANGE_BLOCKS_PER_UNIT));
        panel.setPosRotInterpolationDuration(PANEL_INTERPOLATION_TICKS);
        panel.setBackgroundColor(PANEL_BACKGROUND);
        panel.setLineWidth(200);
        panel.setFlags(FLAG_SEE_THROUGH_AND_SHADOW);
        panel.setTransformation(new com.mojang.math.Transformation(
                null, null,
                new org.joml.Vector3f(settings.panelScale(), settings.panelScale(), settings.panelScale()),
                null));

        level.addFreshEntity(body);
        level.addFreshEntity(panel);

        SessionAvatar avatar = new SessionAvatar(session, level, anchor, body, panel, settings);
        avatar.refresh(System.currentTimeMillis(), null);
        return avatar;
    }

    // ------------------------------------------------------------------ query

    public AgentSession session() {
        return session;
    }

    public Vec3 anchor() {
        return anchor;
    }

    /** The world this avatar lives in. */
    public ServerLevel level() {
        return level;
    }

    public Vec3 position() {
        return body.position();
    }

    /** True if {@code entity} is the body a player just hit. */
    public boolean isBody(Entity entity) {
        return entity != null && entity.getId() == body.getId();
    }

    // ------------------------------------------------------------------- tick

    /**
     * Moves the avatar and keeps the panel current.
     *
     * @param owner the session's owner if they are online and in this level,
     *              otherwise null — an avatar waiting on an absent player just
     *              hovers where it is
     */
    public void tick(long nowMillis, ServerPlayer owner) {
        SessionState state = session.state().state();

        Vec3 target = targetPosition(state, owner);
        body.setPos(target.x, target.y, target.z);
        panel.setPos(target.x, target.y + settings.panelOffsetY(), target.z);

        if (owner != null) {
            body.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES,
                    owner.getEyePosition());
        }

        if (state != lastState) {
            onStateChanged(lastState, state, owner);
            lastState = state;
        }

        if (nowMillis - lastPanelRefreshMs >= PANEL_REFRESH_MS) {
            lastPanelRefreshMs = nowMillis;
            refresh(nowMillis, owner);
        }

        emitParticles(state);
        nagIfWaiting(state, nowMillis, owner);
    }

    private Vec3 targetPosition(SessionState state, ServerPlayer owner) {
        if (state == SessionState.AWAITING && owner != null) {
            // Come and ask: hover just in front of the owner's face, close
            // enough that the diff on the panel is readable.
            Vec3 eye = owner.getEyePosition();
            Vec3 forward = owner.getLookAngle().normalize().scale(2.0);
            return eye.add(forward).subtract(0, 0.4, 0);
        }

        double speed = AvatarStyle.orbitSpeed(state);
        if (speed == 0) {
            return anchor;
        }
        orbitPhase += speed;
        return anchor.add(
                Math.cos(orbitPhase) * settings.orbitRadius(),
                Math.sin(orbitPhase * 0.5) * 0.15,
                Math.sin(orbitPhase) * settings.orbitRadius());
    }

    private void onStateChanged(SessionState from, SessionState to, ServerPlayer owner) {
        applyGlow(to);

        if (to == SessionState.AWAITING) {
            bellsRung = 0;
            lastBellMs = 0;
        }
        if (to == SessionState.ERROR) {
            playToOwner(owner, AvatarStyle.errorSound(), 1.0f, 0.7f);
        }
        // Only announce finishing if it was actually doing something; a
        // session that never started should not chime.
        if (to == SessionState.IDLE && from.isBusy()) {
            playToOwner(owner, AvatarStyle.finishedSound(), 0.6f, 1.2f);
        }
    }

    private void nagIfWaiting(SessionState state, long nowMillis, ServerPlayer owner) {
        if (state != SessionState.AWAITING || bellsRung >= MAX_ATTENTION_BELLS) {
            return;
        }
        if (nowMillis - lastBellMs < BELL_INTERVAL_MS) {
            return;
        }
        lastBellMs = nowMillis;
        bellsRung++;
        playToOwner(owner, AvatarStyle.attentionSound(), 0.8f, 1.4f);
    }

    private void emitParticles(SessionState state) {
        ParticleOptions particle = AvatarStyle.stateParticle(state);
        if (particle == null && state == SessionState.WORKING) {
            ToolCallState tool = session.state().activeTool();
            particle = tool == null ? null : AvatarStyle.toolParticle(tool.kind());
        }
        if (particle == null) {
            return;
        }
        Vec3 at = body.position();
        level.sendParticles(particle, at.x, at.y + 0.4, at.z, 1, 0.25, 0.25, 0.25, 0.01);
    }

    // ------------------------------------------------------------------ panel

    /** Rebuilds the panel text if it has actually changed. */
    public void refresh(long nowMillis, ServerPlayer owner) {
        SessionState state = session.state().state();
        applyGlow(state);

        String line = activityLine(state, nowMillis);
        String rendered = session.name() + "\n" + line;
        if (rendered.equals(lastPanelText)) {
            return;
        }
        lastPanelText = rendered;

        MutableComponent text = Component.literal(session.name())
                .withStyle(ChatFormatting.GRAY)
                .append(Component.literal(" · " + session.spec().id()).withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.literal("\n"))
                .append(Component.literal(line).withStyle(AvatarStyle.textColour(state)));
        panel.setText(text);
    }

    private String activityLine(SessionState state, long nowMillis) {
        String symbol = AvatarStyle.symbol(state);
        long elapsed = session.state().elapsedMillis(nowMillis);

        return switch (state) {
            case IDLE -> symbol + " " + label(state);
            case THINKING -> symbol + " " + label(state) + "  " + clock(elapsed);
            case WORKING -> {
                ToolCallState tool = session.state().activeTool();
                if (tool == null) {
                    yield symbol + " " + label(state) + "  " + clock(elapsed);
                }
                // The agent's own tool name is more specific than the coarse
                // kind, so it is what the player reads.
                yield AvatarStyle.toolSymbol(tool.kind()) + " " + tool.describe() + "  " + clock(elapsed);
            }
            case AWAITING -> {
                var pending = session.state().pendingPermission();
                String what = pending == null || pending.toolCall() == null
                        ? "" : " " + pending.toolCall().kind();
                yield symbol + " " + label(state) + what;
            }
            case ERROR -> symbol + " " + shorten(session.state().errorMessage());
        };
    }

    private static String label(SessionState state) {
        // Resolved server-side: the panel is a Display, whose text is sent as a
        // resolved component, so a translatable key would show as raw text on
        // clients without the mod. Vanilla clients are expected viewers.
        return Component.translatable(AvatarStyle.labelKey(state)).getString();
    }

    private static String clock(long millis) {
        long seconds = millis / 1000;
        return String.format("%02d:%02d", seconds / 60, seconds % 60);
    }

    private static String shorten(String message) {
        if (message == null) {
            return "error";
        }
        return message.length() <= 40 ? message : message.substring(0, 39) + "…";
    }

    // ------------------------------------------------------------------- glow

    /**
     * Glow colour comes from the scoreboard team, which is the only way to
     * colour a mob's outline. One team per state, created on demand.
     */
    private void applyGlow(SessionState state) {
        boolean shouldGlow = AvatarStyle.glows(state);
        body.setGlowingTag(shouldGlow);
        if (!shouldGlow) {
            return;
        }

        Scoreboard scoreboard = level.getScoreboard();
        TeamColor colour = AvatarStyle.glowColour(state);
        String teamName = "mcagent_" + colour.getSerializedName();

        PlayerTeam team = scoreboard.getPlayerTeam(teamName);
        if (team == null) {
            team = scoreboard.addPlayerTeam(teamName);
            team.setColor(Optional.of(colour));
        }
        scoreboard.addPlayerToTeam(body.getScoreboardName(), team);
    }

    // --------------------------------------------------------------- teardown

    /** Takes the avatar out of the world. */
    public void remove() {
        Scoreboard scoreboard = level.getScoreboard();
        PlayerTeam team = scoreboard.getPlayersTeam(body.getScoreboardName());
        if (team != null) {
            scoreboard.removePlayerFromTeam(body.getScoreboardName(), team);
        }
        body.discard();
        panel.discard();
    }

    private void playToOwner(ServerPlayer owner, net.minecraft.core.Holder<net.minecraft.sounds.SoundEvent> sound,
                             float volume, float pitch) {
        if (owner == null) {
            return;
        }
        // Sent straight to the one player: on a shared server nobody else needs
        // to hear another person's agent asking for permission.
        Vec3 at = body.position();
        owner.connection.send(new ClientboundSoundPacket(
                sound, SoundSource.MASTER, at.x, at.y, at.z, volume, pitch, level.getRandom().nextLong()));
    }
}
