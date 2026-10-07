package io.github.kyloschmeilo.sellmacro;

import java.util.Locale;
import java.util.Set;

import net.minecraft.client.Minecraft;

/** Only the Minecraft accounts listed here may use the mod. */
public final class AllowedPlayers {
	// Add player names here (case does not matter).
	private static final Set<String> NAMES = Set.of(
			"kyloschmeilo",
			"_danilo",
			"genius187",
			"Mtb1304",
			"67Sigmaligma"
	);

	private AllowedPlayers() {
	}

	public static boolean isAllowed() {
		String name = Minecraft.getInstance().getUser().getName();
		return NAMES.stream().anyMatch(allowed -> allowed.toLowerCase(Locale.ROOT).equals(name.toLowerCase(Locale.ROOT)));
	}
}
