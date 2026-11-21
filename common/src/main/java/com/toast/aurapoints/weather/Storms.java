package com.toast.aurapoints.weather;

import com.toast.aurapoints.ability.AuraFx;
import com.toast.aurapoints.ability.AuraType;
import com.toast.aurapoints.ability.Reactions;
import com.toast.aurapoints.config.AuraConfig;
import com.toast.aurapoints.data.AuraState;
import com.toast.aurapoints.network.StormPacket;
import com.toast.aurapoints.shim.neoforge.network.PacketDistributor;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.block.BonemealableBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Local storms (5.39.0): a few drifting weather cells in the overworld, one per aura type (StormType).
 * Inside one, the matching aura deals more damage and gets shorter cooldowns, everything alive carries the
 * storm's reaction mark, and each storm has a small world effect. Saved in AuraState (storms survive a
 * restart). The client draws them from {@link StormPacket} (fog, sky tint, particles; client/StormFx).
 */
public final class Storms {

	private Storms() {
	}

	public static final class Storm {
		public final int id;
		public final StormType type;
		public double x, z, vx, vz;
		public final int radius;
		/** Overworld game times. */
		public final long born, dies;

		public Storm(int id, StormType type, double x, double z, double vx, double vz, int radius, long born, long dies) {
			this.id = id;
			this.type = type;
			this.x = x;
			this.z = z;
			this.vx = vx;
			this.vz = vz;
			this.radius = radius;
			this.born = born;
			this.dies = dies;
		}

		/** 0..1: fades in after it forms and out before it ends. */
		public double strength(long now) {
			double fade = Math.max(1, AuraConfig.STORM_FADE_SECONDS * 20.0);
			return Math.max(0, Math.min(1, Math.min((now - born) / fade, (dies - now) / fade)));
		}

		/** 0..1 at a spot: full inside 80% of the radius, falling to 0 at the edge. */
		public double strengthAt(double px, double pz, long now) {
			double d = Math.sqrt((px - x) * (px - x) + (pz - z) * (pz - z));
			if (d >= radius) return 0;
			double edge = d <= radius * 0.8 ? 1 : (radius - d) / (radius * 0.2);
			return edge * strength(now);
		}

		CompoundTag write() {
			CompoundTag t = new CompoundTag();
			t.putInt("id", id);
			t.putString("type", type.id());
			t.putDouble("x", x);
			t.putDouble("z", z);
			t.putDouble("vx", vx);
			t.putDouble("vz", vz);
			t.putInt("r", radius);
			t.putLong("born", born);
			t.putLong("dies", dies);
			return t;
		}

		static Storm read(CompoundTag t) {
			StormType type = StormType.byId(t.getString("type"));
			if (type == null) return null;
			return new Storm(t.getInt("id"), type, t.getDouble("x"), t.getDouble("z"), t.getDouble("vx"), t.getDouble("vz"), t.getInt("r"), t.getLong("born"), t.getLong("dies"));
		}
	}

	/** The storms, as AuraState holds them (it saves and loads them through {@link #write}/{@link #read}). */
	static final List<Storm> STORMS = new ArrayList<>();
	/** player -> the storm id they were in last second (for the "you enter" line). */
	private static final Map<UUID, Integer> INSIDE = new HashMap<>();
	/** player -> the storm list last sent to their client (unchanged lists aren't sent again). */
	private static final Map<UUID, String> SENT = new HashMap<>();

	public static List<Storm> all() {
		return List.copyOf(STORMS);
	}

	/** Dev test: a storm as given. */
	public static void devAdd(Storm s) {
		STORMS.add(s);
	}

	public static ListTag write() {
		ListTag list = new ListTag();
		for (Storm s : STORMS) list.add(s.write());
		return list;
	}

	public static void read(ListTag list) {
		STORMS.clear();
		for (int i = 0; i < list.size(); i++) {
			Storm s = Storm.read(list.getCompound(i));
			if (s != null) STORMS.add(s);
		}
	}

