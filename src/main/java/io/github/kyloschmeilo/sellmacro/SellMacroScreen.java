package io.github.kyloschmeilo.sellmacro;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** Settings and presets in one screen, opened with /sellmacro gui or the hotkey. */
public final class SellMacroScreen extends Screen {
	private static final int WIDTH = 310;
	private static final int ROW = 22;
	private static final int MAX_PRESETS = 9;

	private EditBox itemsBox;
	private EditBox presetNameBox;
	private StringWidget statusWidget;
	private StringWidget messageWidget;
	private Button startButton;
	private Component message = Component.empty();

	public SellMacroScreen() {
		super(Component.literal("Sell Macro"));
	}

	@Override
	protected void init() {
		SellMacroConfig config = SellMacroConfig.get();
		int left = this.width / 2 - WIDTH / 2;
		int y = 8;

		this.addRenderableWidget(new StringWidget(left, y, WIDTH, 12, this.title.copy().withStyle(ChatFormatting.GOLD), this.font));
		y += 14;
		this.statusWidget = this.addRenderableWidget(new StringWidget(left, y, WIDTH, 12, SellMacro.statusText(), this.font));
		y += 16;

		// Items to sell and start/stop.
		String items = this.itemsBox != null ? this.itemsBox.getValue() : String.join(" ", shortIds(config.lastItems));
		this.itemsBox = this.addRenderableWidget(new EditBox(this.font, left, y, 228, 20, Component.literal("Items")));
		this.itemsBox.setMaxLength(512);
		this.itemsBox.setValue(items);
		this.itemsBox.setHint(Component.literal("Items, z. B. wheat carrot").withStyle(ChatFormatting.DARK_GRAY));
		this.addRenderableWidget(Button.builder(Component.literal("Hand"), button -> this.fillFromHand())
				.bounds(left + 232, y, 78, 20)
				.tooltip(Tooltip.create(Component.literal("Item in der Hand eintragen")))
				.build());
		y += ROW;

		this.startButton = this.addRenderableWidget(Button.builder(this.startLabel(), button -> this.toggleMacro())
				.bounds(left, y, 150, 20)
				.build());
		this.presetNameBox = this.addRenderableWidget(new EditBox(this.font, left + 160, y, 90, 20, Component.literal("Preset-Name")));
		this.presetNameBox.setMaxLength(32);
		this.presetNameBox.setHint(Component.literal("Preset-Name").withStyle(ChatFormatting.DARK_GRAY));
		this.addRenderableWidget(Button.builder(Component.literal("Speichern"), button -> this.savePreset())
				.bounds(left + 254, y, 56, 20)
				.tooltip(Tooltip.create(Component.literal("Die Items oben unter diesem Namen als Preset speichern")))
				.build());
		y += ROW + 4;

		// Presets: click the name to start, X to delete.
		List<String> names = new ArrayList<>(config.presets.keySet());
		int shown = Math.min(names.size(), MAX_PRESETS);

		if (shown == 0) {
			this.addRenderableWidget(new StringWidget(left, y + 4, WIDTH, 12,
					Component.literal("Noch keine Presets: Items eintragen, Namen eingeben, Speichern.").withStyle(ChatFormatting.GRAY), this.font));
			y += ROW;
		}

		for (int i = 0; i < shown; i++) {
			String name = names.get(i);
			int x = left + (i % 3) * 105;
			int rowY = y + (i / 3) * ROW;
			this.addRenderableWidget(Button.builder(Component.literal(name), button -> this.startPreset(name))
					.bounds(x, rowY, 78, 20)
					.tooltip(Tooltip.create(Component.literal("Starten: " + String.join(", ", shortIds(config.presets.get(name))))))
					.build());
			this.addRenderableWidget(Button.builder(Component.literal("X").withStyle(ChatFormatting.RED), button -> this.deletePreset(name))
					.bounds(x + 80, rowY, 20, 20)
					.tooltip(Tooltip.create(Component.literal("Preset löschen")))
					.build());
		}

		y += (shown + 2) / 3 * ROW + 4;

		// Settings.
		this.addRenderableWidget(CycleButton.onOffBuilder(config.useConfirmButton)
				.create(left, y, 150, 20, Component.literal("Grüner Haken"), (button, value) -> {
					config.useConfirmButton = value;
					config.save();
				})).setTooltip(Tooltip.create(Component.literal("Mit dem Bestätigen-Knopf verkaufen statt die GUI neu zu öffnen")));
		this.addRenderableWidget(CycleButton.onOffBuilder(config.protectSpecialItems)
				.create(left + 160, y, 150, 20, Component.literal("Item-Schutz"), (button, value) -> {
					config.protectSpecialItems = value;
					config.save();
				})).setTooltip(Tooltip.create(Component.literal("Umbenannte und verzauberte Items nie verkaufen")));
		y += ROW;

		this.addRenderableWidget(CycleButton.onOffBuilder(config.stopOnDamage)
				.create(left, y, 150, 20, Component.literal("Schaden-Stopp"), (button, value) -> {
					config.stopOnDamage = value;
					config.save();
				})).setTooltip(Tooltip.create(Component.literal("Bei Schaden sofort stoppen und die GUI schließen")));
		this.addRenderableWidget(CycleButton.onOffBuilder(config.autoResume)
				.create(left + 160, y, 150, 20, Component.literal("Auto-Fortsetzen"), (button, value) -> {
					config.autoResume = value;
					config.save();
				})).setTooltip(Tooltip.create(Component.literal("Nach Server-Neustart oder Transfer automatisch weitermachen")));
		y += ROW;

		List<Integer> delays = new ArrayList<>();

		for (int i = 0; i <= SellMacro.MAX_CLICK_DELAY; i++) {
			delays.add(i);
		}

		this.addRenderableWidget(CycleButton.<Integer>builder(value -> Component.literal(value + " Ticks"), config.clickDelay)
				.withValues(delays)
				.create(left, y, 150, 20, Component.literal("Klick-Pause"), (button, value) -> {
					config.clickDelay = value;
					config.save();
				})).setTooltip(Tooltip.create(Component.literal("Ticks zwischen zwei Shift-Klicks. Erhöhen, falls der Server kickt.")));

		EditBox roundsBox = this.addRenderableWidget(new EditBox(this.font, left + 160, y, 150, 20, Component.literal("Runden-Limit")));
		roundsBox.setValue(config.maxRounds > 0 ? String.valueOf(config.maxRounds) : "");
		roundsBox.setHint(Component.literal("Runden-Limit (leer = aus)").withStyle(ChatFormatting.DARK_GRAY));
		roundsBox.setResponder(value -> config.maxRounds = parseInt(value));
		y += ROW;

		EditBox moneyBox = this.addRenderableWidget(new EditBox(this.font, left, y, 150, 20, Component.literal("Geld-Limit")));
		moneyBox.setValue(config.moneyLimit > 0 ? Earnings.format(config.moneyLimit) : "");
		moneyBox.setHint(Component.literal("Geld-Limit (leer = aus)").withStyle(ChatFormatting.DARK_GRAY));
		moneyBox.setResponder(value -> config.moneyLimit = value.isBlank() ? 0 : Earnings.parseAmount(value));
		this.addRenderableWidget(Button.builder(Component.literal("Fertig"), button -> this.onClose())
				.bounds(left + 160, y, 150, 20)
				.build());
		y += ROW + 2;

		this.messageWidget = this.addRenderableWidget(new StringWidget(left, y, WIDTH, 12, this.message, this.font));
	}

