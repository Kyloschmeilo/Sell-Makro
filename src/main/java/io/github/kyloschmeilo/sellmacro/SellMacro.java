package io.github.kyloschmeilo.sellmacro;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Opens the server's /sell GUI, shift-clicks every stack of the selected items into it,
 * closes the GUI (which sells the items) and opens it again. This repeats until the
 * player closes the /sell GUI themselves or an auto-stop limit is reached.
 */
public final class SellMacro {
	/** Server command that opens the sell GUI, without the leading slash. */
	private static final String SELL_COMMAND = "sell";
	/** How long to wait for the sell GUI to open after sending the command. */
	private static final int OPEN_TIMEOUT_TICKS = 60;
	/** How often the command is sent in a row without the GUI opening before giving up. */
	private static final int MAX_OPEN_ATTEMPTS = 3;
	/** Wait after the GUI opened so the server can send its contents first. */
	private static final int SYNC_DELAY_TICKS = 2;
	/** Wait after the last shift-click so all clicks reach the server before closing. */
	private static final int CLOSE_DELAY_TICKS = 2;
	/** Wait after closing the GUI before sending the sell command again. */
	private static final int REOPEN_DELAY_TICKS = 10;
	/** Wait after clicking the confirm button so the server can sell and empty the GUI. */
	private static final int CONFIRM_DELAY_TICKS = 10;
	/** Names that mark the confirm button of a sell GUI. */
	private static final Pattern CONFIRM_NAME = Pattern.compile("(?i)verkauf|bestätig|confirm|sell|annehm|accept|fertig|✔|✓");
	private static final int CONFIRM_UNKNOWN = -2;
	private static final int NO_CONFIRM = -1;

	public static final int MAX_CLICK_DELAY = 20;
	public static final int MAX_ITEMS = 9;

	/** How long the macro waits for the player to come back after a disconnect or server transfer. */
	private static final long RESUME_TIMEOUT_MILLIS = 10 * 60 * 1000;
	/** Wait after rejoining before sending /sell again, so the server can finish moving the player. */
	private static final int RESUME_DELAY_TICKS = 100;
	/** After rejoining, keep trying to open the sell GUI for this long (the server may still be starting). */
	private static final long RESUME_RETRY_MILLIS = 2 * 60 * 1000;
	private static final int RESUME_RETRY_DELAY_TICKS = 100;

	private enum State {
		IDLE,
		/** Lost the server (restart, transfer): waits for the player to be back in a world. */
		RESUME_WAIT,
		SEND_COMMAND,
		WAIT_FOR_GUI,
		FILL,
		CLOSE
	}

	private static State state = State.IDLE;
	private static final Set<Item> items = new LinkedHashSet<>();
	private static int timer;
	private static int openAttempts;
	private static int containerId = -1;
	/** Menu slots already shift-clicked this round, so slots the server refuses are not clicked forever. */
	private static final Set<Integer> attemptedSlots = new HashSet<>();
	private static int movedThisRound;
	private static int rounds;
	private static long totalMoved;
	private static double earned;
	private static long startedAt;
	private static float lastHealth;
	private static boolean closingByMacro;
	private static long resumeDeadline;
	private static int resumeTicks;
	/** Until then a GUI that doesn't open is retried instead of stopping the macro. */
	private static long retryUntil;
	/** Menu slot of the green confirm button in the current GUI, or NO_CONFIRM / CONFIRM_UNKNOWN. */
	private static int confirmSlot = CONFIRM_UNKNOWN;

	private SellMacro() {
	}

	public static boolean isRunning() {
		return state != State.IDLE;
	}

	/** Starts the macro, returns false if the player may not use the mod or no item was given. */
	public static boolean start(List<Item> newItems) {
		reset();

		if (!AllowedPlayers.isAllowed() || newItems.isEmpty()) {
			return false;
		}

		items.addAll(newItems);
		items.remove(Items.AIR);

		if (items.isEmpty()) {
			return false;
		}

		LocalPlayer player = Minecraft.getInstance().player;
		lastHealth = player != null ? player.getHealth() : 0;
		startedAt = System.currentTimeMillis();
		state = State.SEND_COMMAND;

		SellMacroConfig config = SellMacroConfig.get();
		config.lastItems = items.stream().map(SellMacro::itemId).toList();
		config.save();
		return true;
	}

