import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Keeps the Aura Points mod in a mods folder up to date. Prism runs it before every launch:
 *   "$INST_JAVA" -jar "$INST_DIR/aura-updater.jar" "$INST_MC_DIR/mods"
 * It reads latest.json from the GitHub repo ({"version", "file", "url", "sha256"}); if the mods folder
 * doesn't hold exactly that jar, it downloads it, checks the SHA-256, puts it in, and only then removes
 * every other copy of the mod (found by its mod id, whatever the file is called).
 *
 * It never stops the game from starting: every problem (offline, GitHub down, a locked file) is only
 * printed, the exit code is always 0, and a watchdog ends it after WATCHDOG_SECONDS no matter what.
 */
public final class AuraUpdater {

	static final String DEFAULT_MANIFEST = "https://raw.githubusercontent.com/ToastbutBuried/aura-points/main/latest.json";
	static final String MOD_ID = "aurapoints";
	static final int WATCHDOG_SECONDS = 45;
	static final String TMP = ".aura-points-download.tmp"; // not a .jar, so the game never loads it half-written

	static volatile Path mods;

	public static void main(String[] args) {
		// whatever happens below, the launch goes on within WATCHDOG_SECONDS
		Thread watchdog = new Thread(() -> {
			try {
				Thread.sleep(WATCHDOG_SECONDS * 1000L);
			} catch (InterruptedException e) {
				return;
			}
			log("took too long - skipped, starting with the jar you have");
			cleanTmp();
			Runtime.getRuntime().halt(0);
		});
		watchdog.setDaemon(true);
		watchdog.start();
		try {
			if (args.length < 1) {
				log("usage: java -jar aura-updater.jar <mods folder> [latest.json url]");
			} else {
				mods = Path.of(args[0]);
				run(args.length > 1 ? args[1] : DEFAULT_MANIFEST);
			}
		} catch (Throwable t) {
			log("skipped (" + t.getClass().getSimpleName() + (t.getMessage() == null ? "" : ": " + t.getMessage())
					+ ") - starting with the jar you have");
			cleanTmp();
		}
		System.exit(0);
	}

	static void run(String manifestUrl) throws Exception {
		if (!Files.isDirectory(mods)) throw new IllegalStateException("no mods folder at " + mods);
		cleanTmp(); // a leftover from a run that was cut off
		HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
				.followRedirects(HttpClient.Redirect.NORMAL).build();

		// a query string so GitHub's raw cache never hands out an old latest.json
		HttpResponse<String> manifest = http.send(HttpRequest.newBuilder(URI.create(manifestUrl + "?t=" + System.currentTimeMillis()))
				.timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString());
		if (manifest.statusCode() != 200) throw new IllegalStateException("latest.json returned HTTP " + manifest.statusCode());
		String json = manifest.body();
		String version = field(json, "version"), file = field(json, "file"), url = field(json, "url"), sha = field(json, "sha256");
		if (!file.matches("aura-points-[A-Za-z0-9.+_-]+\\.jar")) throw new IllegalStateException("odd file name in latest.json: " + file);
		if (!sha.matches("[0-9a-fA-F]{64}")) throw new IllegalStateException("odd checksum in latest.json");
		if (!url.startsWith("https://") && !url.startsWith("http://127.0.0.1")) throw new IllegalStateException("download url must be https");

		Path target = mods.resolve(file);
		if (Files.isRegularFile(target) && sha256(target).equalsIgnoreCase(sha)) {
			removeOthers(target);
			log("Aura Points " + version + " is up to date");
			return;
		}

		log("downloading Aura Points " + version + " ...");
		Path tmp = mods.resolve(TMP);
		HttpResponse<InputStream> res = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15)).build(),
				HttpResponse.BodyHandlers.ofInputStream());
		if (res.statusCode() != 200) throw new IllegalStateException("download returned HTTP " + res.statusCode());
		try (InputStream in = res.body()) {
			Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
		}
		if (!sha256(tmp).equalsIgnoreCase(sha)) throw new IllegalStateException("checksum mismatch, download thrown away");

		// the new jar goes in first; only once it is there do the old ones go - and if that fails,
		// the new one is taken back out, so there are never two copies of the mod (that would crash)
		boolean hadTarget = Files.exists(target);
		Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
		try {
			removeOthers(target);
		} catch (Exception e) {
			if (!hadTarget) Files.deleteIfExists(target);
			throw e;
		}
		log("installed Aura Points " + version);
	}

	/** Every other copy of the mod goes: any .jar whose mod id is aurapoints, whatever it is named. */
	static void removeOthers(Path keep) throws Exception {
		List<Path> old = new ArrayList<>();
		try (DirectoryStream<Path> jars = Files.newDirectoryStream(mods, "*.jar")) {
			for (Path p : jars) {
				if (!p.getFileName().equals(keep.getFileName()) && isAuraPoints(p)) old.add(p);
			}
		}
		for (Path p : old) {
			Files.delete(p);
			log("removed old " + p.getFileName());
		}
	}

	static boolean isAuraPoints(Path jar) {
		String name = jar.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
		if (name.startsWith("aura-points-")) return true;
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			ZipEntry toml = zip.getEntry("META-INF/neoforge.mods.toml");
			if (toml == null) return false;
			try (InputStream in = zip.getInputStream(toml)) {
				String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
				return Pattern.compile("modId\\s*=\\s*\"" + MOD_ID + "\"").matcher(text).find();
			}
		} catch (Exception e) {
			return false; // not a readable jar - not ours to touch
		}
	}

	static void cleanTmp() {
		try {
			if (mods != null) Files.deleteIfExists(mods.resolve(TMP));
		} catch (Exception ignored) {
			// nothing to do
		}
	}

	static String field(String json, String key) {
		Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
		if (!m.find()) throw new IllegalStateException("latest.json has no " + key);
		return m.group(1);
	}

	static String sha256(Path p) throws Exception {
		MessageDigest md = MessageDigest.getInstance("SHA-256");
		md.update(Files.readAllBytes(p));
		return HexFormat.of().formatHex(md.digest());
	}

	static void log(String s) {
		System.out.println("[aura-updater] " + s);
		System.out.flush();
	}
}
