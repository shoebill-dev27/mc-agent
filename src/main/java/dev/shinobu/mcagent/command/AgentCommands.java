package dev.shinobu.mcagent.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.shinobu.mcagent.McAgent;
import dev.shinobu.mcagent.McAgentRuntime;
import dev.shinobu.mcagent.acp.AgentSpec;
import dev.shinobu.mcagent.security.SessionAccess;
import dev.shinobu.mcagent.session.AgentSession;
import dev.shinobu.mcagent.session.SessionState;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * The {@code /agent} commands.
 *
 * <p>These are the plain-text way in, and they stay useful for good: the
 * prompt screen needs the client mod, so on a shared server a player without
 * it still has {@code /agent say}. Every one of them re-checks ownership
 * server-side rather than trusting that a player only sees their own sessions.
 */
public final class AgentCommands {

    private AgentCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("agent")
                .then(Commands.literal("new")
                        .then(Commands.argument("workspace", StringArgumentType.greedyString())
                                .executes(AgentCommands::newSession)))
                .then(Commands.literal("list")
                        .executes(AgentCommands::list))
                .then(Commands.literal("say")
                        .then(Commands.argument("text", StringArgumentType.greedyString())
                                .executes(AgentCommands::say)))
                .then(Commands.literal("cancel")
                        .executes(AgentCommands::cancel))
                .then(Commands.literal("tp")
                        .executes(AgentCommands::teleport))
                .then(Commands.literal("end")
                        .executes(AgentCommands::end)));
    }

    // -------------------------------------------------------------- commands

    private static int newSession(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        McAgentRuntime runtime = requireRuntime(context);
        if (runtime == null) {
            return 0;
        }

        String requested = StringArgumentType.getString(context, "workspace");
        Optional<Path> workspace = runtime.config().resolveWorkspace(requested);
        if (workspace.isEmpty()) {
            // Deliberately does not say whether the path exists: the roots are
            // the only thing reachable, and probing outside them is not useful.
            context.getSource().sendFailure(Component.literal(
                    "Not a directory inside a configured workspace root: " + requested));
            return 0;
        }

        Optional<AgentSpec> spec = runtime.config().defaultAgentSpec();
        if (spec.isEmpty()) {
            context.getSource().sendFailure(Component.literal(
                    "No agent configured. Set agents." + runtime.config().defaultAgent
                            + ".command in config/mcagent.json"));
            return 0;
        }

        Path directory = workspace.orElseThrow();
        String name = directory.getFileName().toString();
        int existing = runtime.sessions().sessionsInWorkspace(directory.toString());
        if (existing > 0) {
            context.getSource().sendSuccess(() -> Component.literal(
                    "Note: " + existing + " session(s) already open on " + name
                            + "; they will compete over the same files.")
                    .withStyle(ChatFormatting.YELLOW), false);
        }

        context.getSource().sendSuccess(() -> Component.literal("Starting " + name + "…"), false);

        runtime.sessions()
                .open(name, player.getUUID(), player.nameAndId().name(),
                        spec.orElseThrow(), directory.toString())
                .whenComplete((session, error) -> {
                    // Already on the server thread: SessionManager hops there
                    // before completing.
                    if (error != null) {
                        player.sendSystemMessage(Component.literal(
                                "Could not start the agent: " + rootCause(error))
                                .withStyle(ChatFormatting.RED));
                        return;
                    }
                    runtime.avatars().spawnFor(session, player);
                    player.sendSystemMessage(Component.literal(
                            session.name() + " is here. Hit it to end the session.")
                            .withStyle(ChatFormatting.GREEN));
                });
        return 1;
    }

    private static int list(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        McAgentRuntime runtime = requireRuntime(context);
        if (runtime == null) {
            return 0;
        }

        List<AgentSession> sessions = runtime.sessions().sessions().stream()
                .filter(session -> SessionAccess.canControl(runtime.server(), player, session))
                .toList();
        if (sessions.isEmpty()) {
            context.getSource().sendSuccess(() -> Component.literal("No sessions."), false);
            return 0;
        }

        for (AgentSession session : sessions) {
            SessionState state = session.state().state();
            String activity = session.state().activeTool() == null
                    ? state.name().toLowerCase(java.util.Locale.ROOT)
                    : session.state().activeTool().describe();
            context.getSource().sendSuccess(() -> Component.literal(
                    session.name() + "  " + activity + "  " + session.workspace())
                    .withStyle(colourOf(state)), false);
        }
        return sessions.size();
    }

    private static int say(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        return withFocusedSession(context, (runtime, player, session) -> {
            runtime.sessions().prompt(session, StringArgumentType.getString(context, "text"));
            return 1;
        });
    }

    private static int cancel(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        return withFocusedSession(context, (runtime, player, session) -> {
            runtime.sessions().cancel(session);
            context.getSource().sendSuccess(() -> Component.literal("Interrupted " + session.name()), false);
            return 1;
        });
    }

    private static int teleport(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        return withFocusedSession(context, (runtime, player, session) -> {
            var avatar = runtime.avatars().avatarOf(session);
            if (avatar == null) {
                context.getSource().sendFailure(Component.literal("That session has no avatar in the world."));
                return 0;
            }
            var to = avatar.anchor();
            player.teleportTo(avatar.level(), to.x, to.y, to.z, java.util.Set.of(),
                    player.getYRot(), player.getXRot(), false);
            return 1;
        });
    }

    private static int end(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        return withFocusedSession(context, (runtime, player, session) -> {
            runtime.sessions().close(session);
            context.getSource().sendSuccess(() -> Component.literal("Ended " + session.name()), false);
            return 1;
        });
    }

    // --------------------------------------------------------------- helpers

    @FunctionalInterface
    private interface SessionAction {
        int run(McAgentRuntime runtime, ServerPlayer player, AgentSession session);
    }

    /**
     * Resolves the session a command acts on and checks the caller may touch
     * it, before running the action.
     */
    private static int withFocusedSession(CommandContext<CommandSourceStack> context, SessionAction action)
            throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        McAgentRuntime runtime = requireRuntime(context);
        if (runtime == null) {
            return 0;
        }

        AgentSession session = runtime.avatars().focusedSession(player);
        if (session == null) {
            context.getSource().sendFailure(Component.literal(
                    "No session in focus. Walk up to one, or start one with /agent new <path>"));
            return 0;
        }
        if (!SessionAccess.canControl(runtime.server(), player, session)) {
            context.getSource().sendFailure(Component.literal(
                    session.name() + " belongs to " + session.ownerName() + "."));
            return 0;
        }
        return action.run(runtime, player, session);
    }

    private static McAgentRuntime requireRuntime(CommandContext<CommandSourceStack> context) {
        McAgentRuntime runtime = McAgent.runtime();
        if (runtime == null) {
            context.getSource().sendFailure(Component.literal("mc-agent is not running."));
        }
        return runtime;
    }

    private static ChatFormatting colourOf(SessionState state) {
        return switch (state) {
            case IDLE -> ChatFormatting.GRAY;
            case THINKING -> ChatFormatting.BLUE;
            case WORKING -> ChatFormatting.AQUA;
            case AWAITING -> ChatFormatting.RED;
            case ERROR -> ChatFormatting.DARK_RED;
        };
    }

    private static String rootCause(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getMessage() == null ? cause.toString() : cause.getMessage();
    }
}
