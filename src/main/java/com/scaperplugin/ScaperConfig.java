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
}
