package io.github.kyloschmeilo.sellmacro;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

/**
 * Opens the auction house again and again and buys offers that match one of the player's rules:
 * name, highest price and the least remaining tool time ("Star-Tool läuft ab in").
 */
public final class AuctionSniper {
	private static final String AUCTION_COMMAND = "ah";
	/** One game tick: the mod can't click more often than once per tick. */
	public static final int MIN_REFRESH_MILLIS = 50;
	public static final int MAX_REFRESH_MILLIS = 60_000;
	private static final int OPEN_TIMEOUT_TICKS = 60;
	private static final int MAX_OPEN_ATTEMPTS = 3;
	/** Wait after the GUI opened so the server can send all offers first. */
	private static final int SYNC_DELAY_TICKS = 5;
	/** Wait after clicking the reload button, so the new offers have arrived before scanning. */
	private static final int REFRESH_SYNC_TICKS = 2;
	/** How long to wait for a confirm screen after clicking an offer. */
	private static final int CONFIRM_TIMEOUT_TICKS = 40;
	private static final int AFTER_BUY_TICKS = 20;

	private static final Pattern PRICE = Pattern.compile("(?i)preis\\s*:?\\s*\\$?\\s*([0-9][0-9.,]*\\s*[kmb]?)");
	private static final Pattern SELLER = Pattern.compile("(?i)verkäufer\\s*:?\\s*(\\S+)");
	private static final Pattern TOOL_TIME = Pattern.compile("(?i)star-?tool läuft ab in\\s*:?\\s*(.*)");
	private static final Pattern EXPIRES_HEADER = Pattern.compile("(?i)^\\s*läuft ab in\\s*:?\\s*$");
	private static final Pattern DURATION_PART = Pattern.compile("(\\d+)\\s*(d|h|min|m|s)(?![a-zäöü])", Pattern.CASE_INSENSITIVE);
	/** Name of the auction house's reload button (bottom left), if it has one. */
	private static final Pattern REFRESH_NAME = Pattern.compile("(?iu)aktualisier|refresh|neu laden|reload");
	private static final Pattern CONFIRM_NAME = Pattern.compile("(?iu)\\b(bestätigen|bestätigung|kaufen|confirm|buy|ja|annehmen|accept)\\b");

	private enum State {
		IDLE,
		SEND_COMMAND,
		WAIT_FOR_GUI,
		SCAN,
		WAIT_CONFIRM,
		AFTER_BUY,
		WAIT_REFRESH
	}

	/** One offer as read from its tooltip. toolSeconds is -1 when the item has no tool time. */
	record Listing(String name, double price, long toolSeconds, String seller) {
		String key() {
			return name + "|" + price + "|" + seller + "|" + toolSeconds / 600;
		}
	}

	private static State state = State.IDLE;
	private static int timer;
	private static int openAttempts;
	private static int containerId = -1;
	private static Listing pending;
	/** Slots that already showed a confirm-like button before an offer was clicked. */
	private static final Set<Integer> knownButtons = new HashSet<>();
	/** Offers already reported in test mode, so they are not repeated on every reload. */
	private static final Set<String> reported = new HashSet<>();
	private static double spent;
	private static int bought;
	private static boolean closingBySniper;
	/** Menu slot of the reload button in the auction house, -1 if none was found. */
	private static int refreshSlot = -1;

	private AuctionSniper() {
	}

	public static boolean isRunning() {
		return state != State.IDLE;
	}

	public static boolean start(LocalPlayer player) {
		SellMacroConfig config = SellMacroConfig.get();

		if (!AllowedPlayers.isAllowed()) {
			player.sendSystemMessage(AllowedPlayers.deniedMessage());
			return false;
		}

		if (config.auctionRules.isEmpty()) {
			player.sendSystemMessage(prefixed(Component.literal("Noch keine Regeln. Beispiel: /ahbuy add 95000000 2d star pickaxe")
					.withStyle(ChatFormatting.RED)));
			return false;
		}

		if (SellMacro.isRunning()) {
			SellMacro.stop(Component.literal("Gestoppt, der Auto-Kauf übernimmt."));
		}

		reset();
		state = State.SEND_COMMAND;
		player.sendSystemMessage(prefixed(Component.literal(config.auctionDryRun
				? "Auto-Kauf läuft im TESTMODUS: passende Angebote werden nur angezeigt, nicht gekauft."
				: "Auto-Kauf läuft und KAUFT passende Angebote.").withStyle(config.auctionDryRun ? ChatFormatting.YELLOW : ChatFormatting.GREEN)));
		return true;
	}

