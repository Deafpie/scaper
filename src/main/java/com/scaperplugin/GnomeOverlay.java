package com.scaperplugin;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.Point;
import net.runelite.api.coords.LocalPoint;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayPriority;

import java.awt.*;

/**
 * Draws overhead text above the gnome companion using the authentic OSRS
 * RuneScape Bold font — matching real player/NPC overhead chat exactly.
 */
@Slf4j
public class GnomeOverlay extends Overlay
{
    private static final Color TEXT_COLOR = new Color(255, 255, 0); // Yellow — same as OSRS overhead
    private static final Color SHADOW_COLOR = new Color(0, 0, 0);  // Pure black shadow, 1px offset
    private static final int MODEL_HEIGHT_OFFSET = 180; // local units above gnome origin

    private final Client client;
    private final GnomeCompanion gnomeCompanion;

    public GnomeOverlay(Client client, GnomeCompanion gnomeCompanion)
    {
        this.client = client;
        this.gnomeCompanion = gnomeCompanion;

        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_SCENE);
        setPriority(OverlayPriority.HIGH);
    }

    @Override
    public Dimension render(Graphics2D graphics)
    {
        String text = gnomeCompanion.getOverheadText();
        if (text == null || text.isEmpty()) return null;

        LocalPoint gnomeLp = gnomeCompanion.getGnomeLocalPoint();
        if (gnomeLp == null) return null;

        int plane = gnomeCompanion.getGnomePlane();

        // Get the canvas point above the gnome's position
        Point canvasPoint = Perspective.localToCanvas(client, gnomeLp, plane, MODEL_HEIGHT_OFFSET);
        if (canvasPoint == null) return null;

        // Word-wrap if needed (for longer responses)
        String[] lines = wrapText(text, 40);

        // Use the authentic RuneScape Bold font from RuneLite's FontManager
        Font rsFont = FontManager.getRunescapeBoldFont();
        graphics.setFont(rsFont);
        FontMetrics fm = graphics.getFontMetrics();

        int lineHeight = fm.getHeight();
        int totalHeight = lineHeight * lines.length;

        // Draw each line centered above the gnome
        int startY = canvasPoint.getY() - totalHeight;

        for (int i = 0; i < lines.length; i++)
        {
            String line = lines[i];
            int lineWidth = fm.stringWidth(line);
            int x = canvasPoint.getX() - lineWidth / 2;
            int y = startY + (i * lineHeight);

            // OSRS-style shadow: single 1px offset to bottom-right
            graphics.setColor(SHADOW_COLOR);
            graphics.drawString(line, x + 1, y + 1);

            // Main text in yellow
            graphics.setColor(TEXT_COLOR);
            graphics.drawString(line, x, y);
        }

        return null;
    }

    /**
     * Simple word-wrap: splits text into lines of at most maxChars characters,
     * breaking at word boundaries.
     */
    private static String[] wrapText(String text, int maxChars)
    {
        if (text.length() <= maxChars) return new String[] { text };

        java.util.List<String> lines = new java.util.ArrayList<>();
        String remaining = text.trim();

        while (remaining.length() > 0)
        {
            if (remaining.length() <= maxChars)
            {
                lines.add(remaining);
                break;
            }

            int splitAt = remaining.lastIndexOf(' ', maxChars);
            if (splitAt <= 0) splitAt = maxChars;

            lines.add(remaining.substring(0, splitAt).trim());
            remaining = remaining.substring(splitAt).trim();
        }

        return lines.toArray(new String[0]);
    }
}
