package com.scaperplugin;

import com.google.inject.Provides;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Menu;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.ClientTick;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.api.Perspective;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.chatbox.ChatboxPanelManager;
import net.runelite.client.input.KeyManager;
import net.runelite.client.input.MouseManager;
import okhttp3.OkHttpClient;

import java.awt.*;
import java.awt.image.BufferedImage;

@Slf4j
@PluginDescriptor(
        name = "Scaper",
        description = "Link your OSRS account to your clan's Discord server via Scaper bot",
        tags = {"clan", "discord", "link", "scaper"}
)
public class ScaperPlugin extends Plugin
{
        @Inject
        private Client client;

        @Inject
        private ScaperConfig config;

        @Inject
        private ClientToolbar clientToolbar;

        @Inject
        private OkHttpClient httpClient;

        @Inject
        private OverlayManager overlayManager;

        @Inject
        private ClientThread clientThread;

        @Inject
        private ChatboxPanelManager chatboxPanelManager;

        @Inject
        private KeyManager keyManager;

        @Inject
        private MouseManager mouseManager;

        private ScaperPanel panel;
        private NavigationButton navButton;
        private ScaperTracker tracker;
        private GnomeCompanion gnomeCompanion;
        private GnomeOverlay gnomeOverlay;
        private GnomeDialogue gnomeDialogue;

        @Override
        protected void startUp()
        {
                panel = new ScaperPanel(client, config, httpClient);
                tracker = new ScaperTracker(client, config, httpClient);
                gnomeCompanion = new GnomeCompanion(client, config, httpClient);
                gnomeCompanion.setClientThread(clientThread);
                gnomeDialogue = new GnomeDialogue(
                        chatboxPanelManager, client, clientThread, keyManager, mouseManager, config);
                gnomeCompanion.setGnomeDialogue(gnomeDialogue);
                gnomeOverlay = new GnomeOverlay(client, gnomeCompanion);
                overlayManager.add(gnomeOverlay);

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
                log.info("Scaper plugin started");
        }

        @Override
        protected void shutDown()
        {
                overlayManager.remove(gnomeOverlay);
                clientToolbar.removeNavigation(navButton);
                panel.shutdown();
                tracker.reset();
                gnomeCompanion.reset();
                log.info("Scaper plugin stopped");
        }

        @Subscribe
        public void onGameTick(GameTick event)
        {
                tracker.onGameTick();
                gnomeCompanion.onGameTick(tracker.getRsn());
        }

        @Subscribe
        public void onClientTick(ClientTick event)
        {
                gnomeCompanion.onClientTick();
                injectGnomeMenuEntries();
        }

        /**
         * Inject right-click menu entries when the mouse is hovering near the gnome.
         */
        private void injectGnomeMenuEntries()
        {
                if (!gnomeCompanion.isSpawned()) return;
                if (client.getGameState() != GameState.LOGGED_IN) return;

                LocalPoint gnomeLp = gnomeCompanion.getGnomeLocalPoint();
                if (gnomeLp == null) return;

                // Get gnome's canvas position
                net.runelite.api.Point canvasPoint = Perspective.localToCanvas(
                        client, gnomeLp, gnomeCompanion.getGnomePlane(), 100
                );
                if (canvasPoint == null) return;

                // Check if mouse is near the gnome (within ~40 pixel radius)
                net.runelite.api.Point mousePos = client.getMouseCanvasPosition();
                int dx = mousePos.getX() - canvasPoint.getX();
                int dy = mousePos.getY() - canvasPoint.getY();
                if (dx * dx + dy * dy > 40 * 40) return;

                // Check if entries already injected this frame to avoid duplicates
                Menu menu = client.getMenu();
                MenuEntry[] existing = menu.getMenuEntries();
                for (MenuEntry entry : existing)
                {
                        if (entry.getTarget() != null && entry.getTarget().contains("Gnome"))
                        {
                                return; // already injected
                        }
                }

                menu.createMenuEntry(1)
                        .setOption("Examine")
                        .setTarget("<col=ffff00>Gnome</col>")
                        .setType(MenuAction.RUNELITE)
                        .onClick(e -> client.addChatMessage(
                                net.runelite.api.ChatMessageType.GAMEMESSAGE, "",
                                "A friendly gnome companion from the Tree Gnome Stronghold.", ""));

                // Only show Dismiss when the gnome is following
                if (gnomeCompanion.getGnomeState() == GnomeCompanion.GnomeState.FOLLOWING)
                {
                        menu.createMenuEntry(2)
                                .setOption("Dismiss")
                                .setTarget("<col=ffff00>Gnome</col>")
                                .setType(MenuAction.RUNELITE)
                                .onClick(e -> gnomeCompanion.dismiss());
                }

                menu.createMenuEntry(3)
                        .setOption("Talk-to")
                        .setTarget("<col=ffff00>Gnome</col>")
                        .setType(MenuAction.RUNELITE)
                        .onClick(e -> gnomeCompanion.startDialogue());
        }

        @Subscribe
        public void onChatMessage(ChatMessage event)
        {
                tracker.onChatMessage(event);
                gnomeCompanion.onChatMessage(event, tracker.getRsn());
        }

        @Subscribe
        public void onGameStateChanged(GameStateChanged event)
        {
                if (event.getGameState() == GameState.LOGGED_IN)
                {
                        panel.onLogin();
                }
                else if (event.getGameState() == GameState.LOGIN_SCREEN)
                {
                        tracker.reset();
                        gnomeCompanion.reset();
                        panel.onLogout();
                }
                else if (event.getGameState() == GameState.HOPPING
                        || event.getGameState() == GameState.LOADING)
                {
                        // Scene is rebuilt on world hop or chunk crossing — reset gnome so it respawns
                        gnomeCompanion.reset();
                }
        }

        @Provides
        ScaperConfig provideConfig(ConfigManager configManager)
        {
                return configManager.getConfig(ScaperConfig.class);
        }
}
