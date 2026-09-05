package dev.shinobu.mcagent.gui;

import dev.shinobu.mcagent.entity.AvatarStyle;
import dev.shinobu.mcagent.session.AgentSession;
import dev.shinobu.mcagent.session.SessionState;
import dev.shinobu.mcagent.session.ToolCallState;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

import java.util.Locale;

/**
 * One line describing a session, shared by the list command and by poking an
 * avatar, so the same session never reads two different ways.
 */
public final class SessionText {

    private SessionText() {
    }

    public static Component summary(AgentSession session) {
        SessionState state = session.state().state();
        return Component.literal(AvatarStyle.symbol(state) + " " + session.name() + "  " + activity(session))
                .withStyle(AvatarStyle.textColour(state))
                .append(Component.literal("  " + session.workspace()).withStyle(ChatFormatting.DARK_GRAY));
    }

    /** What the session is doing right now, in a few words. */
    public static String activity(AgentSession session) {
        ToolCallState tool = session.state().activeTool();
        if (tool != null) {
            return tool.describe();
        }
        SessionState state = session.state().state();
        if (state == SessionState.AWAITING && session.pendingPermission() != null) {
            return "waiting on you: " + DiffBook.title(session.pendingPermission());
        }
        if (state == SessionState.ERROR && session.state().errorMessage() != null) {
            return session.state().errorMessage();
        }
        return state.name().toLowerCase(Locale.ROOT);
    }
}
