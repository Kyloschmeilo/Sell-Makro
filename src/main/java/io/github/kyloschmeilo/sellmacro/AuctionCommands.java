package io.github.kyloschmeilo.sellmacro;

import java.util.List;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;

import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

/** /ahbuy: rules, test mode, budget and start/stop for the auction house auto-buy. */
final class AuctionCommands {
	private AuctionCommands() {
	}

	static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
		dispatcher.register(ClientCommands.literal("ahbuy")
				.executes(AuctionCommands::help)
				.then(ClientCommands.literal("help").executes(AuctionCommands::help))
				.then(ClientCommands.literal("list").executes(AuctionCommands::list))
				.then(ClientCommands.literal("gui").executes(context -> {
					SellMacroClient.openLater(() -> new AuctionScreen(null));
					return 1;
				}))
				.then(ClientCommands.literal("start").executes(context ->
						AuctionSniper.start(context.getSource().getPlayer()) ? 1 : 0))
				.then(ClientCommands.literal("stop").executes(context -> {
					if (!AuctionSniper.isRunning()) {
						return error(context, "Der Auto-Kauf läuft gerade nicht.");
					}

					AuctionSniper.stop(Component.literal("Auto-Kauf gestoppt."));
					return 1;
				}))
				.then(ClientCommands.literal("add")
						.then(ClientCommands.argument("maxPrice", StringArgumentType.word())
								.then(ClientCommands.argument("minToolTime", StringArgumentType.word())
										.then(ClientCommands.argument("name", StringArgumentType.greedyString())
												.executes(AuctionCommands::add)))))
				.then(ClientCommands.literal("remove")
						.then(ClientCommands.argument("number", IntegerArgumentType.integer(1))
								.executes(context -> {
									List<SellMacroConfig.AuctionRule> rules = SellMacroConfig.get().auctionRules;
									int number = IntegerArgumentType.getInteger(context, "number");

									if (number > rules.size()) {
										return error(context, "Regel " + number + " gibt es nicht. (/ahbuy list)");
									}

									SellMacroConfig.AuctionRule rule = rules.remove(number - 1);
									SellMacroConfig.get().save();
									return feedback(context, "Regel entfernt: " + describe(rule));
								})))
				.then(ClientCommands.literal("test")
						.executes(context -> feedback(context, "Testmodus (nur anzeigen, nicht kaufen): " + onOff(SellMacroConfig.get().auctionDryRun)))
						.then(ClientCommands.argument("enabled", BoolArgumentType.bool())
								.executes(context -> {
									if (AuctionSniper.isRunning()) {
										return error(context, "Stoppe zuerst den Auto-Kauf (/ahbuy stop).");
									}

									SellMacroConfig config = SellMacroConfig.get();
									config.auctionDryRun = BoolArgumentType.getBool(context, "enabled");
									config.save();
									return feedback(context, config.auctionDryRun
											? "Testmodus an: passende Angebote werden nur angezeigt."
											: "Testmodus aus: passende Angebote werden ab dem nächsten Start GEKAUFT.");
								})))
				.then(ClientCommands.literal("budget")
						.executes(context -> feedback(context, "Budget pro Lauf: " + budgetText()))
						.then(ClientCommands.argument("amount", StringArgumentType.word())
								.executes(context -> {
									double amount = Earnings.parseAmount(StringArgumentType.getString(context, "amount"));
									SellMacroConfig config = SellMacroConfig.get();
									config.auctionBudget = Math.max(amount, 0);
									config.save();
									return feedback(context, "Budget pro Lauf: " + budgetText());
								})))
				.then(ClientCommands.literal("refresh")
						.executes(context -> feedback(context, "Auktionshaus aktualisieren alle " + AuctionSniper.formatMillis(SellMacroConfig.get().auctionRefreshMillis)))
						.then(ClientCommands.argument("millis", IntegerArgumentType.integer(AuctionSniper.MIN_REFRESH_MILLIS, AuctionSniper.MAX_REFRESH_MILLIS))
								.executes(context -> {
									SellMacroConfig config = SellMacroConfig.get();
									config.auctionRefreshMillis = IntegerArgumentType.getInteger(context, "millis");
									config.save();
									return feedback(context, "Auktionshaus aktualisieren alle " + AuctionSniper.formatMillis(config.auctionRefreshMillis));
								}))));
	}

	private static int add(CommandContext<FabricClientCommandSource> context) {
		String priceText = StringArgumentType.getString(context, "maxPrice");
		String timeText = StringArgumentType.getString(context, "minToolTime");
		String name = StringArgumentType.getString(context, "name").trim();
		double maxPrice = Earnings.parseAmount(priceText);
		long minToolSeconds = "0".equals(timeText) ? 0 : AuctionSniper.parseDuration(timeText);

		if (maxPrice <= 0) {
			return error(context, "Ungültiger Höchstpreis: " + priceText + " (z. B. 95000000, 95.000.000 oder 95m)");
		}

		if (minToolSeconds < 0) {
			return error(context, "Ungültige Mindestzeit: " + timeText + " (z. B. 2d, 36h, 2d12h oder 0)");
		}

		if (name.isEmpty()) {
			return error(context, "Gib einen Namen an, z. B. star pickaxe");
		}

		SellMacroConfig.AuctionRule rule = new SellMacroConfig.AuctionRule();
		rule.name = name;
		rule.maxPrice = maxPrice;
		rule.minToolSeconds = minToolSeconds;
		SellMacroConfig config = SellMacroConfig.get();
		config.auctionRules.add(rule);
		config.save();
		return feedback(context, "Regel " + config.auctionRules.size() + " hinzugefügt: " + describe(rule));
	}

	private static int list(CommandContext<FabricClientCommandSource> context) {
		SellMacroConfig config = SellMacroConfig.get();

		if (config.auctionRules.isEmpty()) {
			return feedback(context, "Keine Regeln. Beispiel: /ahbuy add 95000000 2d star pickaxe");
		}

		for (int i = 0; i < config.auctionRules.size(); i++) {
			feedback(context, (i + 1) + ". " + describe(config.auctionRules.get(i)));
		}

		feedback(context, "Testmodus: " + onOff(config.auctionDryRun) + ", Budget: " + budgetText()
				+ ", aktualisieren alle " + AuctionSniper.formatMillis(config.auctionRefreshMillis));
		return config.auctionRules.size();
	}

	private static int help(CommandContext<FabricClientCommandSource> context) {
		String[] lines = {
				"/ahbuy gui - Regeln und Einstellungen in einer GUI",
				"/ahbuy add <höchstpreis> <mindestzeit> <name> - Regel, z. B. /ahbuy add 95m 2d star pickaxe",
				"   Mindestzeit = Star-Tool-Restzeit (z. B. 2d, 36h, 0 = egal)",
				"/ahbuy list - Regeln anzeigen, /ahbuy remove <nr> - Regel löschen",
				"/ahbuy start | stop - Auto-Kauf starten/stoppen (oder Auktionshaus schließen)",
				"/ahbuy test <true|false> - Testmodus: nur anzeigen statt kaufen (Standard an)",
				"/ahbuy budget <betrag> - höchstens so viel pro Lauf ausgeben (0 = kein Limit)",
				"/ahbuy refresh <50-60000> - Millisekunden zwischen zwei Aktualisierungen (50 = schnellstes, 1 Tick)"
		};

		for (String line : lines) {
			context.getSource().sendFeedback(Component.literal(line).withStyle(ChatFormatting.GRAY));
		}

		return 1;
	}

	private static String describe(SellMacroConfig.AuctionRule rule) {
		return "\"" + rule.name + "\" bis " + Earnings.format(rule.maxPrice)
				+ (rule.minToolSeconds > 0 ? ", Tool mind. " + AuctionSniper.formatDuration(rule.minToolSeconds) : "");
	}

	private static String budgetText() {
		double budget = SellMacroConfig.get().auctionBudget;
		return budget > 0 ? Earnings.format(budget) : "kein Limit";
	}

	private static String onOff(boolean value) {
		return value ? "an" : "aus";
	}

	private static int feedback(CommandContext<FabricClientCommandSource> context, String message) {
		context.getSource().sendFeedback(AuctionSniper.prefixed(Component.literal(message)));
		return 1;
	}

	private static int error(CommandContext<FabricClientCommandSource> context, String message) {
		context.getSource().sendError(AuctionSniper.prefixed(Component.literal(message)));
		return 0;
	}
}
