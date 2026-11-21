package io.github.kyloschmeilo.sellmacro;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import net.fabricmc.loader.api.FabricLoader;

/** Settings and presets, stored in config/sellmacro.json. */
public final class SellMacroConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("sellmacro.json");

	/** Matches money amounts like "$1,234.5", "1.234 $", "2,5k Coins". The first non-empty group is the amount. */
	public static final String DEFAULT_EARNINGS_PATTERN =
			"(?i)(?:\\$\\s?([0-9][0-9.,]*\\s?[kmb]?)|([0-9][0-9.,]*\\s?[kmb]?)\\s?(?:\\$|€|coins?|münzen|dollar))";

	public int clickDelay = 1;
	/** Sell with the GUI's confirm button instead of closing and reopening the GUI. */
	public boolean useConfirmButton = true;
	/** Never sell stacks that are renamed or enchanted. */
	public boolean protectSpecialItems = true;
	public boolean stopOnDamage = true;
	/** Start again automatically after a server restart or transfer. */
	public boolean autoResume = true;
	/** Start the last macro again after this many minutes without input, 0 = off. */
	public int idleRestartMinutes = 3;
	/** Stop after this many rounds, 0 = no limit. */
	public int maxRounds = 0;
	/** Stop once this much money was earned, 0 = no limit. */
	public double moneyLimit = 0;
	public String earningsPattern = DEFAULT_EARNINGS_PATTERN;
	/** Item ids of the last started macro, used by the hotkey. */
	public List<String> lastItems = new ArrayList<>();
	public Map<String, List<String>> presets = new LinkedHashMap<>();

	/** What the auction house auto-buy looks for. */
	public List<AuctionRule> auctionRules = new ArrayList<>();
	/** Test mode: only report matching offers, never buy. On until the player turns it off. */
	public boolean auctionDryRun = true;
	/** Most money the auto-buy may spend per run, 0 = no limit. */
	public double auctionBudget = 0;
	/** Seconds between two reloads of the auction house. */
	public int auctionRefreshSeconds = 5;

	public static final class AuctionRule {
		/** Part of the item name, case does not matter, e.g. "star pickaxe". */
		public String name = "";
		public double maxPrice;
		/** Minimum remaining tool time in seconds ("Star-Tool läuft ab in"), 0 = don't care. */
		public long minToolSeconds;
	}

	private static SellMacroConfig instance;

	public static SellMacroConfig get() {
		if (instance == null) {
			instance = load();
		}

		return instance;
	}

	private static SellMacroConfig load() {
		if (Files.exists(PATH)) {
			try (Reader reader = Files.newBufferedReader(PATH)) {
				SellMacroConfig config = GSON.fromJson(reader, SellMacroConfig.class);

				if (config != null) {
					config.fixNulls();
					return config;
				}
			} catch (IOException | JsonParseException e) {
				SellMacroClient.LOGGER.warn("Could not read {}, using defaults", PATH, e);
			}
		}

		return new SellMacroConfig();
	}

	public void save() {
		try {
			Files.createDirectories(PATH.getParent());

			try (Writer writer = Files.newBufferedWriter(PATH)) {
				GSON.toJson(this, writer);
			}
		} catch (IOException e) {
			SellMacroClient.LOGGER.warn("Could not write {}", PATH, e);
		}
	}

	private void fixNulls() {
		if (earningsPattern == null || earningsPattern.isBlank()) {
			earningsPattern = DEFAULT_EARNINGS_PATTERN;
		}

		if (lastItems == null) {
			lastItems = new ArrayList<>();
		}

		if (presets == null) {
			presets = new LinkedHashMap<>();
		}

		if (auctionRules == null) {
			auctionRules = new ArrayList<>();
		}

		auctionRules.removeIf(rule -> rule == null || rule.name == null || rule.name.isBlank());
		auctionRefreshSeconds = Math.clamp(auctionRefreshSeconds, AuctionSniper.MIN_REFRESH_SECONDS, AuctionSniper.MAX_REFRESH_SECONDS);

		clickDelay = Math.clamp(clickDelay, 0, SellMacro.MAX_CLICK_DELAY);
	}
}
