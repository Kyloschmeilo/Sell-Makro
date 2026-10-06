package io.github.kyloschmeilo.sellmacro;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
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

	public static final int MAX_CLICK_DELAY = 20;
	public static final int MAX_ITEMS = 9;

	private enum State {
		IDLE,
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
				summary.append(", " + Earnings.format(earned) + " verdient");
			}

			player.sendSystemMessage(prefixed(summary.append(")")));
		}

		reset();
	}

	private static void reset() {
		state = State.IDLE;
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

		if (player == null || client.gameMode == null) {
			reset();
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

	private static void tickSendCommand(Minecraft client, LocalPlayer player) {
		if (timer > 0) {
			timer--;
			return;
		}

		// Don't replace a screen the player opened in the meantime, e.g. the chat to type /sellmacro stop.
		// A pause menu that only opened because the window is in the background (alt-tab) is fine.
		Screen screen = client.gui.screen();

		if (screen != null && !(screen instanceof PauseScreen && !client.isWindowActive())) {
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
			state = State.FILL;
			timer = SYNC_DELAY_TICKS;
			openAttempts = 0;
			movedThisRound = 0;
			attemptedSlots.clear();
		} else if (--timer <= 0) {
			if (openAttempts >= MAX_OPEN_ATTEMPTS) {
				stop(Component.literal("Die /" + SELL_COMMAND + " GUI hat sich nicht geöffnet.").withStyle(ChatFormatting.RED));
			} else {
				state = State.SEND_COMMAND;
				timer = REOPEN_DELAY_TICKS;
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

			closingByMacro = true;

			try {
				player.closeContainer();
			} finally {
				closingByMacro = false;
			}
		}

		finishRound(player);
	}

	private static void finishRound(LocalPlayer player) {
		if (movedThisRound > 0) {
			rounds++;
			totalMoved += movedThisRound;
			player.sendOverlayMessage(prefixed(Component.literal(roundStatus())));
		}

		movedThisRound = 0;
		attemptedSlots.clear();
		containerId = -1;
		state = State.SEND_COMMAND;
		timer = REOPEN_DELAY_TICKS;

		int maxRounds = SellMacroConfig.get().maxRounds;

		if (maxRounds > 0 && rounds >= maxRounds) {
			stop(Component.literal("Runden-Limit von " + maxRounds + " erreicht."));
		}
	}

	private static String roundStatus() {
		StringBuilder status = new StringBuilder("Runde " + rounds + ": " + movedThisRound + " Items (gesamt " + totalMoved + ")");

		if (earned > 0) {
			double hours = Math.max(System.currentTimeMillis() - startedAt, 1_000) / 3_600_000.0;
			status.append(" | ").append(Earnings.format(earned)).append(" verdient, ")
					.append(Earnings.format(earned / hours)).append("/h");
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