	public static void stop(Component reason) {
		LocalPlayer player = Minecraft.getInstance().player;

		if (player != null && state != State.IDLE) {
			MutableComponent summary = Component.empty().append(reason);

			if (bought > 0) {
				summary.append(" (" + bought + " gekauft für " + Earnings.format(spent) + ")");
			}

			player.sendSystemMessage(prefixed(summary));
		}

		reset();
	}

	private static void reset() {
		state = State.IDLE;
		timer = 0;
		openAttempts = 0;
		containerId = -1;
		refreshSlot = -1;
		pending = null;
		knownButtons.clear();
		reported.clear();
		spent = 0;
		bought = 0;
	}

	/** The player closed a GUI themselves (Esc, inventory key): that stops the auto-buy. */
	public static void onClientCloseContainer(int closedContainerId) {
		if (state == State.IDLE || closingBySniper || closedContainerId == 0) {
			return;
		}

		if (state != State.SEND_COMMAND) {
			stop(Component.literal("Auktionshaus geschlossen, Auto-Kauf beendet."));
		}
	}

	/** Server transfer or leaving the server: the auction house is gone, so stop. */
	public static void onWorldChange() {
		if (state != State.IDLE) {
			stop(Component.literal("Server gewechselt, Auto-Kauf beendet."));
		}
	}

	public static void tick(Minecraft client) {
		if (state == State.IDLE) {
			return;
		}

		LocalPlayer player = client.player;

		if (player == null || client.gameMode == null) {
			reset();
			return;
		}

		switch (state) {
			case SEND_COMMAND -> tickSendCommand(client, player);
			case WAIT_FOR_GUI -> tickWaitForGui(player);
			case SCAN -> tickScan(client, player);
			case WAIT_CONFIRM -> tickWaitConfirm(client, player);
			case AFTER_BUY, WAIT_REFRESH -> tickReload(player);
			default -> {
			}
		}
	}

	private static void tickSendCommand(Minecraft client, LocalPlayer player) {
		if (timer > 0) {
			timer--;
			return;
		}

		// Don't replace a screen the player opened; the pause menu of a window in the background is fine.
		Screen screen = client.gui.screen();

		if (screen != null && !(screen instanceof PauseScreen && !client.isWindowActive())) {
			return;
		}

		openAttempts++;
		player.connection.sendCommand(AUCTION_COMMAND);
		state = State.WAIT_FOR_GUI;
		timer = OPEN_TIMEOUT_TICKS;
	}

	private static void tickWaitForGui(LocalPlayer player) {
		AbstractContainerMenu menu = openMenu(player);

		if (menu != null) {
			containerId = menu.containerId;
			openAttempts = 0;
			state = State.SCAN;
			timer = SYNC_DELAY_TICKS;
		} else if (--timer <= 0) {
			if (openAttempts >= MAX_OPEN_ATTEMPTS) {
				stop(Component.literal("Das Auktionshaus (/" + AUCTION_COMMAND + ") hat sich nicht geöffnet.").withStyle(ChatFormatting.RED));
			} else {
				state = State.SEND_COMMAND;
				timer = 20;
			}
		}
	}

	private static void tickScan(Minecraft client, LocalPlayer player) {
		AbstractContainerMenu menu = trackedMenu(player);

		if (menu == null) {
			// Closed by the server: open it again.
			state = State.SEND_COMMAND;
			timer = refreshTicks();
			return;
		}

		if (timer > 0) {
			timer--;
			return;
		}

		SellMacroConfig config = SellMacroConfig.get();
		int offers = 0;
		int matches = 0;
		knownButtons.clear();

		for (Slot slot : menu.slots) {
			if (slot.container instanceof Inventory || !slot.hasItem()) {
				continue;
			}

			if (isConfirmButton(slot.getItem())) {
				knownButtons.add(slot.index);
			}

			Listing listing = parse(slot.getItem());

			if (listing == null) {
				continue;
			}

			offers++;
			SellMacroConfig.AuctionRule rule = matchingRule(listing, config.auctionRules);

			if (rule == null) {
				continue;
			}

			matches++;

			if (config.auctionBudget > 0 && spent + listing.price() > config.auctionBudget) {
				if (reported.add("budget|" + listing.key())) {
					player.sendSystemMessage(prefixed(Component.literal("Budget reicht nicht für ").append(describe(listing)).withStyle(ChatFormatting.GRAY)));
				}

				continue;
			}

			if (config.auctionDryRun) {
				if (reported.add(listing.key())) {
					player.sendSystemMessage(prefixed(Component.literal("TEST, würde kaufen: ").withStyle(ChatFormatting.YELLOW).append(describe(listing))));
				}

				continue;
			}

			// Buy: click the offer, then look for a confirm screen.
			pending = listing;
			client.gameMode.handleContainerInput(containerId, slot.index, 0, ContainerInput.PICKUP, player);
			state = State.WAIT_CONFIRM;
			timer = CONFIRM_TIMEOUT_TICKS;
			return;
		}

		refreshSlot = findRefreshSlot(menu);
		player.sendOverlayMessage(prefixed(Component.literal("AH: " + offers + " Angebote gelesen, " + matches + " passend"
				+ (bought > 0 ? ", " + bought + " gekauft" : ""))));
		state = State.WAIT_REFRESH;
		timer = refreshTicks();
	}

