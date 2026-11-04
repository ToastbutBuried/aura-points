package com.toast.aurapoints.ability;

import com.toast.aurapoints.ability.AuraAbilities.Ability;
import com.toast.aurapoints.config.AuraConfig;
import com.toast.aurapoints.data.AuraPlayerData;
import com.toast.aurapoints.network.NetworkHandler;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Ice Shell, a Frost passive: ICE_SHELL_LAYERS layers of ice with ICE_SHELL_LAYER_HP each, worn down
 * by hits (damage after armor). The outermost layer takes the hit; when it breaks the rest of the hit
 * goes on inward. A while after the last hit it heals: cracks in the outer layer mend quickly, a broken
 * layer grows back slowly, one at a time - faster where it is cold, and a hit on a player counts as a
 * moment of healing. Others see a few snowflakes circling per layer; your HUD slot shows the layers.
 * In memory only (whole again after a server restart; a relog keeps it as it was).
 */
public final class IceShell {

	private IceShell() {
	}

	private static final int FLAKE_WHITE = 0xF4FBFF;
	private static final int ICE_BLUE = 0xA8E4FF;

	private static final class Shell {
		/** layers standing; the outermost has {@code top} health */
		int layers;
		float top;
		/** 0..1: the next broken layer growing back */
		double rebuild;
		/** no healing before this tick */
		long healFrom;
		/** what the HUD was told last, so it is only sent on changes */
		String sent = "";

		Shell(int layers, float top) {
			this.layers = layers;
			this.top = top;
		}
	}

	private static final Map<UUID, Shell> SHELLS = new HashMap<>();

	private static int maxLayers() {
		return Math.max(1, Math.min(10, AuraConfig.ICE_SHELL_LAYERS));
	}

	private static float layerHp() {
		return Math.max(0.1f, AuraConfig.ICE_SHELL_LAYER_HP);
	}

	private static Shell shell(ServerPlayer player) {
		return SHELLS.computeIfAbsent(player.getUUID(), k -> new Shell(maxLayers(), layerHp()));
	}

	private static boolean cold(ServerPlayer player) {
		var pos = player.blockPosition();
		return player.serverLevel().getBiome(pos).value().coldEnoughToSnow(pos);
	}

	private static boolean whole(Shell s) {
		return s.layers >= maxLayers() && s.top >= layerHp() - 1.0e-4f;
	}

	/**
	 * From LivingDamageEvent.Pre (after armor), for a player with Ice Shell: the shell takes what it
	 * can of the hit; returns what is left for the player.
	 */
	public static float absorb(ServerPlayer player, DamageSource source, float damage, long now) {
		if (damage <= 0) return damage;
		if (AuraConfig.ICE_SHELL_ONLY_ATTACKS && source.getEntity() == null) return damage;
		if (source.getEntity() == player) return damage;
		Shell s = shell(player);
		if (s.layers <= 0) return damage;
		double share = Math.max(0.0, Math.min(1.0, AuraConfig.ICE_SHELL_ABSORB_FRACTION));
		float onShell = (float) (damage * share);
		float through = damage - onShell;
		float taken = 0;
		int broken = 0;
		boolean noBreak = AuraAbilities.cooldownsDisabled(player); // /aura cooldowns off: it never wears down
		while (onShell > 0 && s.layers > 0) {
			float bite = Math.min(onShell, s.top);
			onShell -= bite;
			taken += bite;
			if (noBreak) {
				onShell = 0;
				break;
			}
			s.top -= bite;
			if (s.top <= 1.0e-4f) {
				s.layers--;
				broken++;
				s.top = s.layers > 0 ? layerHp() : 0;
			}
		}
		s.healFrom = now + Math.max(0, AuraConfig.ICE_SHELL_REGEN_DELAY_TICKS);
		AuraStats.add(player, AuraStats.ICE_SHELL_BLOCKED, Math.round(taken * 10));
		if (broken > 0) breakFx(player, broken, s.layers == 0);
		else crackFx(player);
		sync(player, s, true);
		return through + onShell;
	}

	/** You hit a player: PLAYER_HIT_TICKS of healing at once (even before the delay is over). */
	public static void hitPlayer(ServerPlayer player) {
		Shell s = SHELLS.get(player.getUUID());
		if (s == null || whole(s) || AuraConfig.ICE_SHELL_PLAYER_HIT_TICKS <= 0) return;
		heal(player, s, AuraConfig.ICE_SHELL_PLAYER_HIT_TICKS, cold(player));
		Vec3 c = AuraFx.chest(player);
		AuraFx.burst(player.serverLevel(), c.x, c.y, c.z, ParticleTypes.SNOWFLAKE, 4, 0.3, 0.01);
		sync(player, s, true);
	}

