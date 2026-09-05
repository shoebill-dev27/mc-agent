package dev.shinobu.mcagent.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import dev.shinobu.mcagent.acp.AgentSpec;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Everything the mod reads from {@code config/mcagent.json}.
 *
 * <p>Deliberately holds no secrets. The adapter inherits the host environment
 * and reuses whatever authentication the agent CLI already has, so there is no
 * API key here to leak into a world save or a screenshot.
 */
public final class ModConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Directories sessions may be opened under. Nothing outside them is reachable. */
    public List<String> workspaceRoots = new ArrayList<>();

    public String defaultAgent = "claude";

    /** Launch commands, keyed by the id used in game. */
    public Map<String, AgentEntry> agents = new LinkedHashMap<>();

    public AvatarConfig avatar = new AvatarConfig();
    public WallConfig wall = new WallConfig();
    public ApprovalConfig approval = new ApprovalConfig();
    public ProcessConfig process = new ProcessConfig();

    /** How to launch one adapter. */
    public static final class AgentEntry {
        public String command = "";
        public List<String> args = new ArrayList<>();
        public Map<String, String> env = new LinkedHashMap<>();
    }

    public static final class AvatarConfig {
        public double panelOffsetY = 0.9;
        public float panelScale = 0.5f;
        public double orbitRadius = 0.6;
        public double viewRange = 32;
        public double focusRadius = 8;
    }

    public static final class WallConfig {
        public boolean autoCreate = true;
        public int lines = 20;
        public int lineWidth = 320;
        public int maxUpdatesPerSecond = 4;
        public boolean showThoughts = true;
    }

    public static final class ApprovalConfig {
        /** Zero means wait indefinitely, which is the default on purpose. */
        public long timeoutSeconds = 0;
        /** "Always allow" is easy to misclick in a game, so it is confirmed twice. */
        public boolean confirmAllowAlways = true;
    }

    public static final class ProcessConfig {
        public long idleShutdownSeconds = 300;
    }

    // ------------------------------------------------------------------ agents

    /** The launch spec for an agent id, or empty if it is not configured. */
    public Optional<AgentSpec> agent(String id) {
        AgentEntry entry = agents.get(id);
        if (entry == null || entry.command == null || entry.command.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new AgentSpec(id, entry.command, entry.args, entry.env));
    }

    public Optional<AgentSpec> defaultAgentSpec() {
        return agent(defaultAgent);
    }

    // -------------------------------------------------------------- workspaces

    /**
     * Resolves a directory a player asked for, refusing anything outside the
     * configured roots.
     *
     * <p>Paths are normalised and compared after resolving symlinks, so neither
     * {@code ..} nor a link planted inside a root can reach out of it. This is
     * the boundary between "a session edits my project" and "a session edits my
     * home directory", so it fails closed: an unreadable path, an unconfigured
     * root, or anything it cannot verify comes back empty.
     */
    public Optional<Path> resolveWorkspace(String candidate) {
        if (candidate == null || candidate.isBlank() || workspaceRoots.isEmpty()) {
            return Optional.empty();
        }
        Path requested;
        try {
            requested = Path.of(candidate).toAbsolutePath().normalize().toRealPath();
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
        if (!Files.isDirectory(requested)) {
            return Optional.empty();
        }

        for (String rootPath : workspaceRoots) {
            try {
                Path root = Path.of(rootPath).toAbsolutePath().normalize().toRealPath();
                if (requested.startsWith(root)) {
                    return Optional.of(requested);
                }
            } catch (IOException | RuntimeException e) {
                // A root that does not exist simply matches nothing.
            }
        }
        return Optional.empty();
    }

    /** Repositories offered in the picker: directories under a root holding a .git. */
    public List<Path> discoverRepositories() {
        List<Path> found = new ArrayList<>();
        for (String rootPath : workspaceRoots) {
            Path root;
            try {
                root = Path.of(rootPath).toAbsolutePath().normalize().toRealPath();
            } catch (IOException | RuntimeException e) {
                continue;
            }
            try (var children = Files.list(root)) {
                children.filter(Files::isDirectory)
                        .filter(directory -> Files.exists(directory.resolve(".git")))
                        .sorted()
                        .forEach(found::add);
            } catch (IOException e) {
                // An unreadable root contributes nothing rather than failing.
            }
        }
        return found;
    }

    // ------------------------------------------------------------------- files

    /**
     * Loads the config, writing a starter file if there is none.
     *
     * <p>A malformed file is reported and replaced with defaults in memory,
     * never overwritten — someone's hand-edited config should not be destroyed
     * because of a missing comma.
     */
    public static ModConfig load(Path file, java.util.function.BiConsumer<String, Throwable> diagnostics) {
        if (!Files.exists(file)) {
            ModConfig starter = withDefaults();
            starter.save(file, diagnostics);
            return starter;
        }
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            ModConfig loaded = GSON.fromJson(json, ModConfig.class);
            return loaded == null ? withDefaults() : loaded;
        } catch (IOException | JsonSyntaxException e) {
            diagnostics.accept("could not read " + file + "; using defaults for this session", e);
            return withDefaults();
        }
    }

    public void save(Path file, java.util.function.BiConsumer<String, Throwable> diagnostics) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(this), StandardCharsets.UTF_8);
        } catch (IOException e) {
            diagnostics.accept("could not write " + file, e);
        }
    }

    /**
     * A config that works out of the box on the machine it is written on: the
     * user's home directory as the only root, and Claude Code launched through
     * whatever {@code claude-agent-acp} is on the path.
     */
    public static ModConfig withDefaults() {
        ModConfig config = new ModConfig();
        config.workspaceRoots.add(System.getProperty("user.home", "."));

        AgentEntry claude = new AgentEntry();
        // Prefer an installed binary: going through npx re-resolves the package
        // on every launch, which measured 35s cold against 0.2s for the binary.
        claude.command = "claude-agent-acp";
        config.agents.put("claude", claude);
        return config;
    }
}