	public static void stop(Component reason) {
		LocalPlayer player = Minecraft.getInstance().player;

		if (player != null && state != State.IDLE) {
			MutableComponent summary = Component.empty()
					.append(reason)
					.append(" (" + rounds + " Runden, " + totalMoved + " Items");

			if (earned > 0) {
				summary.append(", " + Earnings.format(earned) + " verdient, Schnitt "
						+ Earnings.format(Earnings.perHour(earned, System.currentTimeMillis() - startedAt)) + "/h");
			}

			player.sendSystemMessage(prefixed(summary.append(")")));
		}

		reset();
	}

	private static void reset() {
		state = State.IDLE;
		retryUntil = 0;
		items.clear();
		timer = 0;
		openAttempts = 0;
		containerId = -1;
		attemptedSlots.clear();
		movedThisRound = 0;
		rounds = 0;
		totalMoved = 0;
		earned = 0;
	}

	/**
	 * Called from {@code LocalPlayer#closeContainer}, which runs for every close the client starts
	 * (Esc, inventory key, death, ...). Closes sent by the server do not go through it.
	 */
	public static void onClientCloseContainer(int closedContainerId) {
		if (closingByMacro || closedContainerId == 0) {
			return;
		}

		boolean sellGuiClosed = switch (state) {
			case WAIT_FOR_GUI -> true;
			case FILL, CLOSE -> closedContainerId == containerId;
			default -> false;
		};

		if (sellGuiClosed) {
			stop(Component.literal("GUI geschlossen, Makro beendet."));
		}
	}

	/** Counts money amounts in server messages while the macro runs. */
	public static void onGameMessage(Component message, boolean overlay) {
		if (state == State.IDLE || overlay) {
			return;
		}

		double amount = Earnings.parse(message.getString());

		if (amount <= 0) {
			return;
		}

		earned += amount;
		double limit = SellMacroConfig.get().moneyLimit;

		if (limit > 0 && earned >= limit) {
			stop(Component.literal("Geld-Limit von " + Earnings.format(limit) + " erreicht."));
		}
	}

	public static void tick(Minecraft client) {
		if (state == State.IDLE) {
			return;
		}

		LocalPlayer player = client.player;

		if (state == State.RESUME_WAIT) {
			tickResume(client, player);
			return;
		}

		if (player == null || client.gameMode == null) {
			// Disconnected, e.g. kicked during a server restart or transferred to another server.
			if (SellMacroConfig.get().autoResume) {
				suspend();
			} else {
				reset();
			}

			return;
		}

		float health = player.getHealth();

		if (health < lastHealth && SellMacroConfig.get().stopOnDamage) {
			// Get the GUI out of the way so the player can react.
			if (trackedScreen(client, player) != null) {
				closingByMacro = true;

				try {
					player.closeContainer();
				} finally {
					closingByMacro = false;
				}
			}

			stop(Component.literal("Schaden erhalten, Makro beendet.").withStyle(ChatFormatting.RED));
			return;
		}

		lastHealth = health;

		switch (state) {
			case SEND_COMMAND -> tickSendCommand(client, player);
			case WAIT_FOR_GUI -> tickWaitForGui(client, player);
			case FILL -> tickFill(client, player);
			case CLOSE -> tickClose(client, player);
			default -> {
			}
		}
	}

	/** Keeps items and stats, and waits for the player to be back on a server. */
	private static void suspend() {
		state = State.RESUME_WAIT;
		resumeDeadline = System.currentTimeMillis() + RESUME_TIMEOUT_MILLIS;
		resumeTicks = 0;
		containerId = -1;
		confirmSlot = CONFIRM_UNKNOWN;
		attemptedSlots.clear();
		movedThisRound = 0;
	}

	private static void tickResume(Minecraft client, LocalPlayer player) {
		if (System.currentTimeMillis() > resumeDeadline) {
			stop(Component.literal("Nicht rechtzeitig wieder verbunden, Makro beendet.").withStyle(ChatFormatting.RED));
			return;
		}

		if (player == null || client.gameMode == null || client.level == null) {
			resumeTicks = 0;
			return;
		}

		if (++resumeTicks < RESUME_DELAY_TICKS) {
			return;
		}

		lastHealth = player.getHealth();
		retryUntil = System.currentTimeMillis() + RESUME_RETRY_MILLIS;
		openAttempts = 0;
		timer = 0;
		state = State.SEND_COMMAND;
		player.sendSystemMessage(prefixed(Component.literal("Wieder verbunden, Makro läuft weiter.").withStyle(ChatFormatting.GREEN)));
	}

	/**
	 * Called when the server moves the player to another world or server (respawn packet). The
	 * sell GUI is gone afterwards, so the macro pauses and starts again once the player has arrived.
	 */
	public static void onWorldChange() {
		if (state != State.IDLE && state != State.RESUME_WAIT && SellMacroConfig.get().autoResume) {
			suspend();
		}
	}

