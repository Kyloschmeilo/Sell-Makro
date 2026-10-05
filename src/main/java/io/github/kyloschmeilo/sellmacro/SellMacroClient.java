package io.github.kyloschmeilo.sellmacro;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.arguments.item.ItemArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;

public final class SellMacroClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		ClientTickEvents.END_CLIENT_TICK.register(SellMacro::tick);
		ClientCommandRegistrationCallback.EVENT.register(SellMacroClient::registerCommands);
	}

	private static void registerCommands(CommandDispatcher<FabricClientCommandSource> dispatcher, CommandBuildContext buildContext) {
		if (!AllowedPlayers.isAllowed()) {
			dispatcher.register(ClientCommands.literal("sellmacro")
					.executes(SellMacroClient::notAllowed)
					.then(ClientCommands.argument("args", StringArgumentType.greedyString())
							.executes(SellMacroClient::notAllowed)));
			return;
		}

		dispatcher.register(ClientCommands.literal("sellmacro")
				.then(ClientCommands.literal("stop")
						.executes(context -> {
							if (!SellMacro.isRunning()) {
								context.getSource().sendError(SellMacro.prefixed(Component.literal("Das Makro läuft gerade nicht.")));
								return 0;
							}

							SellMacro.stop(Component.literal("Makro gestoppt."));
							return 1;
						}))
				.then(ClientCommands.literal("delay")
						.executes(context -> {
							context.getSource().sendFeedback(SellMacro.prefixed(Component.literal(
									"Verzögerung zwischen zwei Klicks: " + SellMacro.getClickDelay() + " Ticks")));
							return 1;
						})
						.then(ClientCommands.argument("ticks", IntegerArgumentType.integer(0, SellMacro.MAX_CLICK_DELAY))
								.executes(context -> {
									SellMacro.setClickDelay(IntegerArgumentType.getInteger(context, "ticks"));
									context.getSource().sendFeedback(SellMacro.prefixed(Component.literal(
											"Verzögerung zwischen zwei Klicks auf " + SellMacro.getClickDelay() + " Ticks gesetzt.")));
									return 1;
								})))
				.then(ClientCommands.argument("item", ItemArgument.item(buildContext))
						.executes(context -> {
							Item item = ItemArgument.getItem(context, "item").item().value();
							SellMacro.start(item);
							context.getSource().sendFeedback(SellMacro.prefixed(Component.literal("Verkaufe ")
									.append(SellMacro.itemName(item))
									.append(" bis du die /sell GUI schließt. ")
									.append(Component.literal("(/sellmacro stop)").withStyle(ChatFormatting.GRAY))));
							return 1;
						})));
	}

	private static int notAllowed(CommandContext<FabricClientCommandSource> context) {
		context.getSource().sendError(SellMacro.prefixed(Component.literal("Du bist nicht berechtigt, diese Mod zu nutzen.")));
		return 0;
	}
}
