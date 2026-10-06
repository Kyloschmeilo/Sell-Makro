package io.github.kyloschmeilo.sellmacro;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Reads money amounts out of server chat messages. */
public final class Earnings {
	private static String compiledSource;
	private static Pattern compiled;

	private Earnings() {
	}

	/** Returns whether the regex compiles, so commands can reject broken patterns. */
	public static boolean isValidPattern(String regex) {
		try {
			Pattern.compile(regex);
			return true;
		} catch (PatternSyntaxException e) {
			return false;
		}
	}

	/** Sum of all money amounts in the message, 0 if there are none. */
	public static double parse(String message) {
		Pattern pattern = pattern();

		if (pattern == null) {
			return 0;
		}

		double total = 0;
		Matcher matcher = pattern.matcher(message);

		while (matcher.find()) {
			String amount = firstGroup(matcher);

			if (amount != null) {
				total += parseAmount(amount);
			}
		}

		return total;
	}

	private static Pattern pattern() {
		String source = SellMacroConfig.get().earningsPattern;

		if (!source.equals(compiledSource)) {
			compiledSource = source;

			try {
				compiled = Pattern.compile(source);
			} catch (PatternSyntaxException e) {
				compiled = null;
			}
		}

		return compiled;
	}

	private static String firstGroup(Matcher matcher) {
		for (int i = 1; i <= matcher.groupCount(); i++) {
			if (matcher.group(i) != null && !matcher.group(i).isBlank()) {
				return matcher.group(i);
			}
		}

		return matcher.groupCount() == 0 ? matcher.group() : null;
	}

	/** Parses "1,234.56", "1.234,56", "1.234", "2,5k" and similar. */
	static double parseAmount(String raw) {
		String text = raw.trim().replace(" ", "").toLowerCase(Locale.ROOT);
		double multiplier = 1;

		if (!text.isEmpty()) {
			switch (text.charAt(text.length() - 1)) {
				case 'k' -> multiplier = 1_000;
				case 'm' -> multiplier = 1_000_000;
				case 'b' -> multiplier = 1_000_000_000;
				default -> {
				}
			}

			if (multiplier != 1) {
				text = text.substring(0, text.length() - 1);
			}
		}

		text = text.replaceAll("[.,]+$", "");
		int lastDot = text.lastIndexOf('.');
		int lastComma = text.lastIndexOf(',');

		if (lastDot >= 0 && lastComma >= 0) {
			// Both separators: the last one is the decimal separator.
			char decimal = lastDot > lastComma ? '.' : ',';
			char grouping = decimal == '.' ? ',' : '.';
			text = text.replace(String.valueOf(grouping), "").replace(decimal, '.');
		} else if (lastDot >= 0 || lastComma >= 0) {
			char separator = lastDot >= 0 ? '.' : ',';
			String[] parts = text.split(Pattern.quote(String.valueOf(separator)));
			boolean grouping = parts.length > 2 || parts[parts.length - 1].length() == 3;
			text = grouping ? text.replace(String.valueOf(separator), "") : text.replace(separator, '.');
		}

		try {
			return Double.parseDouble(text) * multiplier;
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	/** German number format: dots between thousands, comma before cents, e.g. 1.234.567,5. */
	public static String format(double amount) {
		DecimalFormat format = new DecimalFormat("#,##0.##", DecimalFormatSymbols.getInstance(Locale.GERMANY));
		return format.format(amount);
	}

	/**
	 * Average earnings per minute since the start, extrapolated to one hour. The first minute
	 * counts as a full minute, so a quick first sale doesn't show a huge hourly rate.
	 */
	public static double perHour(double earned, long elapsedMillis) {
		double minutes = Math.max(elapsedMillis / 60_000.0, 1);
		return earned / minutes * 60;
	}
}