	/** The player left the server on purpose (pause menu): don't resume on the next join. */
	public static void onManualDisconnect() {
		if (state != State.IDLE) {
			reset();
		}
	}

	/** Short status for the settings screen. */
	public static MutableComponent statusText() {
		return switch (state) {
			case IDLE -> Component.literal("Gestoppt").withStyle(ChatFormatting.GRAY);
			case RESUME_WAIT -> Component.literal("Wartet auf Verbindung zum Server ...").withStyle(ChatFormatting.YELLOW);
			default -> {
				MutableComponent text = Component.literal("Läuft: ").withStyle(ChatFormatting.GREEN)
						.append(itemNames(items))
						.append(" | Runde " + rounds);

				if (earned > 0) {
					text.append(" | " + Earnings.format(earned) + " verdient");
				}

				yield text;
			}
		};
	}

	private static void tickSendCommand(Minecraft client, LocalPlayer player) {
		if (timer > 0) {
			timer--;
			return;
		}

		// Don't replace a screen the player opened in the meantime, e.g. the chat to type /sellmacro stop.
		if (client.gui.screen() != null) {
			return;
		}

		openAttempts++;
		player.connection.sendCommand(SELL_COMMAND);
		state = State.WAIT_FOR_GUI;
		timer = OPEN_TIMEOUT_TICKS;
	}

	private static void tickWaitForGui(Minecraft client, LocalPlayer player) {
		AbstractContainerScreen<?> screen = openContainerScreen(client, player);

		if (screen != null) {
			containerId = screen.getMenu().containerId;
			confirmSlot = CONFIRM_UNKNOWN;
			state = State.FILL;
			timer = SYNC_DELAY_TICKS;
			openAttempts = 0;
			retryUntil = 0;
			movedThisRound = 0;
			attemptedSlots.clear();
		} else if (--timer <= 0) {
			boolean retrying = System.currentTimeMillis() < retryUntil;

			if (openAttempts >= MAX_OPEN_ATTEMPTS && !retrying) {
				stop(Component.literal("Die /" + SELL_COMMAND + " GUI hat sich nicht geöffnet.").withStyle(ChatFormatting.RED));
			} else {
				state = State.SEND_COMMAND;
				timer = retrying ? RESUME_RETRY_DELAY_TICKS : REOPEN_DELAY_TICKS;
			}
		}
	}

	private static void tickFill(Minecraft client, LocalPlayer player) {
		AbstractContainerScreen<?> screen = trackedScreen(client, player);

		if (screen == null) {
			// Closed without LocalPlayer#closeContainer, so the server closed it: just open it again.
			finishRound(player);
			return;
		}

		if (timer > 0) {
			timer--;
			return;
		}

		if (confirmSlot == CONFIRM_UNKNOWN) {
			// The GUI contents arrived during the sync delay, so the button can be looked for now.
			confirmSlot = SellMacroConfig.get().useConfirmButton ? findConfirmSlot(screen.getMenu()) : NO_CONFIRM;
		}

		int clickDelay = SellMacroConfig.get().clickDelay;
		Slot slot;

		while ((slot = nextSlot(screen.getMenu(), player)) != null) {
			attemptedSlots.add(slot.index);
			movedThisRound += slot.getItem().getCount();
			client.gameMode.handleContainerInput(containerId, slot.index, 0, ContainerInput.QUICK_MOVE, player);

			if (clickDelay > 0) {
				timer = clickDelay - 1;
				return;
			}
		}

		if (movedThisRound > 0) {
			state = State.CLOSE;
			timer = CLOSE_DELAY_TICKS;
		}

		// Otherwise there is nothing to sell yet: keep the GUI open until new items show up.
	}

	private static void tickClose(Minecraft client, LocalPlayer player) {
		if (trackedScreen(client, player) != null) {
			if (timer > 0) {
				timer--;
				return;
			}

			if (confirmSlot >= 0) {
				// Selling via the confirm button keeps the GUI open: no new /sell command needed.
				client.gameMode.handleContainerInput(containerId, confirmSlot, 0, ContainerInput.PICKUP, player);
				countRound(player);
				attemptedSlots.clear();
				state = State.FILL;
				timer = CONFIRM_DELAY_TICKS;
				checkRoundLimit();
				return;
			}

			closingByMacro = true;

			try {
				player.closeContainer();
			} finally {
				closingByMacro = false;
			}
		}

		finishRound(player);
	}

