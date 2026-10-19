package net.runelite.client.plugins.microbot.totallevel;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Range;

@ConfigGroup(TotalLevelConfig.GROUP)
public interface TotalLevelConfig extends Config {
    String GROUP = "totalLevelStage03";

    @ConfigItem(keyName = "enableJitter", name = "Tile jitter", position = 0,
        description = "Use offsets -1, 0 or +1 for walking destinations, avoiding loaded blocked tiles.")
    default boolean enableJitter() { return true; }

    @ConfigItem(keyName = "enableCrashRecovery", name = "Corridor recovery", position = 1,
        description = "Evacuate to Lumbridge bank and fail the run after leaving the expected route/activity area.")
    default boolean enableCrashRecovery() { return true; }

    @ConfigItem(keyName = "cameraVariation", name = "Camera variation", position = 2,
        description = "Occasionally adjust camera yaw while gathering; not a guarantee against bans.")
    default boolean cameraVariation() { return true; }

    @ConfigItem(keyName = "tabPeeking", name = "Tab peeking", position = 3,
        description = "Occasionally view Skills or Equipment, then restore Inventory.")
    default boolean tabPeeking() { return true; }

    @ConfigItem(keyName = "mouseOffscreen", name = "Mouse off-canvas", position = 4,
        description = "Occasionally move the virtual mouse outside the game canvas without clicking.")
    default boolean mouseOffscreen() { return true; }

    @Range(min = 5, max = 120)
    @ConfigItem(keyName = "microBreakMinSeconds", name = "Micro-break minimum (s)", position = 5,
        description = "Minimum idle pause, scheduled every five to ten minutes when safely idle.")
    default int microBreakMinSeconds() { return 12; }

    @Range(min = 5, max = 180)
    @ConfigItem(keyName = "microBreakMaxSeconds", name = "Micro-break maximum (s)", position = 6,
        description = "Maximum idle pause; must not be smaller than the minimum.")
    default int microBreakMaxSeconds() { return 45; }

    @Range(min = 10, max = 300)
    @ConfigItem(keyName = "rotationBreakMinSeconds", name = "Bank break minimum (s)", position = 7,
        description = "Minimum break at the bank between 20-to-35-minute activity blocks.")
    default int rotationBreakMinSeconds() { return 30; }

    @Range(min = 10, max = 600)
    @ConfigItem(keyName = "rotationBreakMaxSeconds", name = "Bank break maximum (s)", position = 8,
        description = "Maximum bank break; must not be smaller than the minimum.")
    default int rotationBreakMaxSeconds() { return 120; }

    @Range(min = 30, max = 720)
    @ConfigItem(keyName = "maximumRuntimeMinutes", name = "Maximum runtime (minutes)", position = 9,
        description = "Abort and bank if the total-level goal has not been reached before this deadline.")
    default int maximumRuntimeMinutes() { return 180; }

    @ConfigItem(keyName = "useDraynorTrees", name = "Optional Draynor trees", position = 10,
        description = "Use Draynor trees instead of Lumbridge trees. Dark wizards can attack low-level accounts. Bank and net/cooking route remain Lumbridge. Applied on restart.")
    default boolean useDraynorTrees() { return false; }

    @ConfigItem(keyName = "showOverlay", name = "Show overlay", position = 11,
        description = "Show total level, trained levels, state, activity, timer and observed XP gains.")
    default boolean showOverlay() { return true; }
}