	public static void read(CompoundTag root, String key) {
		read(root.getList(key, Tag.TAG_COMPOUND));
	}

	// ---------------------------------------------------------------
	// Asking
	// ---------------------------------------------------------------

	/** The strongest storm at the entity's spot (overworld only), or null. */
	public static Storm at(LivingEntity e) {
		if (e.level().isClientSide() || e.level().dimension() != net.minecraft.world.level.Level.OVERWORLD || STORMS.isEmpty()) return null;
		long now = e.level().getGameTime();
		Storm best = null;
		double bestS = 0;
		for (Storm s : STORMS) {
			double v = s.strengthAt(e.getX(), e.getZ(), now);
			if (v > bestS) {
				best = s;
				bestS = v;
			}
		}
		return best;
	}

	private static double strengthFor(LivingEntity e, Storm s) {
		return s == null ? 0 : s.strengthAt(e.getX(), e.getZ(), e.level().getGameTime());
	}

	/** Reactions.Mark.environment: the storm here puts this mark on everything inside. */
	public static boolean gives(LivingEntity e, Reactions.Mark mark) {
		if (!AuraConfig.STORM_ENABLED) return false;
		Storm s = at(e);
		return s != null && s.type.mark == mark && strengthFor(e, s) >= 0.3;
	}

	/** 0..1 boost for this player's aura from the storm they stand in. */
	private static double boost(ServerPlayer player) {
		if (!AuraConfig.STORM_ENABLED || player.getServer() == null) return 0;
		Storm s = at(player);
		if (s == null) return 0;
		AuraType aura = AuraState.get(player.getServer()).getOrCreate(player.getUUID()).aura;
		return aura == s.type.aura ? strengthFor(player, s) : 0;
	}

	/** AuraPoints.applyAttackerBonuses: damage raised for the storm's aura inside it. */
	public static float damage(ServerPlayer attacker, float amount) {
		double b = boost(attacker);
		return b <= 0 ? amount : (float) (amount * (1 + AuraConfig.STORM_DAMAGE_BONUS * b));
	}

	/** AuraAbilities cooldown chain: shorter for the storm's aura inside it. */
	public static int cooldown(ServerPlayer player, int ticks) {
		double b = boost(player);
		return b <= 0 ? ticks : (int) Math.round(ticks * (1 - Math.min(0.9, AuraConfig.STORM_COOLDOWN_CUT * b)));
	}

	// ---------------------------------------------------------------
	// Ticking
	// ---------------------------------------------------------------

	public static void tickAll(MinecraftServer server) {
		ServerLevel level = server.overworld();
		long now = level.getGameTime();
		if (!AuraConfig.STORM_ENABLED) {
			if (!STORMS.isEmpty() && now % 20 == 0) {
				STORMS.clear();
				dirty(server);
				sync(server, level);
			}
			return;
		}
		if (now % 20 != 0) return;
		AuraState state = AuraState.get(server);
		boolean changed = false;
		// move, end
		for (var it = STORMS.iterator(); it.hasNext(); ) {
			Storm s = it.next();
			if (now >= s.dies) {
				it.remove();
				changed = true;
				continue;
			}
			s.x += s.vx;
			s.z += s.vz;
		}
		// a new one now and then, near someone in the overworld
		List<ServerPlayer> players = level.players().stream().filter(p -> !p.isSpectator()).toList();
		if (state.nextStormAt == 0) state.nextStormAt = now + interval(level);
		if (!players.isEmpty() && now >= state.nextStormAt) {
			state.nextStormAt = now + interval(level);
			if (STORMS.size() < AuraConfig.STORM_MAX) {
				ServerPlayer near = players.get(level.random.nextInt(players.size()));
				StormType type = roll(level);
				if (type != null) {
					double a = level.random.nextDouble() * Math.PI * 2, d = AuraConfig.STORM_SPAWN_MIN + level.random.nextDouble() * Math.max(0, AuraConfig.STORM_SPAWN_MAX - AuraConfig.STORM_SPAWN_MIN);
					spawn(level, type, near.getX() + Math.cos(a) * d, near.getZ() + Math.sin(a) * d);
				}
			}
			changed = true;
		}
		if (changed) dirty(server);
		// what each storm does to the players and mobs inside
		for (ServerPlayer p : players) effects(level, p, now);
		if (now % 40 == 0) sync(server, level);
	}

