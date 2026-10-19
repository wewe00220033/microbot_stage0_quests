package net.runelite.client.plugins.microbot.tradeunlock;

import com.google.inject.Provides;
import javax.inject.Inject;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;

@PluginDescriptor(
    name = "Stage 0.2 Trade Unlock Quests",
    description = "Bank-supplied Cook's Assistant, Romeo & Juliet, Sheep Shearer and Goblin Diplomacy.",
    tags = {"microbot", "quest", "f2p", "onboarding"},
    enabledByDefault = false
)
public final class TradeUnlockPlugin extends Plugin {
    @Inject private TradeUnlockScript script;
    @Inject private TradeUnlockConfig config;
    @Inject private TradeUnlockOverlay overlay;
    @Inject private OverlayManager overlayManager;

    @Provides
    TradeUnlockConfig provideConfig(ConfigManager manager) {
        return manager.getConfig(TradeUnlockConfig.class);
    }

    @Override
    protected void startUp() {
        overlayManager.add(overlay);
        if (!script.start(config)) {
            overlayManager.remove(overlay);
        }
    }

    @Override
    protected void shutDown() {
        script.shutdown();
        overlayManager.remove(overlay);
    }
}
