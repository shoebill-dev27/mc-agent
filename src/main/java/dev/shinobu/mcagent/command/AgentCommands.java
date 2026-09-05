package dev.shinobu.mcagent.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.shinobu.mcagent.McAgent;
import dev.shinobu.mcagent.McAgentRuntime;
import dev.shinobu.mcagent.acp.AgentSpec;
import dev.shinobu.mcagent.acp.model.PermissionRequest;
import dev.shinobu.mcagent.acp.model.PermissionRequest.PermissionOption;
import dev.shinobu.mcagent.gui.Approvals;
import dev.shinobu.mcagent.gui.SessionText;
import dev.shinobu.mcagent.security.SessionAccess;
import dev.shinobu.mcagent.session.AgentSession;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

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
                .then(Commands.literal("permission")
                        .executes(context -> openApproval(context, null))
                        .then(Commands.argument("session", StringArgumentType.greedyString())
                                .executes(context -> openApproval(context, session(context)))))
                .then(Commands.literal("diff")
                        .executes(context -> openDiff(context, null))
                        .then(Commands.argument("session", StringArgumentType.greedyString())
                                .executes(context -> openDiff(context, session(context)))))
                .then(Commands.literal("approve")
                        .executes(context -> decide(context, PermissionOption.ALLOW_ONCE, null))
                        .then(Commands.literal("always")
                                .executes(context -> decide(context, PermissionOption.ALLOW_ALWAYS, null))
                                .then(Commands.argument("session", StringArgumentType.greedyString())
                                        .executes(context -> decide(context,
                                                PermissionOption.ALLOW_ALWAYS, session(context)))))
                        .then(Commands.argument("session", StringArgumentType.greedyString())
                                .executes(context -> decide(context,
                                        PermissionOption.ALLOW_ONCE, session(context)))))
                .then(Commands.literal("deny")
                        .executes(context -> decide(context, PermissionOption.REJECT_ONCE, null))
                        .then(Commands.argument("session", StringArgumentType.greedyString())
                                .executes(context -> decide(context,
                                        PermissionOption.REJECT_ONCE, session(context)))))
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
            context.getSource().sendSuccess(() -> SessionText.summary(session), false);
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

    // ----------------------------------------------------------- permissions

    private static int openApproval(CommandContext<CommandSourceStack> context, String name)
            throws CommandSyntaxException {
        return withWaitingSession(context, name, (runtime, player, session) -> {
            Approvals.open(player, runtime, session);
            return 1;
        });
    }

    private static int openDiff(CommandContext<CommandSourceStack> context, String name)
            throws CommandSyntaxException {
        return withWaitingSession(context, name, (runtime, player, session) -> {
            Approvals.openDiff(player, runtime, session);
            return 1;
        });
    }

    /**
     * Answers a request from chat, for anyone without the client mod or without
     * the patience for a screen.
     *
     * <p>{@code /agent approve always} is not put behind the dialog's second
     * confirmation. That confirmation exists because a button in an inventory
     * is easy to hit by accident; typing the word "always" is not.
     */
    private static int decide(CommandContext<CommandSourceStack> context, String kind, String name)
            throws CommandSyntaxException {
        return withWaitingSession(context, name, (runtime, player, session) -> {
            PermissionRequest request = session.pendingPermission();
            if (request == null) {
                context.getSource().sendFailure(Component.literal(
                        session.name() + " is no longer waiting on an answer."));
                return 0;
            }
            Optional<PermissionOption> option = PermissionOption.REJECT_ONCE.equals(kind)
                    // Take whatever refusal the agent offered rather than
                    // insisting on the usual one, so "no" always works.
                    ? request.safeRefusal()
                    : request.optionOfKind(kind);
            if (option.isEmpty()) {
                context.getSource().sendFailure(Component.literal(
                        session.name() + " was not offered that choice: " + kind));
                return 0;
            }

            PermissionOption chosen = option.orElseThrow();
            if (!runtime.sessions().decide(session, chosen.optionId())) {
                context.getSource().sendFailure(Component.literal(
                        session.name() + " is no longer waiting on an answer."));
                return 0;
            }
            String label = chosen.name() == null ? chosen.optionId() : chosen.name();
            context.getSource().sendSuccess(() -> Component.literal(session.name() + ": " + label)
                    .withStyle(chosen.allows() ? ChatFormatting.GREEN : ChatFormatting.RED), false);
            return 1;
        });
    }

    /** Resolves which waiting session a permission command means, or explains. */
    private static int withWaitingSession(CommandContext<CommandSourceStack> context, String name,
                                          SessionAction action) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        McAgentRuntime runtime = requireRuntime(context);
        if (runtime == null) {
            return 0;
        }

        AgentSession session = Approvals.resolve(runtime, player, name);
        if (session != null) {
            return action.run(runtime, player, session);
        }

        List<AgentSession> waiting = Approvals.waitingFor(runtime, player);
        if (waiting.isEmpty()) {
            context.getSource().sendFailure(Component.literal("Nothing is waiting for your approval."));
        } else if (name != null) {
            context.getSource().sendFailure(Component.literal("No session called " + name + " is waiting."));
        } else {
            context.getSource().sendFailure(Component.literal(
                    "More than one session is waiting. Name one: "
                            + waiting.stream().map(AgentSession::name).collect(Collectors.joining(", "))));
        }
        return 0;
    }

    private static String session(CommandContext<CommandSourceStack> context) {
        return StringArgumentType.getString(context, "session");
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

    private static String rootCause(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getMessage() == null ? cause.toString() : cause.getMessage();
    }
}
