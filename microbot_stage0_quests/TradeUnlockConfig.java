package net.runelite.client.plugins.microbot.tradeunlock;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Range;

@ConfigGroup(TradeUnlockConfig.GROUP)
public interface TradeUnlockConfig extends Config {
    String GROUP = "tradeUnlockStage02";

    @ConfigItem(keyName = "enableJitter", name = "Tile jitter",
        description = "Offset walking destinations by at most one tile on each axis.", position = 0)
    default boolean enableJitter() { return true; }

    @ConfigItem(keyName = "enableCrashRecovery", name = "Corridor recovery",
        description = "Abort the quest run and return to GE after leaving the planned 15-tile route corridor.", position = 1)
    default boolean enableCrashRecovery() { return true; }

    @ConfigItem(keyName = "redYellowFallback", name = "Mix orange dye",
        description = "If orange dye is unavailable, withdraw one red and one yellow dye and combine them.", position = 2)
    default boolean redYellowFallback() { return true; }

    @Range(min = 2, max = 20)
    @ConfigItem(keyName = "stateTimeoutMinutes", name = "State timeout (minutes)",
        description = "Maximum time in one workflow state before aborting or evacuating.", position = 3)
    default int stateTimeoutMinutes() { return 8; }

    @Range(min = 1, max = 10)
    @ConfigItem(keyName = "maxWalkRetries", name = "Walk retries",
        description = "Maximum consecutive eight-second stalls before aborting a travel leg.", position = 4)
    default int maxWalkRetries() { return 5; }

    @ConfigItem(keyName = "showOverlay", name = "Show status overlay",
        description = "Display quest, state, observed quest points and failure status.", position = 5)
    default boolean showOverlay() { return true; }
}
