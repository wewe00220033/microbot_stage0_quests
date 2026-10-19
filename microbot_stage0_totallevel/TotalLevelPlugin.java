package net.runelite.client.plugins.microbot.totallevel;

import com.google.inject.Provides;
import javax.inject.Inject;
import net.runelite.api.events.GameTick;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;

@PluginDescriptor(name = "Stage 0.3 Total Level 100", description = "F2P Lumbridge Woodcutting/Firemaking and Fishing/Cooking rotation", tags = {"microbot", "f2p", "totallevel"}, enabledByDefault = false)
public final class TotalLevelPlugin extends Plugin {
    @Inject private TotalLevelScript script;
    @Inject private TotalLevelConfig config;
    @Inject private TotalLevelOverlay overlay;
    @Inject private OverlayManager overlayManager;

    @Provides
    TotalLevelConfig provideConfig(ConfigManager manager) {
        return manager.getConfig(TotalLevelConfig.class);
    }

    @Override
    protected void startUp() {
        overlayManager.add(overlay);
        script.run(config);
    }

    @Override
    protected void shutDown() {
        script.shutdown();
        overlayManager.remove(overlay);
    }

    @Subscribe
    public void onGameTick(GameTick event) {
        // Observation only: no waits, pathfinding, mouse or keyboard actions on this thread.
        script.onGameTick();
    }
}