	@Override
	public void tick() {
		super.tick();
		this.statusWidget.setMessage(SellMacro.statusText());
		this.startButton.setMessage(this.startLabel());
	}

	@Override
	public void removed() {
		SellMacroConfig.get().save();
		super.removed();
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	private Component startLabel() {
		return SellMacro.isRunning()
				? Component.literal("Stoppen").withStyle(ChatFormatting.RED)
				: Component.literal("Starten").withStyle(ChatFormatting.GREEN);
	}

	private void toggleMacro() {
		if (SellMacro.isRunning()) {
			SellMacro.stop(Component.literal("Makro gestoppt."));
			return;
		}

		List<Item> items = this.parseItems();

		if (items != null) {
			this.startAndClose(items);
		}
	}

	private void startPreset(String name) {
		List<String> ids = SellMacroConfig.get().presets.get(name);
		List<Item> items = ids == null ? List.of() : SellMacroClient.resolve(ids);

		if (items.isEmpty()) {
			this.showError("Preset \"" + name + "\" enthält keine gültigen Items.");
			return;
		}

		if (SellMacro.isRunning()) {
			SellMacro.stop(Component.literal("Wechsle zu Preset \"" + name + "\"."));
		}

		this.itemsBox.setValue(String.join(" ", shortIds(ids)));
		this.startAndClose(items);
	}

	private void startAndClose(List<Item> items) {
		LocalPlayer player = this.minecraft.player;

		if (player == null) {
			return;
		}

		// Close first: the macro waits until no screen is open before it sends /sell.
		this.onClose();
		SellMacroClient.start(items, player::sendSystemMessage);
	}

	private void savePreset() {
		String name = this.presetNameBox.getValue().trim().toLowerCase(Locale.ROOT);

		if (!name.matches("[a-z0-9_-]+")) {
			this.showError("Preset-Name: nur Buchstaben, Zahlen, - und _ (ohne Leerzeichen).");
			return;
		}

		List<Item> items = this.parseItems();

		if (items == null) {
			return;
		}

		SellMacroConfig config = SellMacroConfig.get();
		config.presets.put(name, items.stream().map(SellMacro::itemId).toList());
		config.save();
		this.presetNameBox.setValue("");
		this.message = Component.literal("Preset \"" + name + "\" gespeichert.").withStyle(ChatFormatting.GREEN);
		this.rebuildWidgets();
	}

	private void deletePreset(String name) {
		SellMacroConfig config = SellMacroConfig.get();
		config.presets.remove(name);
		config.save();
		this.message = Component.literal("Preset \"" + name + "\" gelöscht.").withStyle(ChatFormatting.GRAY);
		this.rebuildWidgets();
	}

	private void fillFromHand() {
		LocalPlayer player = this.minecraft.player;
		ItemStack held = player == null ? ItemStack.EMPTY : player.getMainHandItem();

		if (held.isEmpty()) {
			this.showError("Du hältst kein Item in der Hand.");
			return;
		}

		String id = shortId(SellMacro.itemId(held.getItem()));
		String current = this.itemsBox.getValue().trim();
		this.itemsBox.setValue(current.isEmpty() ? id : current + " " + id);
	}

	/** Items from the text field, or null (with an error shown) if one is unknown. */
	private List<Item> parseItems() {
		List<Item> items = new ArrayList<>();

		for (String token : this.itemsBox.getValue().trim().split("[\\s,]+")) {
			if (token.isEmpty()) {
				continue;
			}

			Item item = SellMacro.itemById(token.toLowerCase(Locale.ROOT));

			if (item == null) {
				this.showError("Unbekanntes Item: " + token);
				return null;
			}

			if (!items.contains(item)) {
				items.add(item);
			}
		}

		if (items.isEmpty()) {
			this.showError("Trag zuerst Items ein, z. B. wheat carrot.");
			return null;
		}

		if (items.size() > SellMacro.MAX_ITEMS) {
			this.showError("Höchstens " + SellMacro.MAX_ITEMS + " Items.");
			return null;
		}

		return items;
	}

	private void showError(String text) {
		this.message = Component.literal(text).withStyle(ChatFormatting.RED);
		this.messageWidget.setMessage(this.message);
	}

	private static int parseInt(String value) {
		try {
			return Math.max(Integer.parseInt(value.trim()), 0);
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	private static List<String> shortIds(List<String> ids) {
		return ids.stream().map(SellMacroScreen::shortId).toList();
	}

	private static String shortId(String id) {
		return id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
	}
}
