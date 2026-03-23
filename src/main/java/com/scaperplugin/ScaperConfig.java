package com.scaperplugin;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("scaper")
public interface ScaperConfig extends Config
{
	@ConfigItem(
		keyName = "apiUrl",
		name = "API URL",
		description = "The URL of the Scaper API server (provided by your clan admin)",
		position = 1
	)
	default String apiUrl()
	{
		return "http://localhost:3420";
	}

	// ── Tracking toggles ───────────────────────────────────────────────────

	@ConfigItem(
		keyName = "trackingEnabled",
		name = "Enable Tracking",
		description = "Send game data to the Scaper API for your clan dashboard",
		position = 2
	)
	default boolean trackingEnabled()
	{
		return true;
	}

	@ConfigItem(
		keyName = "trackingIntervalSeconds",
		name = "Tracking Interval (sec)",
		description = "How often to send data snapshots (minimum 10 seconds)",
		position = 3
	)
	default int trackingIntervalSeconds()
	{
		return 30;
	}

	@ConfigItem(
		keyName = "trackLocation",
		name = "Share Location",
		description = "Include your world map location in tracking data",
		position = 4
	)
	default boolean trackLocation()
	{
		return true;
	}

	@ConfigItem(
		keyName = "trackInventory",
		name = "Share Inventory",
		description = "Include inventory contents in tracking data",
		position = 5
	)
	default boolean trackInventory()
	{
		return true;
	}

	@ConfigItem(
		keyName = "trackEquipment",
		name = "Share Equipment",
		description = "Include worn equipment in tracking data",
		position = 6
	)
	default boolean trackEquipment()
	{
		return true;
	}

	@ConfigItem(
		keyName = "trackClanChat",
		name = "Log Clan Chat",
		description = "Send clan chat messages to the Scaper API",
		position = 7
	)
	default boolean trackClanChat()
	{
		return true;
	}

	@ConfigItem(
		keyName = "trackLoot",
		name = "Track Drops & KC",
		description = "Log valuable drops, boss kills, and collection log entries",
		position = 8
	)
	default boolean trackLoot()
	{
		return true;
	}
}