	/** {@code ticks} worth of healing: mend the cracked outer layer first, then grow broken ones back. */
	private static void heal(ServerPlayer player, Shell s, double ticks, boolean cold) {
		int max = maxLayers();
		float hp = layerHp();
		while (ticks > 1.0e-6 && !whole(s)) {
			if (s.layers > 0 && s.top < hp) {
				double perTick = hp / (double) Math.max(1, cold ? AuraConfig.ICE_SHELL_COLD_REPAIR_TICKS : AuraConfig.ICE_SHELL_REPAIR_TICKS);
				double need = (hp - s.top) / perTick;
				if (need > ticks) {
					s.top += (float) (perTick * ticks);
					return;
				}
				ticks -= need;
				s.top = hp;
				continue;
			}
			if (s.layers >= max) return;
			double perTick = 1.0 / Math.max(1, cold ? AuraConfig.ICE_SHELL_COLD_REBUILD_TICKS : AuraConfig.ICE_SHELL_REBUILD_TICKS);
			double need = (1.0 - s.rebuild) / perTick;
			if (need > ticks) {
				s.rebuild += perTick * ticks;
				return;
			}
			ticks -= need;
			s.rebuild = 0;
			s.layers++;
			s.top = hp;
			layerFx(player, s.layers >= max);
		}
	}

	/** Every server tick per player: healing, the HUD, and the circling flakes. */
	public static void tick(ServerPlayer player, AuraPlayerData data, long now) {
		if (!AuraAbilities.hasAbility(data, Ability.ICE_SHELL)) {
			SHELLS.remove(player.getUUID());
			return;
		}
		Shell s = shell(player);
		// the config changed: fit the shell to the new number of layers / health
		if (s.layers > maxLayers()) {
			s.layers = maxLayers();
			s.top = Math.min(s.top, layerHp());
		}
		if (s.top > layerHp()) s.top = layerHp();
		if (!player.isAlive() || player.isSpectator()) return;
		// a heal delay from an earlier session (single player keeps this into the next world, where ticks start over)
		if (s.healFrom > now + Math.max(0, AuraConfig.ICE_SHELL_REGEN_DELAY_TICKS)) s.healFrom = now;
		if (!whole(s) && now >= s.healFrom) heal(player, s, 1, cold(player));
		if (now % 5 == 0) sync(player, s, false);
		if (s.layers > 0 && now % 2 == 0 && !player.isInvisible()) flakes(player, s, now);
	}

	/** Ticks until the next layer stands again at the current rate (0 when whole). */
	private static int nextTicks(ServerPlayer player, Shell s) {
		if (whole(s)) return 0;
		boolean cold = cold(player);
		float hp = layerHp();
		if (s.layers > 0 && s.top < hp) {
			return (int) Math.ceil((hp - s.top) / hp * Math.max(1, cold ? AuraConfig.ICE_SHELL_COLD_REPAIR_TICKS : AuraConfig.ICE_SHELL_REPAIR_TICKS));
		}
		return (int) Math.ceil((1.0 - s.rebuild) * Math.max(1, cold ? AuraConfig.ICE_SHELL_COLD_REBUILD_TICKS : AuraConfig.ICE_SHELL_REBUILD_TICKS));
	}

	private static void sync(ServerPlayer player, Shell s, boolean force) {
		int max = maxLayers();
		float top = s.layers > 0 ? s.top / layerHp() : 0;
		int next = nextTicks(player, s);
		String key = max + ":" + s.layers + ":" + Math.round(top * 50) + ":" + Math.round(s.rebuild * 50) + ":" + (next / 10);
		if (!force && key.equals(s.sent)) return;
		s.sent = key;
		NetworkHandler.sendIceShell(player, max, s.layers, top, (float) s.rebuild, next);
	}

	/** The outer layer cracked but held: a few ice chips and a clink. */
	private static void crackFx(ServerPlayer player) {
		ServerLevel level = player.serverLevel();
		Vec3 c = AuraFx.chest(player);
		var ice = new BlockParticleOption(ParticleTypes.BLOCK, Blocks.ICE.defaultBlockState());
		AuraFx.burst(level, c.x, c.y, c.z, ice, 10, 0.4, 0.08);
		level.playSound(null, player.getX(), player.getY(), player.getZ(), AuraSounds.AMETHYST_BLOCK_HIT.value(), SoundSource.PLAYERS, 0.9f, 1.3f);
	}