	/**
	 * The confirm button is an item in the GUI's own slots that isn't one of the items being sold.
	 * With several candidates (e.g. glass pane decoration) the one named like a confirm button wins,
	 * otherwise the last one, since confirm buttons usually sit in the bottom right corner.
	 */
	private static int findConfirmSlot(AbstractContainerMenu menu) {
		Slot named = null;
		Slot last = null;

		for (Slot slot : menu.slots) {
			if (slot.container instanceof Inventory || !slot.hasItem() || items.contains(slot.getItem().getItem())) {
				continue;
			}

			last = slot;

			if (named == null && CONFIRM_NAME.matcher(slot.getItem().getHoverName().getString()).find()) {
				named = slot;
			}
		}

		return named != null ? named.index : last != null ? last.index : NO_CONFIRM;
	}

	private static void countRound(LocalPlayer player) {
		if (movedThisRound > 0) {
			rounds++;
			totalMoved += movedThisRound;
			player.sendOverlayMessage(prefixed(Component.literal(roundStatus())));
		}

		movedThisRound = 0;
	}

	private static void checkRoundLimit() {
		int maxRounds = SellMacroConfig.get().maxRounds;

		if (maxRounds > 0 && rounds >= maxRounds) {
			stop(Component.literal("Runden-Limit von " + maxRounds + " erreicht."));
		}
	}

	private static void finishRound(LocalPlayer player) {
		countRound(player);
		attemptedSlots.clear();
		containerId = -1;
		state = State.SEND_COMMAND;
		timer = REOPEN_DELAY_TICKS;
		checkRoundLimit();
	}

	private static String roundStatus() {
		StringBuilder status = new StringBuilder("Runde " + rounds + ": " + movedThisRound + " Items (gesamt " + totalMoved + ")");

		if (earned > 0) {
			double perHour = Earnings.perHour(earned, System.currentTimeMillis() - startedAt);
			status.append(" | ").append(Earnings.format(earned)).append(" verdient, ")
					.append(Earnings.format(perHour / 60)).append("/min = ")
					.append(Earnings.format(perHour)).append("/h");
		}

		return status.toString();
	}

	/** Any open container GUI that isn't the player's own inventory. */
	private static AbstractContainerScreen<?> openContainerScreen(Minecraft client, LocalPlayer player) {
		if (client.gui.screen() instanceof AbstractContainerScreen<?> screen
				&& !(screen instanceof CreativeModeInventoryScreen)
				&& screen.getMenu() == player.containerMenu
				&& player.containerMenu != player.inventoryMenu
				&& player.containerMenu.containerId != 0) {
			return screen;
		}

		return null;
	}

	/** The sell GUI that was opened this round, if it is still open. */
	private static AbstractContainerScreen<?> trackedScreen(Minecraft client, LocalPlayer player) {
		AbstractContainerScreen<?> screen = openContainerScreen(client, player);
		return screen != null && screen.getMenu().containerId == containerId ? screen : null;
	}

	/** Next player inventory slot holding a selected item that wasn't clicked this round. */
	private static Slot nextSlot(AbstractContainerMenu menu, LocalPlayer player) {
		boolean protect = SellMacroConfig.get().protectSpecialItems;

		for (Slot slot : menu.slots) {
			if (slot.container instanceof Inventory
					&& !attemptedSlots.contains(slot.index)
					&& slot.hasItem()
					&& items.contains(slot.getItem().getItem())
					&& !(protect && isProtected(slot.getItem()))
					&& slot.mayPickup(player)) {
				return slot;
			}
		}

		return null;
	}

	/** Renamed or enchanted stacks are usually valuable and never get sold. */
	public static boolean isProtected(ItemStack stack) {
		return stack.has(DataComponents.CUSTOM_NAME) || stack.isEnchanted();
	}

	public static String itemId(Item item) {
		return BuiltInRegistries.ITEM.getKey(item).toString();
	}

	/** Resolves an item id like "minecraft:wheat", null if unknown. */
	public static Item itemById(String id) {
		Identifier identifier = Identifier.tryParse(id);

		if (identifier == null) {
			return null;
		}

		Item item = BuiltInRegistries.ITEM.getValue(identifier);
		return item == Items.AIR ? null : item;
	}

	public static Component itemName(Item item) {
		return new ItemStack(item).getItemName();
	}

	public static MutableComponent itemNames(Iterable<Item> list) {
		MutableComponent names = Component.empty();
		boolean first = true;

		for (Item item : list) {
			if (!first) {
				names.append(", ");
			}

			names.append(itemName(item));
			first = false;
		}

		return names;
	}

	public static MutableComponent prefixed(Component message) {
		return Component.empty()
				.append(Component.literal("[SellMacro] ").withStyle(ChatFormatting.GOLD))
				.append(message);
	}
}
