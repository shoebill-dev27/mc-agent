package dev.shinobu.mcagent.config;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Workspace resolution is the boundary between "a session edits my project"
 * and "a session edits my home directory", so it gets pinned down properly.
 * Every case here fails closed.
 */
class ModConfigTest {

    @TempDir
    Path tmp;

    private ModConfig config;
    private Path root;
    private Path outside;

    @BeforeEach
    void setUp() throws IOException {
        root = Files.createDirectories(tmp.resolve("projects"));
        outside = Files.createDirectories(tmp.resolve("secrets"));

        config = new ModConfig();
        config.workspaceRoots = List.of(root.toString());
    }

    @Test
    void aDirectoryUnderARootIsAccepted() throws IOException {
        Path repo = Files.createDirectories(root.resolve("mc-agent"));

        Optional<Path> resolved = config.resolveWorkspace(repo.toString());

        assertTrue(resolved.isPresent());
        assertEquals(repo.toRealPath(), resolved.orElseThrow());
    }

    @Test
    void aDirectoryOutsideEveryRootIsRefused() {
        assertTrue(config.resolveWorkspace(outside.toString()).isEmpty());
    }

    /** The obvious escape, and the one a hand-typed path hits by accident. */
    @Test
    void traversingOutOfARootIsRefused() throws IOException {
        Files.createDirectories(root.resolve("mc-agent"));
        String escaping = root.resolve("mc-agent").resolve("..").resolve("..")
                .resolve("secrets").toString();

        assertTrue(config.resolveWorkspace(escaping).isEmpty(),
                "normalising the path must happen before the root check, not after");
    }

    /**
     * The less obvious escape: a link planted inside a root. Comparing real
     * paths is what closes it — comparing the requested path textually would
     * let this through.
     */
    @Test
    void aSymlinkOutOfARootIsRefused() throws IOException {
        Path link = root.resolve("shortcut");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | IOException e) {
            Assumptions.abort("this filesystem does not allow symlinks");
        }

        assertTrue(config.resolveWorkspace(link.toString()).isEmpty());
    }

    @Test
    void aPathThatDoesNotExistIsRefused() {
        assertTrue(config.resolveWorkspace(root.resolve("not-here").toString()).isEmpty());
    }

    @Test
    void aFileIsRefusedBecauseASessionNeedsADirectory() throws IOException {
        Path file = Files.writeString(root.resolve("README.md"), "hello");

        assertTrue(config.resolveWorkspace(file.toString()).isEmpty());
    }

    @Test
    void nothingResolvesWhenNoRootsAreConfigured() throws IOException {
        Path repo = Files.createDirectories(root.resolve("mc-agent"));
        config.workspaceRoots = List.of();

        assertTrue(config.resolveWorkspace(repo.toString()).isEmpty(),
                "an unconfigured mod must reach nothing at all");
    }

    @Test
    void blankAndNullAreRefused() {
        assertTrue(config.resolveWorkspace(null).isEmpty());
        assertTrue(config.resolveWorkspace("").isEmpty());
        assertTrue(config.resolveWorkspace("   ").isEmpty());
    }

    @Test
    void aMissingRootMatchesNothingRatherThanThrowing() throws IOException {
        Path repo = Files.createDirectories(root.resolve("mc-agent"));
        config.workspaceRoots = List.of(tmp.resolve("no-such-root").toString(), root.toString());

        assertTrue(config.resolveWorkspace(repo.toString()).isPresent(),
                "one bad root should not disable the good ones");
    }

    // ----------------------------------------------------------- discovery

    @Test
    void repositoryDiscoveryFindsGitDirectoriesOnly() throws IOException {
        Files.createDirectories(root.resolve("with-git").resolve(".git"));
        Files.createDirectories(root.resolve("plain-folder"));
        Files.writeString(root.resolve("loose-file.txt"), "x");

        List<Path> found = config.discoverRepositories();

        assertEquals(1, found.size(), "found: " + found);
        assertEquals("with-git", found.get(0).getFileName().toString());
    }

    @Test
    void repositoryDiscoverySurvivesAnUnreadableRoot() throws IOException {
        Files.createDirectories(root.resolve("with-git").resolve(".git"));
        config.workspaceRoots = List.of(tmp.resolve("gone").toString(), root.toString());

        assertEquals(1, config.discoverRepositories().size());
    }

    // -------------------------------------------------------------- agents

    @Test
    void agentsAreLookedUpByIdAndSkippedWhenUnconfigured() {
        ModConfig defaults = ModConfig.withDefaults();

        assertTrue(defaults.defaultAgentSpec().isPresent());
        assertEquals("claude", defaults.defaultAgentSpec().orElseThrow().id());
        assertTrue(defaults.agent("codex").isEmpty());

        ModConfig.AgentEntry blank = new ModConfig.AgentEntry();
        defaults.agents.put("broken", blank);
        assertTrue(defaults.agent("broken").isEmpty(), "an entry with no command is not usable");
    }

    // --------------------------------------------------------------- files

    @Test
    void aMissingConfigIsWrittenWithWorkingDefaults() {
        Path file = tmp.resolve("config").resolve("mcagent.json");

        ModConfig written = ModConfig.load(file, (message, error) -> {
        });

        assertTrue(Files.exists(file), "a starter config should have been written");
        assertTrue(written.defaultAgentSpec().isPresent());
        assertFalse(written.workspaceRoots.isEmpty());
    }

    /** A missing comma must not silently destroy someone's hand-edited file. */
    @Test
    void aMalformedConfigIsReportedAndLeftOnDisk() throws IOException {
        Path file = tmp.resolve("mcagent.json");
        Files.writeString(file, "{ this is not json");
        StringBuilder reported = new StringBuilder();

        ModConfig loaded = ModConfig.load(file, (message, error) -> reported.append(message));

        assertTrue(reported.length() > 0, "the problem should have been reported");
        assertTrue(loaded.defaultAgentSpec().isPresent(), "defaults should still be usable");
        assertEquals("{ this is not json", Files.readString(file),
                "the broken file must be left alone, not overwritten");
    }

    @Test
    void aSavedConfigRoundTrips() {
        Path file = tmp.resolve("mcagent.json");
        ModConfig original = ModConfig.withDefaults();
        original.workspaceRoots = List.of(root.toString());
        original.approval.timeoutSeconds = 42;
        original.avatar.orbitRadius = 1.25;

        original.save(file, (message, error) -> {
        });
        ModConfig reloaded = ModConfig.load(file, (message, error) -> {
        });

        assertEquals(List.of(root.toString()), reloaded.workspaceRoots);
        assertEquals(42, reloaded.approval.timeoutSeconds);
        assertEquals(1.25, reloaded.avatar.orbitRadius, 1e-9);
        assertEquals("claude-agent-acp", reloaded.agents.get("claude").command);
    }
}
