package io.github.kyloschmeilo.sellmacro;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.arguments.item.ItemArgument;
import net.minecraft.commands.arguments.item.ItemInput;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

public final class SellMacroClient implements ClientModInitializer {
	public static final Logger LOGGER = LoggerFactory.getLogger("sellmacro");

	private static final KeyMapping.Category KEY_CATEGORY = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("sellmacro", "main"));
	private static KeyMapping toggleKey;

	private static final SuggestionProvider<FabricClientCommandSource> PRESET_SUGGESTIONS = (context, builder) -> {
		for (String name : SellMacroConfig.get().presets.keySet()) {
			if (name.startsWith(builder.getRemainingLowerCase())) {
				builder.suggest(name);
			}
		}

		return builder.buildFuture();
	};

	@Override
	public void onInitializeClient() {
		toggleKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.sellmacro.toggle", GLFW.GLFW_KEY_K, KEY_CATEGORY));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (toggleKey.consumeClick()) {
				onToggleKey(client);
			}

			SellMacro.tick(client);
		});
		ClientReceiveMessageEvents.GAME.register(SellMacro::onGameMessage);
		ClientCommandRegistrationCallback.EVENT.register(SellMacroClient::registerCommands);
	}

	/** Hotkey: stops a running macro, otherwise restarts the last items or sells the held item. */
	private static void onToggleKey(Minecraft client) {
		LocalPlayer player = client.player;

		if (player == null) {
			return;
		}

		if (!AllowedPlayers.isAllowed()) {
			player.sendSystemMessage(notAllowedMessage());
			return;
		}

		if (SellMacro.isRunning()) {
			SellMacro.stop(Component.literal("Makro gestoppt."));
			return;
		}

		List<Item> items = resolve(SellMacroConfig.get().lastItems);

		if (items.isEmpty()) {
			ItemStack held = player.getMainHandItem();

			if (held.isEmpty()) {
				player.sendSystemMessage(SellMacro.prefixed(Component.literal(
						"Noch keine Items gewählt. Nutze /sellmacro <item> oder halte ein Item in der Hand.").withStyle(ChatFormatting.RED)));
				return;
			}

			items = List.of(held.getItem());
		}

		start(items, player::sendSystemMessage);
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
				.executes(SellMacroClient::help)
				.then(ClientCommands.literal("help").executes(SellMacroClient::help))
				.then(ClientCommands.literal("stop").executes(context -> {
					if (!SellMacro.isRunning()) {
						return error(context, "Das Makro läuft gerade nicht.");
					}

					SellMacro.stop(Component.literal("Makro gestoppt."));
					return 1;
				}))
				.then(ClientCommands.literal("hand").executes(context -> {
					ItemStack held = context.getSource().getPlayer().getMainHandItem();

					if (held.isEmpty()) {
						return error(context, "Du hältst kein Item in der Hand.");
					}

					return start(context, List.of(held.getItem()));
				}))
				.then(presetCommand())
				.then(ClientCommands.literal("delay")
						.executes(context -> feedback(context, "Verzögerung zwischen zwei Klicks: " + SellMacroConfig.get().clickDelay + " Ticks"))
						.then(ClientCommands.argument("ticks", IntegerArgumentType.integer(0, SellMacro.MAX_CLICK_DELAY))
								.executes(context -> {
									SellMacroConfig config = SellMacroConfig.get();
									config.clickDelay = IntegerArgumentType.getInteger(context, "ticks");
									config.save();
									return feedback(context, "Verzögerung zwischen zwei Klicks auf " + config.clickDelay + " Ticks gesetzt.");
								})))
				.then(ClientCommands.literal("protect")
						.executes(context -> feedback(context, "Schutz für umbenannte/verzauberte Items: " + onOff(SellMacroConfig.get().protectSpecialItems)))
						.then(ClientCommands.argument("enabled", BoolArgumentType.bool())
								.executes(context -> {
									SellMacroConfig config = SellMacroConfig.get();
									config.protectSpecialItems = BoolArgumentType.getBool(context, "enabled");
									config.save();
									return feedback(context, "Schutz für umbenannte/verzauberte Items: " + onOff(config.protectSpecialItems));
								})))
				.then(ClientCommands.literal("damagestop")
						.executes(context -> feedback(context, "Stopp bei Schaden: " + onOff(SellMacroConfig.get().stopOnDamage)))
						.then(ClientCommands.argument("enabled", BoolArgumentType.bool())
								.executes(context -> {
									SellMacroConfig config = SellMacroConfig.get();
									config.stopOnDamage = BoolArgumentType.getBool(context, "enabled");
									config.save();
									return feedback(context, "Stopp bei Schaden: " + onOff(config.stopOnDamage));
								})))
				.then(ClientCommands.literal("limit")
						.executes(context -> feedback(context, "Limits: " + limitsText()))
						.then(ClientCommands.literal("rounds")
								.then(ClientCommands.argument("rounds", IntegerArgumentType.integer(0))
										.executes(context -> {
											SellMacroConfig config = SellMacroConfig.get();
											config.maxRounds = IntegerArgumentType.getInteger(context, "rounds");
											config.save();
											return feedback(context, "Limits: " + limitsText());
										})))
						.then(ClientCommands.literal("money")
								.then(ClientCommands.argument("amount", DoubleArgumentType.doubleArg(0))
										.executes(context -> {
											SellMacroConfig config = SellMacroConfig.get();
											config.moneyLimit = DoubleArgumentType.getDouble(context, "amount");
											config.save();
											return feedback(context, "Limits: " + limitsText());
										}))))
				.then(ClientCommands.literal("earnings")
						.then(ClientCommands.literal("pattern")
								.executes(context -> feedback(context, "Geld-Muster: " + SellMacroConfig.get().earningsPattern))
								.then(ClientCommands.literal("reset").executes(context -> {
									SellMacroConfig config = SellMacroConfig.get();
									config.earningsPattern = SellMacroConfig.DEFAULT_EARNINGS_PATTERN;
									config.save();
									return feedback(context, "Geld-Muster zurückgesetzt.");
								}))
								.then(ClientCommands.argument("regex", StringArgumentType.greedyString())
										.executes(context -> {
											String regex = StringArgumentType.getString(context, "regex");

											if (!Earnings.isValidPattern(regex)) {
												return error(context, "Ungültiger regulärer Ausdruck.");
											}

											SellMacroConfig config = SellMacroConfig.get();
											config.earningsPattern = regex;
											config.save();
											return feedback(context, "Geld-Muster gesetzt.");
										})))
						.then(ClientCommands.literal("test")
								.then(ClientCommands.argument("message", StringArgumentType.greedyString())
										.executes(context -> feedback(context, "Erkannter Betrag: "
												+ Earnings.format(Earnings.parse(StringArgumentType.getString(context, "message"))))))))
				.then(ClientCommands.literal("settings").executes(SellMacroClient::settings))
				.then(itemArguments(buildContext, 1)));
	}

	/** item1 [item2] ... [item9], each one optional after the first. */
	private static RequiredArgumentBuilder<FabricClientCommandSource, ItemInput> itemArguments(CommandBuildContext buildContext, int index) {
		RequiredArgumentBuilder<FabricClientCommandSource, ItemInput> argument = ClientCommands.argument("item" + index, ItemArgument.item(buildContext))
				.executes(context -> start(context, itemsFromArguments(context, index)));

		if (index < SellMacro.MAX_ITEMS) {
			argument.then(itemArguments(buildContext, index + 1));
		}

		return argument;
	}

	private static List<Item> itemsFromArguments(CommandContext<FabricClientCommandSource> context, int count) {
		List<Item> items = new ArrayList<>();

		for (int i = 1; i <= count; i++) {
			items.add(ItemArgument.getItem(context, "item" + i).item().value());
		}

		return items;
	}

	private static com.mojang.brigadier.builder.LiteralArgumentBuilder<FabricClientCommandSource> presetCommand() {
		return ClientCommands.literal("preset")
				.executes(SellMacroClient::listPresets)
				.then(ClientCommands.literal("list").executes(SellMacroClient::listPresets))
				.then(ClientCommands.literal("save")
						.then(ClientCommands.argument("name", StringArgumentType.word())
								.suggests(PRESET_SUGGESTIONS)
								.executes(context -> {
									SellMacroConfig config = SellMacroConfig.get();

									if (config.lastItems.isEmpty()) {
										return error(context, "Starte zuerst das Makro mit den Items, die du speichern willst.");
									}

									String name = StringArgumentType.getString(context, "name").toLowerCase(Locale.ROOT);
									config.presets.put(name, new ArrayList<>(config.lastItems));
									config.save();
									return feedback(context, "Preset \"" + name + "\" gespeichert: " + String.join(", ", config.lastItems));
								})))
				.then(ClientCommands.literal("delete")
						.then(ClientCommands.argument("name", StringArgumentType.word())
								.suggests(PRESET_SUGGESTIONS)
								.executes(context -> {
									SellMacroConfig config = SellMacroConfig.get();
									String name = StringArgumentType.getString(context, "name").toLowerCase(Locale.ROOT);

									if (config.presets.remove(name) == null) {
										return error(context, "Preset \"" + name + "\" gibt es nicht.");
									}

									config.save();
									return feedback(context, "Preset \"" + name + "\" gelöscht.");
								})))
				.then(ClientCommands.argument("name", StringArgumentType.word())
						.suggests(PRESET_SUGGESTIONS)
						.executes(context -> {
							String name = StringArgumentType.getString(context, "name").toLowerCase(Locale.ROOT);
							List<String> ids = SellMacroConfig.get().presets.get(name);

							if (ids == null) {
								return error(context, "Preset \"" + name + "\" gibt es nicht. (/sellmacro preset list)");
							}

							return start(context, resolve(ids));
						}));
	}

	private static int listPresets(CommandContext<FabricClientCommandSource> context) {
		Map<String, List<String>> presets = SellMacroConfig.get().presets;

		if (presets.isEmpty()) {
			return feedback(context, "Keine Presets. Speichern: /sellmacro preset save <name> (speichert die zuletzt gestarteten Items)");
		}

		presets.forEach((name, ids) -> context.getSource().sendFeedback(SellMacro.prefixed(
				Component.literal(name).withStyle(ChatFormatting.YELLOW).append(Component.literal(": " + String.join(", ", ids)).withStyle(ChatFormatting.WHITE)))));
		return presets.size();
	}

	private static int settings(CommandContext<FabricClientCommandSource> context) {
		SellMacroConfig config = SellMacroConfig.get();
		feedback(context, "Einstellungen:");
		feedback(context, "  Klick-Verzögerung: " + config.clickDelay + " Ticks");
		feedback(context, "  Schutz für umbenannte/verzauberte Items: " + onOff(config.protectSpecialItems));
		feedback(context, "  Stopp bei Schaden: " + onOff(config.stopOnDamage));
		feedback(context, "  Limits: " + limitsText());
		feedback(context, "  Zuletzt: " + (config.lastItems.isEmpty() ? "-" : String.join(", ", config.lastItems)));
		return 1;
	}

	private static int help(CommandContext<FabricClientCommandSource> context) {
		String[] lines = {
				"/sellmacro <item> [item ...] - bis zu " + SellMacro.MAX_ITEMS + " Items verkaufen",
				"/sellmacro hand - Item in der Hand verkaufen",
				"/sellmacro stop - Makro beenden",
				"/sellmacro preset save|delete|list|<name> - Item-Listen speichern und starten",
				"/sellmacro delay <0-20> - Ticks zwischen zwei Klicks",
				"/sellmacro protect <true|false> - umbenannte/verzauberte Items nie verkaufen",
				"/sellmacro damagestop <true|false> - bei Schaden stoppen",
				"/sellmacro limit rounds|money <wert> - automatisch stoppen (0 = aus)",
				"/sellmacro earnings pattern|test - Geld-Erkennung im Chat anpassen",
				"/sellmacro settings - alle Einstellungen",
				"Taste K (änderbar in Steuerung): Makro starten/stoppen"
		};

		for (String line : lines) {
			context.getSource().sendFeedback(Component.literal(line).withStyle(ChatFormatting.GRAY));
		}

		return 1;
	}

	private static int start(CommandContext<FabricClientCommandSource> context, List<Item> items) {
		return start(items, context.getSource()::sendFeedback) ? 1 : 0;
	}

	private static boolean start(List<Item> items, java.util.function.Consumer<Component> output) {
		if (items.isEmpty()) {
			output.accept(SellMacro.prefixed(Component.literal("Keine gültigen Items.").withStyle(ChatFormatting.RED)));
			return false;
		}

		if (!SellMacro.start(items)) {
			output.accept(notAllowedMessage());
			return false;
		}

		output.accept(SellMacro.prefixed(Component.literal("Verkaufe ")
				.append(SellMacro.itemNames(items))
				.append(" bis du die /sell GUI schließt. ")
				.append(Component.literal("(/sellmacro stop)").withStyle(ChatFormatting.GRAY))));
		return true;
	}

	private static List<Item> resolve(List<String> ids) {
		List<Item> items = new ArrayList<>();

		for (String id : ids) {
			Item item = SellMacro.itemById(id);

			if (item != null) {
				items.add(item);
			}
		}

		return items;
	}

	private static String limitsText() {
		SellMacroConfig config = SellMacroConfig.get();
		String rounds = config.maxRounds > 0 ? config.maxRounds + " Runden" : "keine Runden-Grenze";
		String money = config.moneyLimit > 0 ? Earnings.format(config.moneyLimit) + " Geld" : "keine Geld-Grenze";
		return rounds + ", " + money;
	}

	private static String onOff(boolean value) {
		return value ? "an" : "aus";
	}

	private static int feedback(CommandContext<FabricClientCommandSource> context, String message) {
		context.getSource().sendFeedback(SellMacro.prefixed(Component.literal(message)));
		return 1;
	}

	private static int error(CommandContext<FabricClientCommandSource> context, String message) {
		context.getSource().sendError(SellMacro.prefixed(Component.literal(message)));
		return 0;
	}

	private static MutableComponent notAllowedMessage() {
		return SellMacro.prefixed(Component.literal("Du bist nicht berechtigt, diese Mod zu nutzen.").withStyle(ChatFormatting.RED));
	}

	private static int notAllowed(CommandContext<FabricClientCommandSource> context) {
		context.getSource().sendError(notAllowedMessage());
		return 0;
	}
}
