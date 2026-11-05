package com.toast.aurapoints;

import com.toast.aurapoints.config.AuraConfig;
import net.minecraft.server.MinecraftServer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * "I'm online": when the server has finished starting it POSTs a tiny JSON packet to
 * {@link AuraConfig#SERVER_ONLINE_NOTIFY_URL} (if set), so whoever deployed it (tools/deploy.sh listens for it) knows
 * at once instead of polling the log. Background thread, 3 s timeout, never throws into the server.
 */
public final class ServerOnlineNotify {

	private ServerOnlineNotify() {
	}

	public static void send(MinecraftServer server) {
		String url = AuraConfig.SERVER_ONLINE_NOTIFY_URL == null ? "" : AuraConfig.SERVER_ONLINE_NOTIFY_URL.trim();
		if (url.isEmpty() || !server.isDedicatedServer()) return;
		Thread t = new Thread(() -> {
			try {
				String body = "{\"event\":\"online\",\"mod\":\"aurapoints\",\"players\":" + server.getPlayerCount() + "}";
				HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(3))
						.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
				HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build().send(req, HttpResponse.BodyHandlers.discarding());
				AuraPoints.LOGGER.info("Told {} that the server is online", url);
			} catch (Exception e) {
				AuraPoints.LOGGER.warn("Could not send the online packet to {}: {}", url, e.toString());
			}
		}, "aurapoints-online-notify");
		t.setDaemon(true);
		t.start();
	}
}
