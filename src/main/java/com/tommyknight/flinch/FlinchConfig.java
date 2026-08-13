package com.tommyknight.flinch;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup(FlinchPlugin.CONFIG_GROUP)
public interface FlinchConfig extends Config
{
	@ConfigItem(
		keyName = "showSidebar",
		name = "Show sidebar button",
		description = "Show the Flinch button in the sidebar. Every other setting lives in that panel.",
		position = 1
	)
	default boolean showSidebar()
	{
		return true;
	}
}
