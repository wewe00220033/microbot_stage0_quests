package net.runelite.client.plugins.microbot.stronghold;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup(StrongholdConfig.GROUP)
public interface StrongholdConfig extends Config {
    String GROUP = "strongholdStage0";

    @ConfigItem(
        keyName = "enableJitter",
        name = "Enable tile jitter",
        description = "Randomize walking destinations by up to one tile on each axis. "
            + "Dungeon approach offsets must remain collision-reachable.",
        position = 0
    )
    default boolean enableJitter() {
        return true;
    }

    @ConfigItem(
        keyName = "enableCrashRecovery",
        name = "Enable crash recovery",
        description = "Abort the stage and return to the GE bank when outside the expected "
            + "floor/route zone plus a 15-tile margin. Recovery deposits items but never reports success.",
        position = 1
    )
    default boolean enableCrashRecovery() {
        return true;
    }
}
