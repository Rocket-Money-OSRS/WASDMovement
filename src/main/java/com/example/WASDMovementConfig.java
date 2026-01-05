package com.example;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Range;

@ConfigGroup("wasdmovement")
public interface WASDMovementConfig extends Config
{
	@ConfigItem(
		keyName = "clickInterval",
		name = "Click Interval (ms)",
		description = "How often to send movement clicks while holding a direction key"
	)
	@Range(min = 50, max = 500)
	default int clickInterval()
	{
		return 100;
	}
}

