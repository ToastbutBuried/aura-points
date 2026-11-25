package com.toast.aurapoints;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The "when did it happen" answer of a ticket, and the server-log snapshot around that moment.
 * Reads the tail of logs/latest.log (NeoForge "[25Nov2026 19:30:03.448]" and Fabric "[19:30:03]" stamps),
 * keeps a few minutes around the time, hides IP addresses and writes it next to the tickets.
 */
final class TicketLogs {

	private TicketLogs() {
	}

	/** How much of the log is kept: this long before the moment and this long after it. */
	static final long BEFORE_MS = 3 * 60_000L;
	static final long AFTER_MS = 2 * 60_000L;
	/** Relative answers ("3 days ago") stop here. */
	static final long MAX_AGE_MS = 7 * 86_400_000L;
	static final int MAX_LINES = 500;
	static final int MAX_CHARS = 64 * 1024;
	/** Only this much of the end of latest.log is read, so a huge log can't stall the server thread. */
	static final int TAIL_BYTES = 6 * 1024 * 1024;
	private static final int MAX_BLOCK_CHARS = 8000;

	/** The parsed answer: a time, or what was wrong with it. */
	record When(long at, String error) {
		static When ok(long at) {
			return new When(at, null);
		}

		static When bad(String error) {
			return new When(0, error);
		}
	}

	/** What the snapshot found: how many lines were written (0 = no file) and a short note for reviewers. */
	record Captured(int lines, String note) {
	}

	static final String HELP = "Try: now, 5 min ago, 2 hours ago, or a time of day like 19:15 (server time).";

	private static final Pattern RELATIVE = Pattern.compile(
			"(\\d{1,4})\\s*(s|sec|secs|second|seconds|m|min|mins|minute|minutes|h|hr|hrs|hour|hours|d|day|days)(?:\\s+ago)?");
	private static final Pattern CLOCK = Pattern.compile("(?:at\\s+)?(\\d{1,2})[:.](\\d{2})(?:[:.](\\d{2}))?");
	private static final Pattern NOW = Pattern.compile("(?:just\\s+|right\\s+)?now|just happened|a moment ago|a second ago");

	/** "now", "5 min ago", "2h", "19:15" (server time, today, or yesterday if that is still ahead) -> a moment, never in the future. */
	static When parseWhen(String raw, long now, ZoneId zone) {
		String s = raw == null ? "" : raw.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
		if (s.isEmpty()) return When.bad("Say when it happened. " + HELP);
		if (NOW.matcher(s).matches()) return When.ok(now);
		s = s.replaceFirst("^(a|an) ", "1 ");

		Matcher m = RELATIVE.matcher(s);
		if (m.matches()) {
			long n = Long.parseLong(m.group(1));
			long unit = switch (m.group(2).charAt(0)) {
				case 's' -> 1_000L;
				case 'm' -> 60_000L;
				case 'h' -> 3_600_000L;
				default -> 86_400_000L;
			};
			long ago = n * unit;
			if (ago > MAX_AGE_MS) return When.bad("That's more than a week ago. " + HELP);
			return When.ok(now - ago);
		}

		m = CLOCK.matcher(s);
		if (m.matches()) {
			int h = Integer.parseInt(m.group(1));
			int min = Integer.parseInt(m.group(2));
			int sec = m.group(3) == null ? 0 : Integer.parseInt(m.group(3));
			if (h > 23 || min > 59 || sec > 59) return When.bad("That isn't a time of day. " + HELP);
			LocalDate today = ZonedDateTime.ofInstant(Instant.ofEpochMilli(now), zone).toLocalDate();
			long at = today.atTime(h, min, sec).atZone(zone).toInstant().toEpochMilli();
			if (at > now + 60_000L) at = today.minusDays(1).atTime(h, min, sec).atZone(zone).toInstant().toEpochMilli();
			return When.ok(Math.min(at, now));
		}
		return When.bad("I didn't understand \"" + (raw.strip().length() > 30 ? raw.strip().substring(0, 30) + "..." : raw.strip()) + "\". " + HELP);
	}

	// ---------------------------------------------------------------
	// Log snapshot
	// ---------------------------------------------------------------

	private static final Pattern STAMP = Pattern.compile("^\\[(?:(\\d{2}[A-Za-z]{3}\\d{4}) )?(\\d{2}):(\\d{2}):(\\d{2})(?:\\.\\d+)?]");
	private static final DateTimeFormatter DATE = new DateTimeFormatterBuilder().parseCaseInsensitive()
			.appendPattern("ddMMMuuuu").toFormatter(Locale.ENGLISH);
	private static final Pattern IPV6 = Pattern.compile("\\[/[0-9a-fA-F:.%]+]");
	private static final Pattern IPV4 = Pattern.compile("\\b\\d{1,3}(?:\\.\\d{1,3}){3}(?::\\d{1,5})?\\b");

	/** One log entry: its stamped first line plus any lines under it (stack traces). */
	private static final class Block {
		final int index;
		final long at;
		final StringBuilder text;

		Block(int index, long at, String first) {
			this.index = index;
			this.at = at;
			this.text = new StringBuilder(first);
		}

		void add(String line) {
			if (text.length() < MAX_BLOCK_CHARS) text.append('\n').append(line);
		}

		/** Warnings, errors, the author's own lines and anything from the mod. */
		boolean important(String authorLower) {
			String t = text.toString();
			if (t.contains("/WARN]") || t.contains("/ERROR]") || t.contains("/FATAL]")) return true;
			String lower = t.toLowerCase(Locale.ROOT);
			return lower.contains("aura") || (!authorLower.isEmpty() && lower.contains(authorLower));
		}
	}

