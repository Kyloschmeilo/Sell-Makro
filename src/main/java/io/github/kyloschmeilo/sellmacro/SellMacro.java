package io.github.kyloschmeilo.sellmacro;

import java.util.HashSet;
import java.util.Set;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Opens the server's /sell GUI, shift-clicks every stack of the selected item into it,
 * closes the GUI (which sells the items) and opens it again. This repeats until the
 * player closes the /sell GUI themselves.
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

	public static final int DEFAULT_CLICK_DELAY = 1;
	public static final int MAX_CLICK_DELAY = 20;

	private enum State {
		IDLE,
		SEND_COMMAND,
		WAIT_FOR_GUI,
		FILL,
		CLOSE
	}

	private static State state = State.IDLE;
	private static Item item;
	private static int timer;
	private static int openAttempts;
	private static int containerId = -1;
	/** Menu slots already shift-clicked this round, so slots the server refuses are not clicked forever. */
	private static final Set<Integer> attemptedSlots = new HashSet<>();
	private static int movedThisRound;
	private static int rounds;
	private static long totalMoved;
	private static boolean closingByMacro;
	/** Ticks between two shift-clicks, 0 moves everything in the same tick. */
	private static int clickDelay = DEFAULT_CLICK_DELAY;

	private SellMacro() {
	}

	public static boolean isRunning() {
		return state != State.IDLE;
	}

	public static int getClickDelay() {
		return clickDelay;
	}

	public static void setClickDelay(int ticks) {
		clickDelay = Math.clamp(ticks, 0, MAX_CLICK_DELAY);
	}

	public static void start(Item newItem) {
		reset();

		if (!AllowedPlayers.isAllowed()) {
			return;
		}

		item = newItem;
		state = State.SEND_COMMAND;
	}

	public static void stop(Component reason) {
		LocalPlayer player = Minecraft.getInstance().player;

		if (player != null && state != State.IDLE) {
			player.sendSystemMessage(prefixed(Component.empty()
					.append(reason)
					.append(" (" + rounds + " Runden, " + totalMoved + "x ")
					.append(itemName())
					.append(")")));
		}

		reset();
	}

	private static void reset() {
		state = State.IDLE;
		item = null;
		timer = 0;
		openAttempts = 0;
		containerId = -1;
		attemptedSlots.clear();
		movedThisRound = 0;
		rounds = 0;
		totalMoved = 0;
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
			player.sendOverlayMessage(prefixed(Component.literal("Runde " + rounds + ": " + movedThisRound + "x ")
					.append(itemName())
					.append(" eingelegt (gesamt " + totalMoved + ")")));
		}

		movedThisRound = 0;
		attemptedSlots.clear();
		containerId = -1;
		state = State.SEND_COMMAND;
		timer = REOPEN_DELAY_TICKS;
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

	/** Next player inventory slot holding the selected item that wasn't clicked this round. */
	private static Slot nextSlot(AbstractContainerMenu menu, LocalPlayer player) {
		for (Slot slot : menu.slots) {
			if (slot.container instanceof Inventory
					&& !attemptedSlots.contains(slot.index)
					&& slot.hasItem()
					&& slot.getItem().getItem() == item
					&& slot.mayPickup(player)) {
				return slot;
			}
		}

		return null;
	}

	public static Component itemName() {
		return item == null ? Component.literal("?") : itemName(item);
	}

	public static Component itemName(Item item) {
		return new ItemStack(item).getItemName();
	}

	public static MutableComponent prefixed(Component message) {
		return Component.empty()
				.append(Component.literal("[SellMacro] ").withStyle(ChatFormatting.GOLD))
				.append(message);
	}
}
