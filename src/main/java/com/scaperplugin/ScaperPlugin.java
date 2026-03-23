package com.scaperplugin;

import com.google.inject.Provides;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
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

	private ScaperPanel panel;
	private NavigationButton navButton;
	private ScaperTracker tracker;

	@Override
	protected void startUp()
	{
		panel = new ScaperPanel(client, config, httpClient);
		tracker = new ScaperTracker(client, config, httpClient);

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
