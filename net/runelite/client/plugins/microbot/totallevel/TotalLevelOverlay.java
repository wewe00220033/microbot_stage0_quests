package net.runelite.client.plugins.microbot.totallevel;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

@Singleton
public final class TotalLevelOverlay extends OverlayPanel {
    private final TotalLevelScript script;
    private final TotalLevelConfig config;

    @Inject
    public TotalLevelOverlay(TotalLevelScript script, TotalLevelConfig config) {
        this.script = script;
        this.config = config;
        setPosition(OverlayPosition.TOP_LEFT);
        panelComponent.setPreferredSize(new Dimension(320, 0));
    }

    @Override
    public Dimension render(Graphics2D graphics) {
        if (!config.showOverlay()) { return null; }
        TotalLevelScript.Status status = script.getStatus();
        panelComponent.getChildren().clear();
        panelComponent.getChildren().add(TitleComponent.builder().text("Stage 0.3 | Total Level 100")
            .color(new Color(125, 220, 175)).build());
        line("State", status.state.replace('_', ' '));
        line("Activity", status.activity.replace('_', ' '));
        line("Total level", status.total + " / " + TotalLevelScript.TARGET_TOTAL);
        line("Levels gained", Integer.toString(Math.max(0, status.total - status.baselineTotal)));
        line("Woodcutting / Firemaking", status.woodcutting + " / " + status.firemaking);
        line("Fishing / Cooking", status.fishing + " / " + status.cooking);
        line("Observed training XP", String.format("%,d", status.gainedXp));
        line("Block remaining", time(status.blockRemainingMillis));
        line("Blocks banked", Integer.toString(status.completedBlocks));
        line("Runtime", time(status.runtimeMillis));
        panelComponent.getChildren().add(LineComponent.builder().left("Status").right(status.detail)
            .rightColor("FAILED".equals(status.state) ? Color.RED
                : "COMPLETED".equals(status.state) ? Color.GREEN : Color.WHITE).build());
        return super.render(graphics);
    }

    private void line(String left, String right) {
        panelComponent.getChildren().add(LineComponent.builder().left(left).right(right).build());
    }

    private static String time(long millis) {
        long seconds = TimeUnit.MILLISECONDS.toSeconds(millis);
        return String.format("%02d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60);
    }
}