	/** One or more layers broke: chunks of ice and a crack; the last one going is the loudest. */
	private static void breakFx(ServerPlayer player, int broken, boolean gone) {
		ServerLevel level = player.serverLevel();
		Vec3 c = AuraFx.chest(player);
		var ice = new BlockParticleOption(ParticleTypes.BLOCK, Blocks.PACKED_ICE.defaultBlockState());
		AuraFx.burst(level, c.x, c.y, c.z, ice, 20 + 15 * broken, 0.45, 0.15);
		AuraFx.burst(level, c.x, c.y, c.z, ParticleTypes.SNOWFLAKE, 8 + 6 * broken, 0.5, 0.06);
		AuraFx.burst(level, c.x, c.y, c.z, AuraFx.dust(ICE_BLUE, 1.4f), 8 * broken, 0.5, 0.0);
		level.playSound(null, player.getX(), player.getY(), player.getZ(), AuraSounds.GLASS_BREAK.value(), SoundSource.PLAYERS, gone ? 1.0f : 0.7f, gone ? 1.0f : 1.4f);
		if (gone) level.playSound(null, player.getX(), player.getY(), player.getZ(), AuraSounds.AMETHYST_CLUSTER_BREAK.value(), SoundSource.PLAYERS, 0.8f, 0.8f);
	}

	/** A layer grew back; a brighter chime once the shell is whole. */
	private static void layerFx(ServerPlayer player, boolean whole) {
		ServerLevel level = player.serverLevel();
		Vec3 c = AuraFx.chest(player);
		AuraFx.burst(level, c.x, c.y, c.z, AuraFx.dust(FLAKE_WHITE, 1.0f), whole ? 12 : 6, 0.45, 0.0);
		AuraFx.burst(level, c.x, c.y, c.z, ParticleTypes.SNOWFLAKE, whole ? 10 : 5, 0.4, 0.02);
		level.playSound(null, player.getX(), player.getY(), player.getZ(), AuraSounds.GLASS_PLACE.value(), SoundSource.PLAYERS, 0.5f, whole ? 1.4f : 1.2f);
		if (whole) level.playSound(null, player.getX(), player.getY(), player.getZ(), AuraSounds.AMETHYST_BLOCK_CHIME.value(), SoundSource.PLAYERS, 0.8f, 1.6f);
	}

	/** Light and slow: per layer a snowflake turning around the body (each layer its own height and way), a white speck, now and then a faint glint. */
	private static void flakes(ServerPlayer player, Shell s, long now) {
		ServerLevel level = player.serverLevel();
		for (int layer = 0; layer < s.layers; layer++) {
			double r = 0.6 + 0.12 * layer;
			double dir = layer % 2 == 0 ? 1 : -1;
			double a = dir * now * (0.12 - 0.02 * layer) + layer * 2.1;
			double y = player.getY() + 0.35 + 1.3 * ((layer + 0.5) / Math.max(1, maxLayers())) + 0.2 * Math.sin(now * 0.05 + layer);
			send(level, player, ParticleTypes.SNOWFLAKE, player.getX() + Math.cos(a) * r, y, player.getZ() + Math.sin(a) * r);
		}
		double a = -now * 0.1 + Math.PI / 2;
		send(level, player, AuraFx.dust(FLAKE_WHITE, 0.6f), player.getX() + Math.cos(a) * 0.65,
				player.getY() + 1.0 + 0.4 * Math.sin(now * 0.07), player.getZ() + Math.sin(a) * 0.65);
		if (now % 16 == 0) {
			double g = now * 0.15;
			send(level, player, ParticleTypes.END_ROD, player.getX() + Math.cos(g) * 0.7, player.getY() + 0.4 + (now % 48) / 32.0,
					player.getZ() + Math.sin(g) * 0.7);
		}
	}

	/** One speck, standing still, to everyone near enough except the player themselves. */
	private static void send(ServerLevel level, ServerPlayer self, ParticleOptions particle, double x, double y, double z) {
		for (ServerPlayer p : level.players()) {
			if (p != self) AuraFx.particles(level, p, particle, false, x, y, z, 0, 0, 0, 0, 0);
		}
	}

	/** Dying breaks the shell for good: the next life starts with a whole one (called on respawn; the arena cancels its deaths, so knockouts there keep it). */
	public static void onDeath(ServerPlayer player) {
		SHELLS.remove(player.getUUID());
	}

	/** The shell stays as it was through a relog (no relogging for a fresh one); the HUD is told again after. */
	public static void onLogout(ServerPlayer player) {
		Shell s = SHELLS.get(player.getUUID());
		if (s != null) s.sent = "";
	}
}
