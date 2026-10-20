package io.github.kyloschmeilo.sellmacro;

import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.ToggleKeyMapping;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;

/** Starts the last macro again once the player hasn't touched mouse or keyboard for a while. */
public final class IdleRestarter {
	private static long lastActivity = System.currentTimeMillis();
	private static float lastYRot;
	private static float lastXRot;
	private static double lastMouseX;
	private static double lastMouseY;
	private static Screen lastScreen;

	private IdleRestarter() {
	}

	public static void tick(Minecraft client) {
		long now = System.currentTimeMillis();
		LocalPlayer player = client.player;

		if (player == null || client.level == null || player.isDeadOrDying() || SellMacro.isRunning()) {
			// Only time spent in game without a running macro counts as idle.
			lastActivity = now;
			snapshot(client, player);
			return;
		}

		if (isActive(client, player)) {
			lastActivity = now;
		}

		snapshot(client, player);
		int minutes = SellMacroConfig.get().idleRestartMinutes;

		if (minutes <= 0 || now - lastActivity < minutes * 60_000L) {
			return;
		}

		lastActivity = now;
		List<Item> items = SellMacroClient.resolve(SellMacroConfig.get().lastItems);

		if (items.isEmpty() || !AllowedPlayers.isAllowed()) {
			return;
		}

		player.sendSystemMessage(SellMacro.prefixed(Component.literal(minutes + " Min. inaktiv, starte das letzte Makro.")
				.withStyle(ChatFormatting.YELLOW)));
		SellMacroClient.start(items, player::sendSystemMessage);
	}

	/** Mouse moved, view turned, a key is held, or the player opened/closed a screen. */
	private static boolean isActive(Minecraft client, LocalPlayer player) {
		if (player.getYRot() != lastYRot || player.getXRot() != lastXRot) {
			return true;
		}

		if (client.mouseHandler.xpos() != lastMouseX || client.mouseHandler.ypos() != lastMouseY) {
			return true;
		}

		for (KeyMapping key : client.options.keyMappings) {
			// Sneak/sprint set to "toggle" stay down while toggled on, without the player doing anything.
			if (!(key instanceof ToggleKeyMapping) && key.isDown()) {
				return true;
			}
		}

		// The pause menu Minecraft opens on its own after alt-tabbing is no activity.
		return client.gui.screen() != lastScreen && client.isWindowActive();
	}

	private static void snapshot(Minecraft client, LocalPlayer player) {
		if (player != null) {
			lastYRot = player.getYRot();
			lastXRot = player.getXRot();
		}

		lastMouseX = client.mouseHandler.xpos();
		lastMouseY = client.mouseHandler.ypos();
		lastScreen = client.gui.screen();
	}
}
