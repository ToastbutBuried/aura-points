import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keeps aura-points-*.jar in a mods folder up to date. Prism runs it before every launch:
 *   "$INST_JAVA" -jar "$INST_DIR/aura-updater.jar" "$INST_MC_DIR/mods"
 * It reads latest.json from the GitHub repo ({"version", "file", "url", "sha256"}), and if the mods
 * folder doesn't hold exactly that jar, downloads it, checks the SHA-256, then swaps it in for any
 * other aura-points jar. Anything going wrong (offline, GitHub down) only prints a line - the game
 * always starts, with whatever jar it had.
 */
public final class AuraUpdater {

	static final String DEFAULT_MANIFEST = "https://raw.githubusercontent.com/ToastbutBuried/aura-points/main/latest.json";

	public static void main(String[] args) {
		try {
			if (args.length < 1) {
				log("usage: java -jar aura-updater.jar <mods folder> [latest.json url]");
				return;
			}
			run(Path.of(args[0]), args.length > 1 ? args[1] : DEFAULT_MANIFEST);
		} catch (Exception e) {
			log("skipped (" + e.getClass().getSimpleName() + ": " + e.getMessage() + ") - starting with the jar you have");
		}
	}

	static void run(Path mods, String manifestUrl) throws Exception {
		if (!Files.isDirectory(mods)) throw new IllegalStateException("no mods folder at " + mods);
		HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4))
				.followRedirects(HttpClient.Redirect.NORMAL).build();

		// a query string so GitHub's raw cache never hands out an old latest.json
		String json = http.send(HttpRequest.newBuilder(URI.create(manifestUrl + "?t=" + System.currentTimeMillis()))
				.timeout(Duration.ofSeconds(8)).build(), HttpResponse.BodyHandlers.ofString()).body();
		String version = field(json, "version"), file = field(json, "file"), url = field(json, "url"), sha = field(json, "sha256");
		if (!file.matches("aura-points-[A-Za-z0-9.+_-]+\\.jar")) throw new IllegalStateException("odd file name in latest.json: " + file);

		Path target = mods.resolve(file);
		if (Files.exists(target) && sha256(target).equalsIgnoreCase(sha)) {
			removeOthers(mods, file);
			log("Aura Points " + version + " is up to date");
			return;
		}

		log("downloading Aura Points " + version + " ...");
		Path tmp = mods.resolve(".aura-points-download.tmp"); // not a .jar, so never loaded half-written
		HttpResponse<InputStream> res = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60)).build(),
				HttpResponse.BodyHandlers.ofInputStream());
		if (res.statusCode() != 200) throw new IllegalStateException("download returned HTTP " + res.statusCode());
		try (InputStream in = res.body()) {
			Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
		}
		String got = sha256(tmp);
		if (!got.equalsIgnoreCase(sha)) {
			Files.deleteIfExists(tmp);
			throw new IllegalStateException("checksum mismatch, download thrown away");
		}
		removeOthers(mods, file);
		Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
		log("installed Aura Points " + version);
	}

	/** Every other aura-points jar goes, so two versions are never loaded at once. */
	static void removeOthers(Path mods, String keep) throws Exception {
		try (DirectoryStream<Path> jars = Files.newDirectoryStream(mods, "aura-points-*.jar")) {
			for (Path p : jars) {
				if (!p.getFileName().toString().equals(keep)) {
					Files.delete(p);
					log("removed old " + p.getFileName());
				}
			}
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
	}
}
