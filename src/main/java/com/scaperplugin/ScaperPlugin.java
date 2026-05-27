package com.scaperplugin;

import com.google.inject.Provides;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.IndexedSprite;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import okhttp3.OkHttpClient;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@PluginDescriptor(
        name = "Scaper",
        description = "Link your OSRS account to your clan's Discord server via Scaper bot",
        tags = {"clan", "discord", "link", "scaper"}
)
public class ScaperPlugin extends Plugin
{
        private static volatile int discordModIconIndex = -1;
        private static final int DISCORD_CHAT_ICON_SIZE = 11;

        @Inject
        private Client client;

        @Inject
        private ScaperConfig config;

        @Inject
        private ClientToolbar clientToolbar;

        @Inject
        private OkHttpClient httpClient;

        private ScaperPanel panel;
        private NavigationButton navButton;
        private ScaperTracker tracker;

        public static int getDiscordModIconIndex()
        {
                return discordModIconIndex;
        }

        private int installDiscordModIcon()
        {
                try (InputStream in = ScaperPlugin.class.getResourceAsStream("/discord_ingame.png"))
                {
                        if (in == null)
                        {
                                log.warn("discord_ingame.png resource not found");
                                return -1;
                        }

                        BufferedImage img = ImageIO.read(in);
                        if (img == null)
                        {
                                log.warn("Failed to decode discord_ingame.png resource");
                                return -1;
                        }

                        int width = DISCORD_CHAT_ICON_SIZE;
                        int height = DISCORD_CHAT_ICON_SIZE;

                        BufferedImage scaled = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
                        Graphics2D sg = scaled.createGraphics();
                        sg.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                        sg.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                        sg.drawImage(img, 0, 0, width, height, null);
                        sg.dispose();

                        // RuneLite modicons are indexed sprites: palette index 0 is transparent.
                        Map<Integer, Integer> paletteIndexByRgb = new LinkedHashMap<>();
                        int[] palette = new int[256];
                        byte[] pixels = new byte[width * height];
                        int nextPaletteIndex = 1;

                        for (int y = 0; y < height; y++)
                        {
                                for (int x = 0; x < width; x++)
                                {
                                        int argb = scaled.getRGB(x, y);
                                        int alpha = (argb >>> 24) & 0xFF;
                                        int idx = y * width + x;

                                        if (alpha < 16)
                                        {
                                                pixels[idx] = 0;
                                                continue;
                                        }

                                        int rgb = argb & 0x00FFFFFF;
                                        Integer paletteIndex = paletteIndexByRgb.get(rgb);
                                        if (paletteIndex == null)
                                        {
                                                if (nextPaletteIndex >= 256)
                                                {
                                                        // Too many colors for indexed sprite; collapse to nearest 5-bit channel color.
                                                        rgb = ((rgb >> 19) & 0x1F) << 19 | ((rgb >> 11) & 0x1F) << 11 | ((rgb >> 3) & 0x1F) << 3;
                                                        paletteIndex = paletteIndexByRgb.get(rgb);
                                                }

                                                if (paletteIndex == null)
                                                {
                                                        if (nextPaletteIndex >= 256)
                                                        {
                                                                paletteIndex = 1;
                                                        }
                                                        else
                                                        {
                                                                paletteIndex = nextPaletteIndex++;
                                                                palette[nextPaletteIndex - 1] = rgb;
                                                                paletteIndexByRgb.put(rgb, paletteIndex);
                                                        }
                                                }
                                        }

                                        pixels[idx] = (byte) (paletteIndex & 0xFF);
                                }
                        }

                        IndexedSprite customIcon = client.createIndexedSprite();
                        customIcon.setWidth(width);
                        customIcon.setHeight(height);
                        customIcon.setOriginalWidth(width);
                        customIcon.setOriginalHeight(height);
                        customIcon.setOffsetX(0);
                        customIcon.setOffsetY(0);
                        customIcon.setPixels(pixels);
                        customIcon.setPalette(Arrays.copyOf(palette, palette.length));

                        IndexedSprite[] modIcons = client.getModIcons();
                        if (modIcons == null)
                        {
                                log.warn("Mod icons not available yet");
                                return -1;
                        }

                        IndexedSprite[] extended = Arrays.copyOf(modIcons, modIcons.length + 1);
                        extended[modIcons.length] = customIcon;
                        client.setModIcons(extended);
                        return modIcons.length;
                }
                catch (Exception e)
                {
                        log.warn("Failed to install Discord mod icon", e);
                        return -1;
                }
        }

        @Override
        protected void startUp()
        {
                panel = new ScaperPanel(client, httpClient);
                tracker = new ScaperTracker(client, httpClient);

                // Generate a simple "S" icon programmatically
                BufferedImage icon = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = icon.createGraphics();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(new Color(0, 200, 83));
                g.fillRoundRect(0, 0, 16, 16, 4, 4);
                g.setColor(Color.WHITE);
                g.setFont(new Font("Arial", Font.BOLD, 12));
                FontMetrics fm = g.getFontMetrics();
                g.drawString("S", (16 - fm.stringWidth("S")) / 2, 13);
                g.dispose();

                navButton = NavigationButton.builder()
                        .tooltip("Scaper")
                        .icon(icon)
                        .priority(10)
                        .panel(panel)
                        .build();

                clientToolbar.addNavigation(navButton);
                if (discordModIconIndex < 0)
                {
                        discordModIconIndex = installDiscordModIcon();
                }
                log.info("Scaper plugin started");
        }

        @Override
        protected void shutDown()
        {
                clientToolbar.removeNavigation(navButton);
                panel.shutdown();
                tracker.reset();
                log.info("Scaper plugin stopped");
        }

        @Subscribe
        public void onGameTick(GameTick event)
        {
                tracker.onGameTick();
        }

        @Subscribe
        public void onChatMessage(ChatMessage event)
        {
                tracker.onChatMessage(event);
        }

        @Subscribe
        public void onGameStateChanged(GameStateChanged event)
        {
                if (event.getGameState() == GameState.LOGGED_IN)
                {
                        if (discordModIconIndex < 0)
                        {
                                discordModIconIndex = installDiscordModIcon();
                        }
                        panel.onLogin();
                }
                else if (event.getGameState() == GameState.LOGIN_SCREEN)
                {
                        tracker.reset();
                        panel.onLogout();
                }
        }

        @Provides
        ScaperConfig provideConfig(ConfigManager configManager)
        {
                return configManager.getConfig(ScaperConfig.class);
        }
}
