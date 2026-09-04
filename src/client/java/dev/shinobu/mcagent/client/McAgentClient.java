package dev.shinobu.mcagent.client;

import net.fabricmc.api.ClientModInitializer;

/**
 * Client entrypoint. Owns only what cannot live on the server: the prompt
 * screen, its keybinding, and completion requests. Avatars and terminal
 * walls are plain server-driven entities, so vanilla clients still see them.
 */
public class McAgentClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        dev.shinobu.mcagent.McAgent.LOGGER.info("mc-agent client initialising");
    }
}