	private static long interval(ServerLevel level) {
		double base = AuraConfig.STORM_INTERVAL_MINUTES * 1200.0;
		return Math.max(200, Math.round(base * (0.75 + level.random.nextDouble() * 0.5)));
	}

	private static StormType roll(ServerLevel level) {
		List<StormType> pool = new ArrayList<>();
		for (StormType t : StormType.values()) if (!t.nightOnly || level.isNight()) pool.add(t);
		return pool.isEmpty() ? null : pool.get(level.random.nextInt(pool.size()));
	}

	/** Starts a storm at x/z, drifting in a random direction. */
	public static Storm spawn(ServerLevel level, StormType type, double x, double z) {
		long now = level.getGameTime();
		double a = level.random.nextDouble() * Math.PI * 2;
		double speed = AuraConfig.STORM_SPEED_MIN + level.random.nextDouble() * Math.max(0, AuraConfig.STORM_SPEED_MAX - AuraConfig.STORM_SPEED_MIN);
		int id = 1;
		for (Storm s : STORMS) id = Math.max(id, s.id + 1);
		Storm s = new Storm(id, type, x, z, Math.cos(a) * speed, Math.sin(a) * speed, AuraConfig.STORM_RADIUS, now, now + AuraConfig.STORM_MINUTES * 1200L);
		STORMS.add(s);
		dirty(level.getServer());
		sync(level.getServer(), level);
		Component line = Component.literal("☁ A " + type.displayName + " is forming " + direction(level, x, z)).withStyle(s2 -> s2.withColor(TextColor.fromRgb(type.aura.rgb)))
				.append(Component.literal("  (" + type.aura.displayName + " +" + Math.round(AuraConfig.STORM_DAMAGE_BONUS * 100) + "% inside)").withStyle(ChatFormatting.GRAY));
		for (ServerPlayer p : level.players()) p.sendSystemMessage(line);
		return s;
	}

	private static String direction(ServerLevel level, double x, double z) {
		return "around x " + Math.round(x / 10) * 10 + ", z " + Math.round(z / 10) * 10;
	}

	private static void dirty(MinecraftServer server) {
		AuraState.get(server).markDirty();
	}

