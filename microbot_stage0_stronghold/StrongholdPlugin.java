package net.runelite.client.plugins.microbot.stronghold;

import com.google.inject.Provides;
import javax.inject.Inject;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;

import net.runelite.client.ui.overlay.OverlayManager;

@PluginDescriptor(
    name = "Stage 0 Stronghold Onboarding",
    description = "Collects Stronghold rewards and returns to the Grand Exchange bank.",
    tags = {"microbot", "stronghold", "security", "onboarding"},
    enabledByDefault = false
)
public final class StrongholdPlugin extends Plugin {
    @Inject
    private StrongholdScript script;

    @Inject
    private StrongholdConfig config;

    @Inject
    private OverlayManager overlayManager;

    @Inject
    private StrongholdOverlay overlay;

    @Provides
    StrongholdConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(StrongholdConfig.class);
    }

    @Override
    protected void startUp() {
        if (overlayManager != null && overlay != null) {
            overlayManager.add(overlay);
        }
        script.start(config);
    }

    @Override
    protected void shutDown() {
        if (overlayManager != null && overlay != null) {
            overlayManager.remove(overlay);
        }
        script.shutdown();
    }
}
