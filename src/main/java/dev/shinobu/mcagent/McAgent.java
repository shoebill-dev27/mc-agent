package dev.shinobu.mcagent;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Common entrypoint. Runs on dedicated servers and on the integrated server
 * in singleplayer, and owns everything that touches the world: sessions,
 * avatars, terminal walls, GUIs and the ACP connections behind them.
 */
public class McAgent implements ModInitializer {
    public static final String MOD_ID = "mcagent";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("mc-agent initialising");
    }
}
