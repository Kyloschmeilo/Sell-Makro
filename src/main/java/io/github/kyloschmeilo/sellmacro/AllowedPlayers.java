package io.github.kyloschmeilo.sellmacro;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * Only accounts whose UUID is on the online allow list may use the mod. The list is not part of
 * the mod: it is downloaded from {@link #LIST_URL}, which only the owner of that file can change.
 */
public final class AllowedPlayers {
	/** JSON list of allowed players: {"players": [{"name": "...", "uuid": "..."}]}. */
	public static final String LIST_URL = "https://raw.githubusercontent.com/Kyloschmeilo/Sell-Makro-Zugang/main/allowed.json";
	private static final Duration TIMEOUT = Duration.ofSeconds(8);
	private static final long REFRESH_MILLIS = 10 * 60 * 1000;
	private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

	public enum Status {
		ALLOWED,
		NOT_ALLOWED,
		/** The list couldn't be downloaded yet. */
		UNKNOWN
	}

	/** Null until the list was downloaded once. */
	private static volatile Set<UUID> allowed;
	private static volatile long loadedAt;
	private static volatile boolean loading;

	private AllowedPlayers() {
	}

	public static boolean isAllowed() {
		return status() == Status.ALLOWED;
	}

	public static Status status() {
		refreshIfStale();
		Set<UUID> list = allowed;

		if (list == null) {
			return Status.UNKNOWN;
		}

		return list.contains(ownUuid()) ? Status.ALLOWED : Status.NOT_ALLOWED;
	}

	public static UUID ownUuid() {
		return Minecraft.getInstance().getUser().getProfileId();
	}

	/** Downloads the list in the background if it is missing or older than 10 minutes. */
	public static void refreshIfStale() {
		if (!loading && (allowed == null || System.currentTimeMillis() - loadedAt > REFRESH_MILLIS)) {
			loading = true;
			CompletableFuture.runAsync(AllowedPlayers::load).whenComplete((result, error) -> loading = false);
		}
	}

	/** Downloads the list right away if it isn't there yet, e.g. before the commands get registered. */
	public static void loadNowIfMissing() {
		if (allowed == null) {
			load();
		}
	}

	private static void load() {
		try {
			HttpRequest request = HttpRequest.newBuilder(URI.create(LIST_URL))
					.timeout(TIMEOUT)
					.header("Cache-Control", "no-cache")
					.GET()
					.build();
			HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

			if (response.statusCode() != 200) {
				SellMacroClient.LOGGER.warn("Could not load allow list: HTTP {}", response.statusCode());
				return;
			}

			allowed = parse(response.body());
			loadedAt = System.currentTimeMillis();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} catch (IOException | RuntimeException e) {
			SellMacroClient.LOGGER.warn("Could not load allow list", e);
		}
	}

	/** Accepts {"players": [{"uuid": "..."}]} as well as a plain array of UUID strings. */
	static Set<UUID> parse(String json) {
		JsonElement root = JsonParser.parseString(json);
		JsonArray players = root.isJsonArray() ? root.getAsJsonArray() : root.getAsJsonObject().getAsJsonArray("players");
		Set<UUID> result = new HashSet<>();

		for (JsonElement entry : players) {
			try {
				String uuid = entry.isJsonObject() ? entry.getAsJsonObject().get("uuid").getAsString() : entry.getAsString();
				result.add(parseUuid(uuid));
			} catch (RuntimeException e) {
				SellMacroClient.LOGGER.warn("Skipping invalid allow list entry {}", entry);
			}
		}

		return Set.copyOf(result);
	}

	/** Parses UUIDs with or without dashes. */
	static UUID parseUuid(String raw) {
		String hex = raw.trim().replace("-", "");

		if (hex.length() != 32) {
			throw new IllegalArgumentException("Not a UUID: " + raw);
		}

		return new UUID(Long.parseUnsignedLong(hex.substring(0, 16), 16), Long.parseUnsignedLong(hex.substring(16), 16));
	}

	public static MutableComponent deniedMessage() {
		String text = status() == Status.UNKNOWN
				? "Freischaltung konnte nicht geladen werden (keine Verbindung?). Versuch es gleich nochmal."
				: "Du bist nicht berechtigt, diese Mod zu nutzen. Deine UUID bekommst du mit /sellmacro uuid.";
		return SellMacro.prefixed(Component.literal(text).withStyle(ChatFormatting.RED));
	}
}
