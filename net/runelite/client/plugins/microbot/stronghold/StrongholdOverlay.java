package net.runelite.client.plugins.microbot.stronghold;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import javax.inject.Inject;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

public class StrongholdOverlay extends OverlayPanel {
    private final StrongholdScript script;

    @Inject
    StrongholdOverlay(StrongholdScript script) {
        this.script = script;
        setPosition(OverlayPosition.TOP_LEFT);
        setNillable(true);
    }

    @Override
    public Dimension render(Graphics2D graphics) {
        try {
            panelComponent.setPreferredSize(new Dimension(240, 110));
            panelComponent.getChildren().add(TitleComponent.builder()
                .text("Stage 0 Stronghold")
                .color(Color.CYAN)
                .build());

            panelComponent.getChildren().add(LineComponent.builder()
                .left("FSM State:")
                .right(script.getState().toString())
                .rightColor(script.getState() == StrongholdScript.State.COMPLETED ? Color.GREEN :
                    script.getState() == StrongholdScript.State.FAILED ? Color.RED : Color.YELLOW)
                .build());

            panelComponent.getChildren().add(LineComponent.builder()
                .left("Safety Corridor:")
                .right("15-Tile Safe")
                .rightColor(Color.GREEN)
                .build());

            panelComponent.getChildren().add(LineComponent.builder()
                .left("Target Loot:")
                .right("10K GP + Boots")
                .rightColor(Color.WHITE)
                .build());

        } catch (Exception ex) {
            // Avoid crashing render thread
        }
        return super.render(graphics);
    }
}
