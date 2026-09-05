package dev.shinobu.mcagent.session;

/**
 * What a session is doing, as the player sees it from across the world.
 *
 * <p>These five are the whole vocabulary of the avatar: each maps to a glow
 * colour, a movement pattern and (for two of them) a sound. Adding a state
 * means adding a look, so the set is deliberately small.
 */
public enum SessionState {

    /** Nothing in flight. Hovers at its anchor, unlit. */
    IDLE,

    /** A turn is running but no tool is. Slow orbit, blue. */
    THINKING,

    /** A tool is running. Fast orbit, cyan, particles keyed to the tool kind. */
    WORKING,

    /** Blocked on the player. Flies to the owner, flashes red, rings a bell. */
    AWAITING,

    /** The agent died or failed. Red, smoke. */
    ERROR;

    /** Whether the agent is doing something, for "is it safe to walk away". */
    public boolean isBusy() {
        return this == THINKING || this == WORKING;
    }

    /** Whether the session needs the player before it can continue. */
    public boolean needsAttention() {
        return this == AWAITING || this == ERROR;
    }
}
