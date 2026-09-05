package dev.shinobu.mcagent.entity;

import dev.shinobu.mcagent.session.SessionState;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.scores.TeamColor;

/**
 * The visual vocabulary of the avatar: how a state or a tool turns into a
 * colour, a symbol, a particle and a sound.
 *
 * <p>Two constraints shape this. Minecraft's default font cannot draw emoji,
 * so symbols are limited to geometric characters that the font actually has.
 * And an entity's glow colour comes from its scoreboard team, so it can only
 * be one of sixteen named colours — there is no arbitrary RGB for a mob.
 *
 * <p>Sound is deliberately scarce. Only three things make a noise: "I need
 * you", "I finished", and "I broke". Playing something per tool call would be
 * unbearable once a session gets going.
 */
public final class AvatarStyle {

    private AvatarStyle() {
    }

    // ------------------------------------------------------------------ state

    /** Glow colour for a state. Idle does not glow at all, so it has none. */
    public static TeamColor glowColour(SessionState state) {
        return switch (state) {
            case IDLE -> TeamColor.WHITE;
            case THINKING -> TeamColor.BLUE;
            case WORKING -> TeamColor.AQUA;
            case AWAITING -> TeamColor.RED;
            case ERROR -> TeamColor.DARK_RED;
        };
    }

    /** Whether the body should glow at all. Idle stays dark so it recedes. */
    public static boolean glows(SessionState state) {
        return state != SessionState.IDLE;
    }

    public static ChatFormatting textColour(SessionState state) {
        return switch (state) {
            case IDLE -> ChatFormatting.GRAY;
            case THINKING -> ChatFormatting.BLUE;
            case WORKING -> ChatFormatting.AQUA;
            case AWAITING -> ChatFormatting.RED;
            case ERROR -> ChatFormatting.DARK_RED;
        };
    }

    /** Leading glyph on the panel. ASCII-safe geometric shapes only. */
    public static String symbol(SessionState state) {
        return switch (state) {
            case IDLE -> "○";      // hollow circle
            case THINKING -> "◇";  // hollow diamond
            case WORKING -> "▶";   // right-pointing triangle
            case AWAITING -> "⚠";  // warning sign
            case ERROR -> "×";     // multiplication sign
        };
    }

    /** Translation key for the state's own label. */
    public static String labelKey(SessionState state) {
        return switch (state) {
            case IDLE -> "mcagent.state.idle";
            case THINKING -> "mcagent.state.thinking";
            case WORKING -> "mcagent.state.working";
            case AWAITING -> "mcagent.state.awaiting";
            case ERROR -> "mcagent.state.error";
        };
    }

    /** How fast the body circles its anchor, in radians per tick. */
    public static double orbitSpeed(SessionState state) {
        return switch (state) {
            case IDLE -> 0.0;
            case THINKING -> 0.04;
            case WORKING -> 0.12;
            // Stops and faces the player: the point is to be asked, not to
            // keep busily circling while it waits.
            case AWAITING, ERROR -> 0.0;
        };
    }

    /** Ambient particle for a state, or null for none. */
    public static ParticleOptions stateParticle(SessionState state) {
        return switch (state) {
            case THINKING -> ParticleTypes.ENCHANT;
            case ERROR -> ParticleTypes.SMOKE;
            case IDLE, WORKING, AWAITING -> null;
        };
    }

    // ------------------------------------------------------------------ tools

    /**
     * Particle for a tool kind, keyed off the cross-agent {@code kind} rather
     * than the Claude-specific tool name, so Codex and Gemini get the same
     * visual language for free.
     */
    public static ParticleOptions toolParticle(String kind) {
        if (kind == null) {
            return null;
        }
        return switch (kind) {
            case "edit", "think" -> ParticleTypes.ENCHANT;
            case "delete" -> ParticleTypes.SMOKE;
            case "execute" -> ParticleTypes.FLAME;
            case "fetch" -> ParticleTypes.PORTAL;
            default -> null;
        };
    }

    /** Short glyph shown before a tool's name. */
    public static String toolSymbol(String kind) {
        if (kind == null) {
            return "*";
        }
        return switch (kind) {
            case "read" -> "▸";   // small right triangle
            case "edit" -> "~";
            case "delete" -> "-";
            case "move" -> "»";   // double angle quote
            case "search" -> "?";
            case "execute" -> "$";
            case "think" -> "·";  // middle dot
            case "fetch" -> "@";
            default -> "*";
        };
    }

    public static ChatFormatting toolColour(String kind) {
        if (kind == null) {
            return ChatFormatting.GRAY;
        }
        return switch (kind) {
            case "edit" -> ChatFormatting.YELLOW;
            case "delete" -> ChatFormatting.RED;
            case "execute" -> ChatFormatting.GOLD;
            case "fetch" -> ChatFormatting.AQUA;
            case "think" -> ChatFormatting.DARK_GRAY;
            default -> ChatFormatting.GRAY;
        };
    }

    /** Status glyph for a tool call, matching the observed status values. */
    public static String statusSymbol(String status) {
        if (status == null) {
            return "○";
        }
        return switch (status) {
            case "pending", "in_progress" -> "▶";
            case "completed" -> "●";  // filled circle
            case "failed" -> "×";
            default -> "○";
        };
    }

    // ----------------------------------------------------------------- sounds

    /** Rung at the owner when a decision is needed. */
    public static Holder<SoundEvent> attentionSound() {
        return SoundEvents.NOTE_BLOCK_BELL;
    }

    /** Played once when a turn finishes and the session goes quiet. */
    public static Holder<SoundEvent> finishedSound() {
        return Holder.direct(SoundEvents.EXPERIENCE_ORB_PICKUP);
    }

    /** Played when the agent dies. */
    public static Holder<SoundEvent> errorSound() {
        return Holder.direct(SoundEvents.VILLAGER_NO);
    }
}