	private static void effects(ServerLevel level, ServerPlayer p, long now) {
		Storm s = at(p);
		Integer was = INSIDE.get(p.getUUID());
		int id = s == null ? 0 : s.id;
		if (was == null || was != id) {
			if (s != null) {
				p.displayClientMessage(Component.literal("You enter a " + s.type.displayName + " · " + s.type.aura.displayName + " +"
						+ Math.round(AuraConfig.STORM_DAMAGE_BONUS * 100) + "% damage, -" + Math.round(AuraConfig.STORM_COOLDOWN_CUT * 100) + "% cooldowns")
						.withStyle(st -> st.withColor(TextColor.fromRgb(s.type.aura.rgb))), true);
			}
			INSIDE.put(p.getUUID(), id);
		}
		if (s == null) return;
		double str = strengthFor(p, s);
		if (str < 0.3) return;
		var r = level.random;
		switch (s.type) {
			case THUNDERHEAD -> {
				if (r.nextDouble() < AuraConfig.STORM_LIGHTNING_CHANCE * str) {
					LightningBolt bolt = EntityType.LIGHTNING_BOLT.create(level);
					if (bolt != null) {
						double a = r.nextDouble() * Math.PI * 2, d = 16 + r.nextDouble() * 32;
						BlockPos at = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, BlockPos.containing(p.getX() + Math.cos(a) * d, 0, p.getZ() + Math.sin(a) * d));
						bolt.moveTo(Vec3.atBottomCenterOf(at));
						bolt.setVisualOnly(true);
						level.addFreshEntity(bolt);
					}
				}
			}
			case BLIZZARD -> {
				// freezing builds slowly but never reaches the damage point
				for (LivingEntity e : around(level, p, 24)) {
					if (e.canFreeze()) e.setTicksFrozen(Math.min(AuraConfig.STORM_FREEZE_CAP, e.getTicksFrozen() + 4));
				}
			}
			case HEAT_HAZE -> {
				for (LivingEntity e : around(level, p, 24)) if (e instanceof Enemy) Reactions.applyMark(e, Reactions.Mark.BURNING);
			}
			case GALE -> {
				double len = Math.max(0.01, Math.sqrt(s.vx * s.vx + s.vz * s.vz));
				double push = AuraConfig.STORM_GALE_PUSH * str;
				for (LivingEntity e : around(level, p, 24)) {
					if (e instanceof ServerPlayer sp && (sp.isCreative() || sp.isSpectator() || sp.isFallFlying())) continue;
					e.push(s.vx / len * push, 0, s.vz / len * push);
					e.hurtMarked = true;
				}
			}
			case SPORE_BLOOM -> {
				for (int k = 0; k < AuraConfig.STORM_SPORE_GROWTH; k++) {
					BlockPos at = p.blockPosition().offset(r.nextInt(17) - 8, r.nextInt(5) - 2, r.nextInt(17) - 8);
					BlockState st = level.getBlockState(at);
					if (st.getBlock() instanceof CropBlock crop && st.getBlock() instanceof BonemealableBlock b && b.isValidBonemealTarget(level, at, st)) {
						b.performBonemeal(level, r, at, st);
						AuraFx.particles(level, net.minecraft.core.particles.ParticleTypes.HAPPY_VILLAGER, at.getX() + 0.5, at.getY() + 0.6, at.getZ() + 0.5, 4, 0.3, 0.2, 0.3, 0);
					}
				}
				for (LivingEntity e : around(level, p, 24)) if (e instanceof Enemy) Reactions.applyMark(e, Reactions.Mark.POISONED);
			}
			case AURORA -> {
				if (now % 60 == 0) p.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 70, 0, true, false));
			}
			case UMBRA -> {
				for (LivingEntity e : around(level, p, 40)) {
					if (e instanceof Mob m && m.getTarget() != null && m.distanceTo(m.getTarget()) > AuraConfig.STORM_UMBRA_SIGHT) m.setTarget(null);
				}
			}
			default -> {
			}
		}
	}

	private static List<LivingEntity> around(ServerLevel level, ServerPlayer p, double r) {
		return level.getEntitiesOfClass(LivingEntity.class, new AABB(p.getX() - r, p.getY() - 12, p.getZ() - r, p.getX() + r, p.getY() + 12, p.getZ() + r));
	}

	/** The storms near each overworld player, for their client (an empty list clears them). */
	public static void sync(MinecraftServer server, ServerLevel level) {
		long now = level.getGameTime();
		for (ServerPlayer p : level.players()) {
			StringBuilder sb = new StringBuilder();
			for (Storm s : STORMS) {
				double d = Math.sqrt((p.getX() - s.x) * (p.getX() - s.x) + (p.getZ() - s.z) * (p.getZ() - s.z));
				if (d > s.radius + 400) continue;
				if (!sb.isEmpty()) sb.append(';');
				sb.append(s.id).append(',').append(s.type.id()).append(',').append(Math.round(s.x)).append(',').append(Math.round(s.z)).append(',')
						.append(s.radius).append(',').append(String.format(java.util.Locale.ROOT, "%.2f", s.strength(now)))
						.append(',').append(String.format(java.util.Locale.ROOT, "%.2f", s.vx)).append(',').append(String.format(java.util.Locale.ROOT, "%.2f", s.vz));
			}
			String wire = sb.toString();
			if (wire.equals(SENT.put(p.getUUID(), wire))) continue;
			PacketDistributor.sendToPlayer(p, new StormPacket(wire));
		}
	}

	/** Ends every storm (owner command). */
	public static int clear(MinecraftServer server) {
		int n = STORMS.size();
		STORMS.clear();
		dirty(server);
		sync(server, server.overworld());
		return n;
	}

	public static void forget(UUID id) {
		INSIDE.remove(id);
		SENT.remove(id);
	}

	// ---------------------------------------------------------------
	// /aura storm
	// ---------------------------------------------------------------

	public static void register(com.mojang.brigadier.CommandDispatcher<net.minecraft.commands.CommandSourceStack> dispatcher) {
		var types = (com.mojang.brigadier.suggestion.SuggestionProvider<net.minecraft.commands.CommandSourceStack>) (ctx, b) -> {
			for (StormType t : StormType.values()) b.suggest(t.id());
			return b.buildFuture();
		};
		dispatcher.register(net.minecraft.commands.Commands.literal("aura").then(net.minecraft.commands.Commands.literal("storm")
				.executes(ctx -> where(ctx.getSource()))
				.then(net.minecraft.commands.Commands.literal("where").executes(ctx -> where(ctx.getSource())))
				.then(net.minecraft.commands.Commands.literal("clear").executes(ctx -> {
					int n = clear(ctx.getSource().getServer());
					ctx.getSource().sendSuccess(() -> Component.literal(n + " storm(s) ended."), true);
					return n;
				}))
				.then(net.minecraft.commands.Commands.literal("spawn").then(net.minecraft.commands.Commands.argument("type", com.mojang.brigadier.arguments.StringArgumentType.word()).suggests(types)
						.executes(ctx -> {
							StormType t = StormType.byId(com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "type"));
							if (t == null) {
								ctx.getSource().sendFailure(Component.literal("No such storm."));
								return 0;
							}
							Vec3 at = ctx.getSource().getPosition();
							spawn(ctx.getSource().getServer().overworld(), t, at.x, at.z);
							ctx.getSource().sendSuccess(() -> Component.literal(t.displayName + " started here."), true);
							return 1;
						})))));
	}

	/** Where the storms are, roughly, from the asker. */
	private static int where(net.minecraft.commands.CommandSourceStack src) {
		long now = src.getServer().overworld().getGameTime();
		if (STORMS.isEmpty()) {
			src.sendSuccess(() -> Component.literal("No storms right now.").withStyle(ChatFormatting.GRAY), false);
			return 0;
		}
		Vec3 from = src.getPosition();
		for (Storm s : STORMS) {
			double dx = s.x - from.x, dz = s.z - from.z;
			int dist = (int) Math.round(Math.sqrt(dx * dx + dz * dz));
			String dir = compass(dx, dz);
			int mins = (int) Math.max(0, (s.dies - now) / 1200);
			src.sendSuccess(() -> Component.literal("☁ " + s.type.displayName).withStyle(st -> st.withColor(TextColor.fromRgb(s.type.aura.rgb)))
					.append(Component.literal("  " + (dist <= s.radius ? "you're in it" : dist + " blocks " + dir) + " · " + s.type.aura.displayName
							+ " +" + Math.round(AuraConfig.STORM_DAMAGE_BONUS * 100) + "% · ~" + mins + " min left").withStyle(ChatFormatting.GRAY)), false);
		}
		return STORMS.size();
	}

	private static String compass(double dx, double dz) {
		String[] names = {"east", "south-east", "south", "south-west", "west", "north-west", "north", "north-east"};
		double a = Math.toDegrees(Math.atan2(dz, dx));
		int i = (int) Math.round(((a % 360) + 360) % 360 / 45.0) % 8;
		return names[i];
	}

	/** World unload / server stop: nothing static survives into the next world. */
	public static void clearAll() {
		STORMS.clear();
		INSIDE.clear();
		SENT.clear();
	}
}