	private static void tickWaitConfirm(Minecraft client, LocalPlayer player) {
		AbstractContainerMenu menu = openMenu(player);

		if (menu != null && timer < CONFIRM_TIMEOUT_TICKS - 3) {
			boolean newScreen = menu.containerId != containerId;

			for (Slot slot : menu.slots) {
				if (slot.container instanceof Inventory || !slot.hasItem() || !isConfirmButton(slot.getItem())) {
					continue;
				}

				if (newScreen || !knownButtons.contains(slot.index)) {
					client.gameMode.handleContainerInput(menu.containerId, slot.index, 0, ContainerInput.PICKUP, player);
					containerId = menu.containerId;
					finishPurchase(player, "gekauft");
					return;
				}
			}
		}

		if (--timer <= 0) {
			// No confirm screen: this auction house buys on the first click (or the offer was gone).
			finishPurchase(player, "Kauf angeklickt (ohne Bestätigung)");
		}
	}

	private static void finishPurchase(LocalPlayer player, String what) {
		bought++;
		spent += pending.price();
		player.sendSystemMessage(prefixed(Component.literal(what + ": ").withStyle(ChatFormatting.GREEN).append(describe(pending))));
		pending = null;
		state = State.AFTER_BUY;
		timer = AFTER_BUY_TICKS;

		double budget = SellMacroConfig.get().auctionBudget;

		if (budget > 0 && spent >= budget) {
			stop(Component.literal("Budget von " + Earnings.format(budget) + " ausgegeben."));
		}
	}

	/** Closes the auction house after the wait and opens it again, so the offers are fresh. */
	private static void tickReload(LocalPlayer player) {
		if (timer > 0) {
			timer--;
			return;
		}

		AbstractContainerMenu menu = openMenu(player);
		// Only in the auction house that was scanned: after a purchase a confirm screen may be open instead.
		int button = state == State.WAIT_REFRESH && menu != null && menu.containerId == containerId ? refreshSlot : -1;

		if (button >= 0) {
			// Reload with the auction house's own button: the GUI stays open, no new /ah needed.
			containerId = menu.containerId;
			Minecraft.getInstance().gameMode.handleContainerInput(containerId, button, 0, ContainerInput.PICKUP, player);
			state = State.SCAN;
			timer = REFRESH_SYNC_TICKS;
			return;
		}

		if (menu != null) {
			closingBySniper = true;

			try {
				player.closeContainer();
			} finally {
				closingBySniper = false;
			}
		}

		containerId = -1;
		state = State.SEND_COMMAND;
		timer = 0;
	}

	private static int refreshTicks() {
		// The timer counts game ticks (50 ms each); at least one tick between two reloads.
		return Math.max(1, Math.round(SellMacroConfig.get().auctionRefreshMillis / 50f));
	}

	private static AbstractContainerMenu openMenu(LocalPlayer player) {
		AbstractContainerMenu menu = player.containerMenu;
		return menu != player.inventoryMenu && menu.containerId != 0 ? menu : null;
	}

	private static AbstractContainerMenu trackedMenu(LocalPlayer player) {
		AbstractContainerMenu menu = openMenu(player);
		return menu != null && menu.containerId == containerId ? menu : null;
	}

	/**
	 * The reload button: an item in the auction house's own slots named like "Aktualisieren", otherwise
	 * the item in the bottom left corner of the GUI, as long as it isn't an offer.
	 */
	private static int findRefreshSlot(AbstractContainerMenu menu) {
		int ownSlots = 0;

		for (Slot slot : menu.slots) {
			if (slot.container instanceof Inventory) {
				continue;
			}

			ownSlots++;

			if (slot.hasItem() && REFRESH_NAME.matcher(slot.getItem().getHoverName().getString()).find()) {
				return slot.index;
			}
		}

		int bottomLeft = ownSlots - 9;

		if (bottomLeft >= 0 && bottomLeft < menu.slots.size()) {
			Slot slot = menu.slots.get(bottomLeft);

			if (!(slot.container instanceof Inventory) && slot.hasItem() && parse(slot.getItem()) == null) {
				return slot.index;
			}
		}

		return -1;
	}