	/** Epoch millis of a stamped log line, or -1 for a line without a stamp. Fabric stamps have no date: today, or yesterday if that is ahead. */
	static long stamp(String line, long now, ZoneId zone) {
		if (line.isEmpty() || line.charAt(0) != '[') return -1;
		Matcher m = STAMP.matcher(line);
		if (!m.find()) return -1;
		try {
			LocalTime time = LocalTime.of(Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4)));
			if (m.group(1) != null) return LocalDate.parse(m.group(1), DATE).atTime(time).atZone(zone).toInstant().toEpochMilli();
			LocalDate today = ZonedDateTime.ofInstant(Instant.ofEpochMilli(now), zone).toLocalDate();
			long at = today.atTime(time).atZone(zone).toInstant().toEpochMilli();
			return at > now + 60_000L ? today.minusDays(1).atTime(time).atZone(zone).toInstant().toEpochMilli() : at;
		} catch (DateTimeException | NumberFormatException e) {
			return -1;
		}
	}

	static String redact(String line) {
		return IPV4.matcher(IPV6.matcher(line).replaceAll("[/<ip>]")).replaceAll("<ip>");
	}

	/**
	 * Copies the log lines around {@code eventAt} into {@code out}. Never throws: a log problem must not lose the ticket.
	 * {@code header} goes on top as "# " lines.
	 */
	static Captured capture(Path source, Path out, long eventAt, long now, String author, String header, ZoneId zone) {
		try {
			return doCapture(source, out, eventAt, now, author, header, zone);
		} catch (IOException | RuntimeException e) {
			AuraPoints.LOGGER.warn("Ticket log snapshot failed", e);
			return new Captured(0, "log snapshot failed");
		}
	}

	private static Captured doCapture(Path source, Path out, long eventAt, long now, String author, String header, ZoneId zone) throws IOException {
		if (!Files.isRegularFile(source)) return new Captured(0, "no server log found");
		long from = eventAt - BEFORE_MS;
		long to = Math.min(eventAt + AFTER_MS, now);

		List<String> lines;
		boolean cut;
		try (FileChannel ch = FileChannel.open(source, StandardOpenOption.READ)) {
			long size = ch.size();
			long start = Math.max(0, size - TAIL_BYTES);
			ByteBuffer buf = ByteBuffer.allocate((int) (size - start));
			long pos = start;
			while (buf.hasRemaining()) {
				int n = ch.read(buf, pos);
				if (n < 0) break;
				pos += n;
			}
			cut = start > 0;
			lines = new String(buf.array(), 0, buf.position(), StandardCharsets.UTF_8).lines().toList();
			if (cut && !lines.isEmpty()) lines = lines.subList(1, lines.size()); // first line is probably cut in half
		}

		List<Block> blocks = new ArrayList<>();
		Block cur = null;
		long firstStamp = -1;
		for (String line : lines) {
			long at = stamp(line, now, zone);
			if (at < 0) {
				if (cur != null) cur.add(line);
				continue;
			}
			if (firstStamp < 0) firstStamp = at;
			cur = at >= from && at <= to ? new Block(blocks.size(), at, line) : null;
			if (cur != null) blocks.add(cur);
		}
		if (blocks.isEmpty()) {
			String where = firstStamp < 0 ? "" : "; the log I could read starts at "
					+ DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ENGLISH).withZone(zone).format(Instant.ofEpochMilli(firstStamp))
					+ (cut ? " (older parts are not read)" : " (server restarted since?)");
			return new Captured(0, "nothing in the log around that time" + where);
		}

		String authorLower = author == null ? "" : author.toLowerCase(Locale.ROOT);
		int total = blocks.size();
		String note = null;
		List<Block> keep = blocks;
		if (tooBig(keep)) {
			keep = blocks.stream().filter(b -> b.important(authorLower)).toList();
			note = "only warnings, errors, the player's and the mod's lines";
		}
		if (tooBig(keep)) {
			keep = nearest(keep, eventAt);
			note = "only the " + keep.size() + " entries closest to that time";
		}
		if (keep.isEmpty()) return new Captured(0, "nothing worth keeping in the log around that time");

		StringBuilder sb = new StringBuilder();
		for (String h : header.split("\n")) sb.append("# ").append(h).append('\n');
		sb.append("# ").append(keep.size()).append(" of ").append(total).append(" log entries kept")
				.append(note == null ? "" : " (" + note + ")").append("; IP addresses hidden\n\n");
		for (Block b : keep) sb.append(redact(b.text.toString())).append('\n');
		Files.createDirectories(out.getParent());
		Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
		return new Captured(keep.size(), keep.size() + " log entries" + (note == null ? "" : ", " + note));
	}

	private static boolean tooBig(List<Block> blocks) {
		if (blocks.size() > MAX_LINES) return true;
		long chars = 0;
		for (Block b : blocks) chars += b.text.length() + 1;
		return chars > MAX_CHARS;
	}

	/** The blocks closest in time to {@code eventAt} that fit the budget, back in log order. */
	private static List<Block> nearest(List<Block> blocks, long eventAt) {
		List<Block> byDistance = new ArrayList<>(blocks);
		byDistance.sort(Comparator.comparingLong(b -> Math.abs(b.at - eventAt)));
		List<Block> keep = new ArrayList<>();
		long chars = 0;
		for (Block b : byDistance) {
			if (keep.size() >= MAX_LINES || chars + b.text.length() + 1 > MAX_CHARS) break;
			keep.add(b);
			chars += b.text.length() + 1;
		}
		keep.sort(Comparator.comparingInt(b -> b.index));
		return keep;
	}
}
