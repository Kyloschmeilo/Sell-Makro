package io.github.kyloschmeilo.sellmacro;

import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;

/** Rules for the auction house auto-buy: one row per case, plus test mode, budget and start/stop. */
public final class AuctionScreen extends Screen {
	private static final int WIDTH = 310;
	private static final int ROW = 22;
	private static final int MAX_RULES = 6;

	private final Screen parent;
	private StringWidget statusWidget;
	private StringWidget messageWidget;
	private Button startButton;

	public AuctionScreen(Screen parent) {
		super(Component.literal("Auto-Kauf im Auktionshaus"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		SellMacroConfig config = SellMacroConfig.get();
		List<SellMacroConfig.AuctionRule> rules = config.auctionRules;
		int left = this.width / 2 - WIDTH / 2;
		int y = 8;

		this.addRenderableWidget(new StringWidget(left, y, WIDTH, 12, this.title.copy().withStyle(ChatFormatting.AQUA), this.font));
		y += 14;
		this.statusWidget = this.addRenderableWidget(new StringWidget(left, y, WIDTH, 12, statusText(), this.font));
		y += 16;

		// Column headers.
		this.addRenderableWidget(new StringWidget(left, y, 128, 12, Component.literal("Name enthält").withStyle(ChatFormatting.GRAY), this.font));
		this.addRenderableWidget(new StringWidget(left + 132, y, 82, 12, Component.literal("Höchstpreis").withStyle(ChatFormatting.GRAY), this.font));
		this.addRenderableWidget(new StringWidget(left + 218, y, 66, 12, Component.literal("Restzeit mind.").withStyle(ChatFormatting.GRAY), this.font));
		y += 13;

		// One row per rule; every rule is its own case, the first that matches buys.
		for (int i = 0; i < rules.size() && i < MAX_RULES; i++) {
			SellMacroConfig.AuctionRule rule = rules.get(i);

			EditBox name = this.addRenderableWidget(new EditBox(this.font, left, y, 128, 20, Component.literal("Name")));
			name.setMaxLength(64);
			name.setValue(rule.name);
			name.setHint(Component.literal("z. B. star pickaxe").withStyle(ChatFormatting.DARK_GRAY));
			name.setResponder(value -> {
				rule.name = value.trim();
				this.updateMessage();
			});

			EditBox price = this.addRenderableWidget(new EditBox(this.font, left + 132, y, 82, 20, Component.literal("Höchstpreis")));
			price.setMaxLength(24);
			price.setValue(rule.maxPrice > 0 ? Earnings.format(rule.maxPrice) : "");
			price.setHint(Component.literal("95m").withStyle(ChatFormatting.DARK_GRAY));
			price.setResponder(value -> {
				rule.maxPrice = value.isBlank() ? 0 : Earnings.parseAmount(value);
				this.updateMessage();
			});

			EditBox time = this.addRenderableWidget(new EditBox(this.font, left + 218, y, 66, 20, Component.literal("Restzeit")));
			time.setMaxLength(16);
			time.setValue(rule.minToolSeconds > 0 ? AuctionSniper.formatDuration(rule.minToolSeconds) : "0");
			time.setHint(Component.literal("2d").withStyle(ChatFormatting.DARK_GRAY));
			time.setResponder(value -> {
				String text = value.trim();
				rule.minToolSeconds = text.isEmpty() || "0".equals(text) ? 0 : AuctionSniper.parseDuration(text);
				this.updateMessage();
			});

			this.addRenderableWidget(Button.builder(Component.literal("X").withStyle(ChatFormatting.RED), button -> {
						rules.remove(rule);
						config.save();
						this.rebuildWidgets();
					})
					.bounds(left + 290, y, 20, 20)
					.tooltip(Tooltip.create(Component.literal("Regel löschen")))
					.build());
			y += ROW;
		}

		if (rules.size() < MAX_RULES) {
			this.addRenderableWidget(Button.builder(Component.literal("+ Regel hinzufügen"), button -> {
						rules.add(new SellMacroConfig.AuctionRule());
						this.rebuildWidgets();
					})
					.bounds(left, y, 150, 20)
					.tooltip(Tooltip.create(Component.literal("Ein weiterer Fall, z. B. billiger kaufen, wenn das Tool weniger Restzeit hat")))
					.build());
			y += ROW;
		}

		y += 4;

		// Settings.
		this.addRenderableWidget(CycleButton.booleanBuilder(
						Component.literal("nur anzeigen").withStyle(ChatFormatting.YELLOW),
						Component.literal("KAUFT").withStyle(ChatFormatting.RED),
						config.auctionDryRun)
				.create(left, y, 150, 20, Component.literal("Testmodus"), (button, value) -> {
					config.auctionDryRun = value;
					config.save();
					this.statusWidget.setMessage(statusText());
				})).setTooltip(Tooltip.create(Component.literal("Im Testmodus werden passende Angebote nur im Chat angezeigt")));

		this.addRenderableWidget(CycleButton.<Integer>builder(value -> Component.literal(AuctionSniper.formatMillis(value)), config.auctionRefreshMillis)
				.withValues(refreshValues(config.auctionRefreshMillis))
				.create(left + 160, y, 150, 20, Component.literal("Aktualisieren"), (button, value) -> {
					config.auctionRefreshMillis = value;
					config.save();
				})).setTooltip(Tooltip.create(Component.literal("Zeit zwischen zwei Klicks auf den Aktualisieren-Knopf. 50 ms (1 Tick) ist das Schnellste, sehr kurze Zeiten können einen Kick geben.")));
		y += ROW;

		EditBox budget = this.addRenderableWidget(new EditBox(this.font, left, y, 150, 20, Component.literal("Budget")));
		budget.setMaxLength(24);
		budget.setValue(config.auctionBudget > 0 ? Earnings.format(config.auctionBudget) : "");
		budget.setHint(Component.literal("Budget pro Lauf (leer = kein Limit)").withStyle(ChatFormatting.DARK_GRAY));
		budget.setResponder(value -> config.auctionBudget = value.isBlank() ? 0 : Math.max(Earnings.parseAmount(value), 0));

		this.startButton = this.addRenderableWidget(Button.builder(this.startLabel(), button -> this.toggle())
				.bounds(left + 160, y, 150, 20)
				.build());
		y += ROW;

		this.addRenderableWidget(Button.builder(Component.literal("Fertig"), button -> this.onClose())
				.bounds(left + 80, y, 150, 20)
				.build());
		y += ROW + 2;

		this.messageWidget = this.addRenderableWidget(new StringWidget(left, y, WIDTH, 12, Component.empty(), this.font));
		this.updateMessage();
	}

	@Override
	public void tick() {
		super.tick();
		this.statusWidget.setMessage(statusText());
		this.startButton.setMessage(this.startLabel());
	}

	@Override
	public void removed() {
		SellMacroConfig.get().save();
		super.removed();
	}

	@Override
	public void onClose() {
		this.minecraft.gui.setScreen(this.parent);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	private void toggle() {
		if (AuctionSniper.isRunning()) {
			AuctionSniper.stop(Component.literal("Auto-Kauf gestoppt."));
			return;
		}

		LocalPlayer player = this.minecraft.player;

		if (player == null) {
			return;
		}

		SellMacroConfig.get().save();
		// Close first: the auto-buy waits until no screen is open before it sends /ah.
		this.minecraft.gui.setScreen(null);
		AuctionSniper.start(player);
	}

	/** Choices from one tick (50 ms) up to a minute, plus a value set by command. */
	private static List<Integer> refreshValues(int current) {
		java.util.ArrayList<Integer> values = new java.util.ArrayList<>(List.of(50, 100, 150, 200, 250, 500, 750, 1000, 2000, 3000, 5000, 10000, 30000, 60000));

		if (!values.contains(current)) {
			values.add(current);
			values.sort(null);
		}

		return values;
	}

	private Component startLabel() {
		return AuctionSniper.isRunning()
				? Component.literal("Stoppen").withStyle(ChatFormatting.RED)
				: Component.literal("Starten").withStyle(ChatFormatting.GREEN);
	}

	private static Component statusText() {
		SellMacroConfig config = SellMacroConfig.get();

		if (AuctionSniper.isRunning()) {
			return Component.literal(config.auctionDryRun ? "Läuft im Testmodus" : "Läuft und KAUFT").withStyle(ChatFormatting.GREEN);
		}

		return Component.literal("Gestoppt" + (config.auctionDryRun ? " (Testmodus an)" : "")).withStyle(ChatFormatting.GRAY);
	}

	/** Points out rows that won't buy anything yet, e.g. a price or time that can't be read. */
	private void updateMessage() {
		if (this.messageWidget == null) {
			return;
		}

		List<SellMacroConfig.AuctionRule> rules = SellMacroConfig.get().auctionRules;

		for (int i = 0; i < rules.size(); i++) {
			SellMacroConfig.AuctionRule rule = rules.get(i);

			if (!AuctionSniper.isValid(rule)) {
				String problem = rule.name == null || rule.name.isBlank() ? "Name fehlt"
						: rule.maxPrice <= 0 ? "Höchstpreis fehlt oder ungültig (z. B. 95m oder 95.000.000)"
						: "Restzeit ungültig (z. B. 2d, 36h oder 0)";
				this.messageWidget.setMessage(Component.literal("Regel " + (i + 1) + ": " + problem + " - wird ignoriert.").withStyle(ChatFormatting.RED));
				return;
			}
		}

		this.messageWidget.setMessage(rules.isEmpty()
				? Component.literal("Noch keine Regel. Mit \"+ Regel hinzufügen\" anlegen.").withStyle(ChatFormatting.GRAY)
				: Component.literal("Gekauft wird, wenn eine Regel passt.").withStyle(ChatFormatting.GRAY));
	}
}