	static SellMacroConfig.AuctionRule matchingRule(Listing listing, List<SellMacroConfig.AuctionRule> rules) {
		String name = listing.name().toLowerCase(Locale.ROOT);

		for (SellMacroConfig.AuctionRule rule : rules) {
			if (!isValid(rule) || !name.contains(rule.name.toLowerCase(Locale.ROOT)) || listing.price() > rule.maxPrice) {
				continue;
			}

			if (rule.minToolSeconds > 0 && listing.toolSeconds() < rule.minToolSeconds) {
				continue;
			}

			return rule;
		}

		return null;
	}

	/** A rule that is still being typed (no name, no price, broken time) never buys anything. */
	public static boolean isValid(SellMacroConfig.AuctionRule rule) {
		return rule.name != null && !rule.name.isBlank() && rule.maxPrice > 0 && rule.minToolSeconds >= 0;
	}

	private static boolean isConfirmButton(ItemStack stack) {
		return CONFIRM_NAME.matcher(stack.getHoverName().getString()).find();
	}

	/** Reads price, seller and tool time from the offer's tooltip, null if it has no price. */
	static Listing parse(ItemStack stack) {
		ItemLore lore = stack.get(DataComponents.LORE);

		if (lore == null) {
			return null;
		}

		List<String> lines = new ArrayList<>();

		for (Component line : lore.lines()) {
			lines.add(line.getString());
		}

		return parse(stack.getHoverName().getString(), lines);
	}

	static Listing parse(String name, List<String> lines) {
		Double price = null;
		String seller = "";
		long toolSeconds = -1;
		long ownExpiry = -1;

		for (int i = 0; i < lines.size(); i++) {
			String line = lines.get(i);
			Matcher matcher;

			if (price == null && (matcher = PRICE.matcher(line)).find()) {
				price = Earnings.parseAmount(matcher.group(1));
			} else if ((matcher = SELLER.matcher(line)).find()) {
				seller = matcher.group(1);
			} else if ((matcher = TOOL_TIME.matcher(line)).find()) {
				toolSeconds = parseDuration(matcher.group(1));
			} else if (ownExpiry < 0 && EXPIRES_HEADER.matcher(line).find() && i + 1 < lines.size()) {
				// The item's own lore: "Läuft ab in:" with the time in the next line.
				ownExpiry = parseDuration(lines.get(i + 1));
			}
		}

		if (price == null) {
			return null;
		}

		return new Listing(name, price, toolSeconds >= 0 ? toolSeconds : ownExpiry, seller);
	}

	/** "2d 23h 58m" in seconds, -1 if there is no time in the text. */
	static long parseDuration(String text) {
		Matcher matcher = DURATION_PART.matcher(text);
		long seconds = 0;
		boolean found = false;

		while (matcher.find()) {
			long value = Long.parseLong(matcher.group(1));
			found = true;
			seconds += switch (matcher.group(2).toLowerCase(Locale.ROOT)) {
				case "d" -> value * 86_400;
				case "h" -> value * 3_600;
				case "m", "min" -> value * 60;
				default -> value;
			};
		}

		return found ? seconds : -1;
	}

	/** "2d 4h" style text for messages. */
	public static String formatDuration(long seconds) {
		if (seconds < 0) {
			return "?";
		}

		long days = seconds / 86_400;
		long hours = seconds % 86_400 / 3_600;
		long minutes = seconds % 3_600 / 60;
		return days > 0 ? days + "d " + hours + "h" : hours > 0 ? hours + "h " + minutes + "m" : minutes + "m";
	}

	private static MutableComponent describe(Listing listing) {
		MutableComponent text = Component.literal(listing.name() + " für " + Earnings.format(listing.price()));

		if (listing.toolSeconds() >= 0) {
			text.append(", Tool noch " + formatDuration(listing.toolSeconds()));
		}

		if (!listing.seller().isEmpty()) {
			text.append(" (von " + listing.seller() + ")");
		}

		return text;
	}

	/** "250 ms" below one second, "2 s" / "1,5 s" above. */
	public static String formatMillis(int millis) {
		return millis < 1000 ? millis + " ms" : Earnings.format(millis / 1000.0) + " s";
	}

	public static MutableComponent prefixed(Component message) {
		return Component.empty()
				.append(Component.literal("[AH] ").withStyle(ChatFormatting.AQUA))
				.append(message);
	}
}
