package net.runelite.client.plugins.microbot.tradeunlock;

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
public final class TradeUnlockOverlay extends OverlayPanel {
    private final TradeUnlockScript script;
    private final TradeUnlockConfig config;

    @Inject
    public TradeUnlockOverlay(TradeUnlockScript script, TradeUnlockConfig config) {
        this.script = script;
        this.config = config;
        setPosition(OverlayPosition.TOP_LEFT);
        panelComponent.setPreferredSize(new Dimension(300, 0));
    }

    @Override
    public Dimension render(Graphics2D graphics) {
        if (!config.showOverlay()) { return null; }
        TradeUnlockScript.Status status = script.getStatus();
        panelComponent.getChildren().clear();
        panelComponent.getChildren().add(TitleComponent.builder()
            .text("Stage 0.2 | F2P Quests").color(new Color(120, 210, 230)).build());
        line("State", status.state.replace('_', ' '));
        line("Quest", status.quest);
        line("Objective", status.objective);
        line("Quest points", Integer.toString(status.questPoints));
        line("QP gained this run", Integer.toString(status.earnedPoints));
        long seconds = TimeUnit.MILLISECONDS.toSeconds(status.runtimeMillis);
        line("Runtime", String.format("%02d:%02d:%02d", seconds / 3600, (seconds / 60) % 60, seconds % 60));
        panelComponent.getChildren().add(LineComponent.builder().left("Status")
            .right(status.detail).rightColor("FAILED".equals(status.state) ? Color.RED : Color.WHITE).build());
        return super.render(graphics);
    }

    private void line(String left, String right) {
        panelComponent.getChildren().add(LineComponent.builder().left(left).right(right).build());
    }
}
