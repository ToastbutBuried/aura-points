package com.toast.aurapoints.shrine;

import com.toast.aurapoints.AuraCurrency;
import com.toast.aurapoints.AuraPoints;
import com.toast.aurapoints.ability.Allies;
import com.toast.aurapoints.ability.AuraFx;
import com.toast.aurapoints.ability.AuraType;
import com.toast.aurapoints.ability.Relics;
import com.toast.aurapoints.config.AuraConfig;
import com.toast.aurapoints.data.AuraState;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Territory shrines (5.42.0). One ruined shrine waits in each SHRINE_SPACING x SHRINE_SPACING region of the
 * overworld (a seeded spot; built the first time a player comes near, skipped on water). Bring the repair
 * materials to its core to rebuild it; then channel at the core (and pay Aura) to attune it to your FTB team and
 * your aura. An owned shrine gives its team a small damage bonus nearby (more for its own aura, and
 * Regeneration while it is well powered). Its power fades by the day and is topped up with Aura; a rival
 * standing at the core drains it, and at 0 it turns neutral for anyone to take. Owners can also place one.
 */
public final class Shrines {

	private Shrines() {
	}

	public static final String TAG = "aurapoints_shrine";
	private static final int CORE_RANGE = 3, DRAIN_RANGE = 6;

	public static final class Shrine {
		public final int id;
		public final BlockPos core;
		public boolean ruined;
		public UUID team;
		public String teamName = "";
		public AuraType aura;
		public double power;
		Display.ItemDisplay crystal;
		Display.TextDisplay label;
		long warnedAt = -100000;

		Shrine(int id, BlockPos core, boolean ruined) {
			this.id = id;
			this.core = core;
			this.ruined = ruined;
		}

		public boolean owned() {
			return team != null && aura != null && power > 0;
		}

		Vec3 top() {
			return Vec3.atBottomCenterOf(core).add(0, 1.2, 0);
		}

		CompoundTag write() {
			CompoundTag t = new CompoundTag();
			t.putInt("id", id);
			t.putLong("core", core.asLong());
			t.putBoolean("ruined", ruined);
			if (team != null) t.putUUID("team", team);
			t.putString("teamName", teamName);
			if (aura != null) t.putString("aura", aura.id());
			t.putDouble("power", power);
			return t;
		}

		static Shrine read(CompoundTag t) {
			Shrine s = new Shrine(t.getInt("id"), BlockPos.of(t.getLong("core")), t.getBoolean("ruined"));
			if (t.hasUUID("team")) s.team = t.getUUID("team");
			s.teamName = t.getString("teamName");
			s.aura = t.contains("aura") ? AuraType.byId(t.getString("aura")) : null;
			s.power = t.getDouble("power");
			return s;
		}
	}

	static final List<Shrine> SHRINES = new ArrayList<>();
	/** Regions already looked at (a shrine built there, or no fitting spot). */
	static final Set<Long> SITES = new HashSet<>();
	/** player -> [shrine id, channel start tick] */
	private static final Map<UUID, long[]> CHANNELS = new HashMap<>();

	public static List<Shrine> all() {
		return List.copyOf(SHRINES);
	}

	// ---------------------------------------------------------------
	// Saving (AuraState)
	// ---------------------------------------------------------------

	public static void write(CompoundTag root) {
		ListTag list = new ListTag();
		for (Shrine s : SHRINES) list.add(s.write());
		root.put("shrines", list);
		ListTag sites = new ListTag();
		for (long k : SITES) sites.add(LongTag.valueOf(k));
		root.put("shrineSites", sites);
	}

	public static void read(CompoundTag root) {
		SHRINES.clear();
		SITES.clear();
		ListTag list = root.getList("shrines", Tag.TAG_COMPOUND);
		for (int i = 0; i < list.size(); i++) SHRINES.add(Shrine.read(list.getCompound(i)));
		ListTag sites = root.getList("shrineSites", Tag.TAG_LONG);
		for (int i = 0; i < sites.size(); i++) SITES.add(((LongTag) sites.get(i)).getAsLong());
	}

	private static void dirty(MinecraftServer server) {
		AuraState.get(server).markDirty();
	}

	// ---------------------------------------------------------------
	// Sites and building
	// ---------------------------------------------------------------

	private static long region(int x, int z) {
		int s = Math.max(64, AuraConfig.SHRINE_SPACING);
		return ((long) Math.floorDiv(x, s) << 32) ^ (Math.floorDiv(z, s) & 0xffffffffL);
	}

	/** The seeded spot of the region a position is in. */
	private static BlockPos site(ServerLevel level, int x, int z) {
		int s = Math.max(64, AuraConfig.SHRINE_SPACING);
		int rx = Math.floorDiv(x, s), rz = Math.floorDiv(z, s);
		RandomSource r = RandomSource.create(level.getSeed() ^ (rx * 341873128712L + rz * 132897987541L) ^ 0x5A17E5L);
		int margin = s / 6;
		return new BlockPos(rx * s + margin + r.nextInt(s - 2 * margin), 0, rz * s + margin + r.nextInt(s - 2 * margin));
	}

	private static void lookForSites(ServerLevel level, ServerPlayer p) {
		BlockPos site = site(level, p.getBlockX(), p.getBlockZ());
		long key = region(site.getX(), site.getZ());
		if (SITES.contains(key)) return;
		double dx = site.getX() - p.getX(), dz = site.getZ() - p.getZ();
		if (dx * dx + dz * dz > 112 * 112 || !level.isLoaded(site)) return;
		SITES.add(key);
		dirty(level.getServer());
		BlockPos top = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, site);
		if (!level.getFluidState(top.below()).isEmpty() || !level.getFluidState(top).isEmpty() || top.getY() < level.getSeaLevel() - 2) return;
		if (lived(level, top.above())) return;
		place(level, top.above(), true, null);
	}

	/** Players spend this long near a chunk (summed per player) before it counts as lived in. */
	private static final long LIVED_TICKS = 3 * 60 * 20;

	/**
	 * Ground a ruin must not be built on: a chunk it touches where players have spent a while (their
	 * builds and farms), or any block with a block entity (chest, furnace, sign, bed...) in the space
	 * {@link #build} clears or fills.
	 */
	static boolean lived(ServerLevel level, BlockPos core) {
		for (int dx = -4; dx <= 4; dx += 8) {
			for (int dz = -4; dz <= 4; dz += 8) {
				if (level.getChunkAt(core.offset(dx, 0, dz)).getInhabitedTime() > LIVED_TICKS) return true;
			}
		}
		for (BlockPos b : BlockPos.betweenClosed(core.offset(-4, -4, -4), core.offset(4, 5, 4))) {
			if (level.getBlockEntity(b) != null) return true;
		}
		return false;
	}

	/** Builds a shrine with its core at {@code core} (ruined or whole) and registers it. */
	public static Shrine place(ServerLevel level, BlockPos core, boolean ruined, AuraType aura) {
		int id = 1;
		for (Shrine s : SHRINES) id = Math.max(id, s.id + 1);
		Shrine s = new Shrine(id, core, ruined);
		if (aura != null && !ruined) s.aura = null;
		SHRINES.add(s);
		build(level, s);
		dirty(level.getServer());
		return s;
	}

	private static void set(ServerLevel level, BlockPos p, Block b) {
		level.setBlock(p, b.defaultBlockState(), 2);
	}

	/** The shrine's blocks: a 9x9 platform, four pillars, the core in the middle; broken and mossy while ruined. */
	static void build(ServerLevel level, Shrine s) {
		RandomSource r = RandomSource.create(s.core.asLong());
		BlockPos c = s.core;
		int y0 = c.getY() - 1;
		for (int dx = -4; dx <= 4; dx++) {
			for (int dz = -4; dz <= 4; dz++) {
				BlockPos floor = new BlockPos(c.getX() + dx, y0, c.getZ() + dz);
				for (int up = 1; up <= 6; up++) set(level, floor.above(up), Blocks.AIR);
				for (int down = 1; down <= 3; down++) {
					BlockPos b = floor.below(down);
					if (level.getBlockState(b).isAir() || !level.getFluidState(b).isEmpty()) set(level, b, Blocks.STONE_BRICKS);
				}
				boolean edge = Math.abs(dx) == 4 || Math.abs(dz) == 4;
				if (s.ruined) {
					if (edge && r.nextInt(3) == 0) set(level, floor, Blocks.COBBLESTONE);
					else set(level, floor, r.nextInt(3) == 0 ? Blocks.MOSSY_STONE_BRICKS : r.nextInt(2) == 0 ? Blocks.CRACKED_STONE_BRICKS : Blocks.STONE_BRICKS);
				} else {
					set(level, floor, edge ? Blocks.POLISHED_ANDESITE : (Math.abs(dx) + Math.abs(dz)) % 2 == 0 ? Blocks.STONE_BRICKS : Blocks.CHISELED_STONE_BRICKS);
				}
			}
		}
		for (int[] o : new int[][]{{3, 3}, {-3, 3}, {3, -3}, {-3, -3}}) {
			BlockPos base = new BlockPos(c.getX() + o[0], c.getY(), c.getZ() + o[1]);
			int h = s.ruined ? 1 + r.nextInt(2) : 4;
			for (int k = 0; k < h; k++) set(level, base.above(k), s.ruined && k == h - 1 ? Blocks.CRACKED_STONE_BRICKS : Blocks.STONE_BRICK_WALL);
			if (!s.ruined) set(level, base.above(4), Blocks.SEA_LANTERN);
		}
		set(level, c, s.ruined ? Blocks.CHISELED_STONE_BRICKS : Blocks.LODESTONE);
	}

	// ---------------------------------------------------------------
	// Using the core
	// ---------------------------------------------------------------

	public static Shrine at(BlockPos core) {
		for (Shrine s : SHRINES) if (s.core.equals(core)) return s;
		return null;
	}

	/** A right click on a block: true if it was a shrine core (and the click was used). */
	public static boolean onUse(ServerPlayer player, BlockPos pos) {
		if (!AuraConfig.SHRINE_ENABLED || player.level().dimension() != Level.OVERWORLD) return false;
		Shrine s = at(pos);
		if (s == null) return false;
		ServerLevel level = player.serverLevel();
		if (s.ruined) {
			repair(level, player, s);
			return true;
		}
		Allies.TeamInfo team = Allies.teamOf(player);
		if (!s.owned()) {
			var aura = AuraState.get(player.getServer()).getOrCreate(player.getUUID()).aura;
			if (aura == null) {
				player.sendSystemMessage(Component.literal("Roll an aura first: a shrine takes its keeper's aura.").withStyle(ChatFormatting.RED));
				return true;
			}
			if (AuraCurrency.get(player) < AuraConfig.SHRINE_POWER_COST) {
				player.sendSystemMessage(Component.literal("Attuning costs " + AuraConfig.SHRINE_POWER_COST + " Aura.").withStyle(ChatFormatting.RED));
				return true;
			}
			CHANNELS.put(player.getUUID(), new long[]{s.id, level.getGameTime()});
			player.displayClientMessage(Component.literal("Channelling... stay at the core for " + AuraConfig.SHRINE_CHANNEL_SECONDS + " s").withStyle(ChatFormatting.AQUA), true);
			return true;
		}
		if (s.team.equals(team.id())) {
			if (s.power >= 100) {
				player.displayClientMessage(Component.literal("The shrine is at full power.").withStyle(ChatFormatting.GRAY), true);
			} else if (AuraCurrency.take(player, AuraConfig.SHRINE_TOPUP_COST)) {
				s.power = Math.min(100, s.power + AuraConfig.SHRINE_TOPUP_POWER);
				dirty(player.getServer());
				player.displayClientMessage(Component.literal("Shrine power " + Math.round(s.power) + "%").withStyle(ChatFormatting.AQUA), true);
				AuraFx.sound(level, player, SoundEvents.BEACON_POWER_SELECT, 0.8f, 1.2f);
			} else {
				player.sendSystemMessage(Component.literal("Topping up costs " + AuraConfig.SHRINE_TOPUP_COST + " Aura.").withStyle(ChatFormatting.RED));
			}
		} else {
			player.sendSystemMessage(Component.literal("This shrine belongs to " + s.teamName + ". Stand at its core to drain it.").withStyle(ChatFormatting.YELLOW));
		}
		return true;
	}

	/** "minecraft:stone_bricks*32,minecraft:gold_ingot*8" */
	private static Map<Item, Integer> repairCost() {
		Map<Item, Integer> out = new java.util.LinkedHashMap<>();
		for (String part : AuraConfig.SHRINE_REPAIR_ITEMS.split(",")) {
			String[] f = part.strip().split("\\*");
			ResourceLocation id = ResourceLocation.tryParse(f[0].strip());
			if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) continue;
			int n = 1;
			try {
				if (f.length > 1) n = Integer.parseInt(f[1].strip());
			} catch (NumberFormatException ignored) {
			}
			out.merge(BuiltInRegistries.ITEM.get(id), Math.max(1, n), Integer::sum);
		}
		return out;
	}

	private static int count(ServerPlayer p, Item item) {
		int n = 0;
		var inv = p.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) if (inv.getItem(i).is(item)) n += inv.getItem(i).getCount();
		return n;
	}

	private static void repair(ServerLevel level, ServerPlayer p, Shrine s) {
		Map<Item, Integer> cost = repairCost();
		List<String> missing = new ArrayList<>();
		for (var e : cost.entrySet()) {
			int have = count(p, e.getKey());
			if (have < e.getValue()) missing.add((e.getValue() - have) + " " + new ItemStack(e.getKey()).getHoverName().getString());
		}
		if (!missing.isEmpty() && !p.isCreative()) {
			p.sendSystemMessage(Component.literal("A ruined shrine. To rebuild it you still need: " + String.join(", ", missing)).withStyle(ChatFormatting.YELLOW));
			return;
		}
		if (!p.isCreative()) {
			for (var e : cost.entrySet()) {
				int left = e.getValue();
				var inv = p.getInventory();
				for (int i = 0; i < inv.getContainerSize() && left > 0; i++) {
					ItemStack st = inv.getItem(i);
					if (!st.is(e.getKey())) continue;
					int take = Math.min(left, st.getCount());
					st.shrink(take);
					left -= take;
				}
			}
		}
		s.ruined = false;
		build(level, s);
		dirty(level.getServer());
		level.sendParticles(ParticleTypes.END_ROD, s.core.getX() + 0.5, s.core.getY() + 1.5, s.core.getZ() + 0.5, 60, 2.5, 1.5, 2.5, 0.05);
		level.playSound(null, s.core, SoundEvents.BEACON_ACTIVATE, SoundSource.BLOCKS, 1.2f, 1f);
		p.sendSystemMessage(Component.literal("✔ The shrine stands again. Right-click its core to attune it to your team (" + AuraConfig.SHRINE_POWER_COST + " Aura).").withStyle(ChatFormatting.GREEN));
	}

	private static void tickChannels(MinecraftServer server, long now) {
		for (var it = CHANNELS.entrySet().iterator(); it.hasNext(); ) {
			var e = it.next();
			ServerPlayer p = player(server, e.getKey());
			Shrine s = byId((int) e.getValue()[0]);
			if (p == null || s == null || s.ruined || s.owned() || p.level().dimension() != Level.OVERWORLD
					|| p.position().distanceToSqr(Vec3.atCenterOf(s.core)) > CORE_RANGE * CORE_RANGE * 4) {
				if (p != null) p.displayClientMessage(Component.literal("Channelling broken").withStyle(ChatFormatting.GRAY), true);
				it.remove();
				continue;
			}
			long left = e.getValue()[1] + AuraConfig.SHRINE_CHANNEL_SECONDS * 20L - now;
			if (left > 0) {
				if (left % 20 == 0) p.displayClientMessage(Component.literal("Channelling... " + (left / 20) + " s").withStyle(ChatFormatting.AQUA), true);
				continue;
			}
			it.remove();
			if (!AuraCurrency.take(p, AuraConfig.SHRINE_POWER_COST)) {
				p.sendSystemMessage(Component.literal("Attuning costs " + AuraConfig.SHRINE_POWER_COST + " Aura.").withStyle(ChatFormatting.RED));
				continue;
			}
			attune(server, s, p);
		}
	}

	static void attune(MinecraftServer server, Shrine s, ServerPlayer p) {
		Allies.TeamInfo team = Allies.teamOf(p);
		s.team = team.id();
		s.teamName = team.name();
		s.aura = AuraState.get(server).getOrCreate(p.getUUID()).aura;
		s.power = 100;
		dirty(server);
		ServerLevel level = server.overworld();
		level.playSound(null, s.core, SoundEvents.BEACON_POWER_SELECT, SoundSource.BLOCKS, 1.2f, 0.9f);
		Component line = Component.literal("⛩ " + team.name() + " attuned a shrine to " + s.aura.displayName).withStyle(st -> st.withColor(TextColor.fromRgb(s.aura.rgb)))
				.append(Component.literal(" near x " + Math.round(s.core.getX() / 10.0) * 10 + ", z " + Math.round(s.core.getZ() / 10.0) * 10).withStyle(ChatFormatting.GRAY));
		for (ServerPlayer pl : server.getPlayerList().getPlayers()) pl.sendSystemMessage(line);
		refreshDisplays(level, s);
	}

	private static Shrine byId(int id) {
		for (Shrine s : SHRINES) if (s.id == id) return s;
		return null;
	}

	/** Dev tests: fake players aren't in the player list. */
	public static final Map<UUID, ServerPlayer> TEST_PLAYERS = new HashMap<>();

	private static ServerPlayer player(MinecraftServer server, UUID id) {
		ServerPlayer p = server.getPlayerList().getPlayer(id);
		return p != null ? p : TEST_PLAYERS.get(id);
	}

	// ---------------------------------------------------------------
	// Ticking: sites, displays, decay, drain, buffs
	// ---------------------------------------------------------------

	public static void tickAll(MinecraftServer server) {
		if (!AuraConfig.SHRINE_ENABLED) return;
		ServerLevel level = server.overworld();
		long now = level.getGameTime();
		tickChannels(server, now);
		if (now % 20 != 0) return;
		List<ServerPlayer> players = level.players().stream().filter(p -> !p.isSpectator()).toList();
		if (now % 100 == 0) for (ServerPlayer p : players) lookForSites(level, p);
		double reach = Math.max(DRAIN_RANGE, AuraConfig.SHRINE_RADIUS);
		for (Shrine s : SHRINES) {
			// decay
			if (s.owned()) {
				s.power -= AuraConfig.SHRINE_DECAY_PER_DAY / 1200.0;
				if (s.power <= 0) neutral(server, s, null);
			}
			boolean near = false;
			for (ServerPlayer p : players) {
				double d = p.position().distanceToSqr(Vec3.atCenterOf(s.core));
				if (d < 64 * 64) near = true;
				// the team lookup (FTB Teams reflection) only for players close enough to drain or be healed
				if (!s.owned() || d > reach * reach) continue;
				Allies.TeamInfo team = Allies.teamOf(p);
				boolean member = s.team.equals(team.id());
				// a rival at the core drains it
				if (!member && d <= DRAIN_RANGE * DRAIN_RANGE && !p.isCreative()
						&& AuraState.get(server).getOrCreate(p.getUUID()).aura != null) {
					drain(server, level, s, p);
				}
				// members nearby: Regeneration while it is well powered
				if (member && d <= AuraConfig.SHRINE_RADIUS * AuraConfig.SHRINE_RADIUS && s.power >= AuraConfig.SHRINE_REGEN_POWER && now % 80 == 0) {
					p.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 90, 0, true, false));
				}
			}
			if (near) {
				refreshDisplays(level, s);
				if (s.owned() && now % 40 == 0) glow(level, s);
			} else removeDisplays(s);
		}
		if (now % 600 == 0) dirty(server);
	}

	private static void drain(MinecraftServer server, ServerLevel level, Shrine s, ServerPlayer rival) {
		boolean ownersOn = false;
		for (ServerPlayer p : server.getPlayerList().getPlayers()) if (s.team.equals(Allies.teamOf(p).id())) ownersOn = true;
		double amount = AuraConfig.SHRINE_DRAIN_PER_SECOND * (ownersOn ? 1 : AuraConfig.SHRINE_OFFLINE_DRAIN_SHARE);
		s.power = Math.max(0, s.power - amount);
		rival.displayClientMessage(Component.literal("Draining " + s.teamName + "'s shrine: " + Math.round(s.power) + "%").withStyle(ChatFormatting.YELLOW), true);
		level.sendParticles(ParticleTypes.REVERSE_PORTAL, s.core.getX() + 0.5, s.core.getY() + 1.2, s.core.getZ() + 0.5, 6, 0.4, 0.4, 0.4, 0.02);
		if (level.getGameTime() - s.warnedAt > 600) {
			s.warnedAt = level.getGameTime();
			for (ServerPlayer p : server.getPlayerList().getPlayers()) {
				if (!s.team.equals(Allies.teamOf(p).id())) continue;
				p.sendSystemMessage(Component.literal("⚠ " + rival.getGameProfile().getName() + " is draining your shrine near x " + s.core.getX() + ", z " + s.core.getZ()
						+ " (" + Math.round(s.power) + "%)").withStyle(ChatFormatting.RED));
			}
		}
		if (s.power <= 0) neutral(server, s, rival);
		dirty(server);
	}

	private static void neutral(MinecraftServer server, Shrine s, ServerPlayer by) {
		String was = s.teamName;
		s.team = null;
		s.teamName = "";
		s.aura = null;
		s.power = 0;
		dirty(server);
		if (by != null) {
			Component line = Component.literal("⛩ " + by.getGameProfile().getName() + " broke " + was + "'s hold on a shrine - it is free to take").withStyle(ChatFormatting.GOLD);
			for (ServerPlayer p : server.getPlayerList().getPlayers()) p.sendSystemMessage(line);
		}
		refreshDisplays(server.overworld(), s);
	}

	private static void glow(ServerLevel level, Shrine s) {
		int rgb = s.aura.rgb;
		var dust = new DustParticleOptions(new Vector3f(((rgb >> 16) & 255) / 255f, ((rgb >> 8) & 255) / 255f, (rgb & 255) / 255f), 1.4f);
		Vec3 t = s.top();
		for (int k = 0; k < 12; k++) {
			double a = k / 12.0 * Math.PI * 2;
			level.sendParticles(dust, t.x + Math.cos(a) * 1.2, t.y + 0.6, t.z + Math.sin(a) * 1.2, 1, 0, 0.3, 0, 0);
		}
	}

	/** AuraPoints.applyAttackerBonuses: the team's bonus near an owned shrine. */
	public static float damage(ServerPlayer attacker, float amount) {
		if (!AuraConfig.SHRINE_ENABLED || amount <= 0 || attacker.level().dimension() != Level.OVERWORLD || SHRINES.isEmpty()) return amount;
		Allies.TeamInfo team = null;
		double best = 0;
		for (Shrine s : SHRINES) {
			if (!s.owned() || attacker.position().distanceToSqr(Vec3.atCenterOf(s.core)) > AuraConfig.SHRINE_RADIUS * AuraConfig.SHRINE_RADIUS) continue;
			if (team == null) team = Allies.teamOf(attacker);
			if (!s.team.equals(team.id())) continue;
			var aura = AuraState.get(attacker.getServer()).getOrCreate(attacker.getUUID()).aura;
			best = Math.max(best, AuraConfig.SHRINE_TEAM_DAMAGE + (aura == s.aura ? AuraConfig.SHRINE_AURA_DAMAGE : 0));
		}
		return best <= 0 ? amount : (float) (amount * (1 + best));
	}

	// ---------------------------------------------------------------
	// Displays: the floating relic of its aura and a label
	// ---------------------------------------------------------------

	private static Component label(Shrine s) {
		if (s.ruined) {
			return Component.literal("RUINED SHRINE").withStyle(ChatFormatting.GRAY, ChatFormatting.BOLD)
					.append(Component.literal("\nright-click the core with\n" + AuraConfig.SHRINE_REPAIR_ITEMS.replace("minecraft:", "").replace("_", " ").replace(",", ", ")).withStyle(ChatFormatting.DARK_GRAY));
		}
		if (!s.owned()) {
			return Component.literal("UNCLAIMED SHRINE").withStyle(ChatFormatting.WHITE, ChatFormatting.BOLD)
					.append(Component.literal("\nright-click the core: attune it (" + AuraConfig.SHRINE_POWER_COST + " Aura)").withStyle(ChatFormatting.GRAY));
		}
		return Component.literal(s.aura.displayName.toUpperCase(java.util.Locale.ROOT) + " SHRINE").withStyle(st -> st.withColor(TextColor.fromRgb(s.aura.rgb)).withBold(true))
				.append(Component.literal("\n" + s.teamName + " · power " + Math.round(s.power) + "%").withStyle(ChatFormatting.GRAY));
	}

	private static void refreshDisplays(ServerLevel level, Shrine s) {
		if (!level.isLoaded(s.core)) return;
		Vec3 t = s.top();
		if (s.label == null || s.label.isRemoved()) {
			s.label = com.toast.aurapoints.arena.Holograms.makePublic(level, t.add(0, 2.2, 0), label(s), 0.9f, 0x60000000);
			if (s.label != null) {
				s.label.addTag(TAG);
				level.addFreshEntity(s.label);
			}
		} else com.toast.aurapoints.arena.Holograms.setTextPublic(s.label, label(s));
		boolean wantCrystal = s.owned();
		if (wantCrystal && (s.crystal == null || s.crystal.isRemoved())) s.crystal = crystal(level, s, t.add(0, 0.8, 0));
		if (!wantCrystal && s.crystal != null) {
			s.crystal.discard();
			s.crystal = null;
		}
	}

	private static void removeDisplays(Shrine s) {
		if (s.label != null) s.label.discard();
		if (s.crystal != null) s.crystal.discard();
		s.label = null;
		s.crystal = null;
	}

	private static Display.ItemDisplay crystal(ServerLevel level, Shrine s, Vec3 at) {
		Display.ItemDisplay d = EntityType.ITEM_DISPLAY.create(level);
		if (d == null) return null;
		CompoundTag t = new CompoundTag();
		t.put("item", Relics.stack(Relics.AURAS.contains(s.aura) ? s.aura : AuraType.LIGHT).save(level.registryAccess()));
		t.putString("billboard", "center");
		CompoundTag light = new CompoundTag();
		light.putInt("sky", 15);
		light.putInt("block", 15);
		t.put("brightness", light);
		CompoundTag tr = new CompoundTag();
		ListTag l = new ListTag();
		for (float f : new float[]{0, 0, 0, 1}) l.add(FloatTag.valueOf(f));
		tr.put("left_rotation", l.copy());
		tr.put("right_rotation", l.copy());
		ListTag zero = new ListTag();
		for (float f : new float[]{0, 0, 0}) zero.add(FloatTag.valueOf(f));
		tr.put("translation", zero);
		ListTag sc = new ListTag();
		for (float f : new float[]{1.6f, 1.6f, 1.6f}) sc.add(FloatTag.valueOf(f));
		tr.put("scale", sc);
		t.put("transformation", tr);
		d.load(t);
		d.moveTo(at.x, at.y, at.z, 0, 0);
		d.addTag(TAG);
		level.addFreshEntity(d);
		return d;
	}

	/** Shrine displays from before a restart (the live ones are re-made when someone comes near). */
	public static boolean isLeftover(Entity e) {
		if (!e.getTags().contains(TAG)) return false;
		for (Shrine s : SHRINES) if (s.label == e || s.crystal == e) return false;
		return true;
	}

	// ---------------------------------------------------------------
	// /aura shrine
	// ---------------------------------------------------------------

	private static Shrine nearest(Vec3 from) {
		Shrine best = null;
		double bd = Double.MAX_VALUE;
		for (Shrine s : SHRINES) {
			double d = from.distanceToSqr(Vec3.atCenterOf(s.core));
			if (d < bd) {
				bd = d;
				best = s;
			}
		}
		return best;
	}

	public static void register(com.mojang.brigadier.CommandDispatcher<net.minecraft.commands.CommandSourceStack> dispatcher) {
		dispatcher.register(net.minecraft.commands.Commands.literal("aura").then(net.minecraft.commands.Commands.literal("shrine")
				.executes(ctx -> info(ctx.getSource()))
				.then(net.minecraft.commands.Commands.literal("list").executes(ctx -> {
					for (Shrine s : SHRINES) {
						ctx.getSource().sendSuccess(() -> Component.literal("#" + s.id + " " + s.core.toShortString() + " " + (s.ruined ? "ruined" : s.owned() ? s.teamName + " " + s.aura.displayName + " " + Math.round(s.power) + "%" : "unclaimed")), false);
					}
					return SHRINES.size();
				}))
				.then(net.minecraft.commands.Commands.literal("place")
						.executes(ctx -> placeCmd(ctx.getSource(), false))
						.then(net.minecraft.commands.Commands.literal("ruined").executes(ctx -> placeCmd(ctx.getSource(), true)))
						.then(net.minecraft.commands.Commands.literal("intact").executes(ctx -> placeCmd(ctx.getSource(), false))))
				.then(net.minecraft.commands.Commands.literal("remove").executes(ctx -> {
					Shrine s = nearest(ctx.getSource().getPosition());
					if (s == null) return 0;
					removeDisplays(s);
					SHRINES.remove(s);
					dirty(ctx.getSource().getServer());
					ctx.getSource().sendSuccess(() -> Component.literal("Shrine #" + s.id + " removed (its blocks stay)."), true);
					return 1;
				}))
				.then(net.minecraft.commands.Commands.literal("power").then(net.minecraft.commands.Commands.argument("n", com.mojang.brigadier.arguments.IntegerArgumentType.integer(0, 100))
						.executes(ctx -> {
							Shrine s = nearest(ctx.getSource().getPosition());
							if (s == null || !s.owned()) return 0;
							s.power = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "n");
							if (s.power <= 0) neutral(ctx.getSource().getServer(), s, null);
							dirty(ctx.getSource().getServer());
							return 1;
						})))
				.then(net.minecraft.commands.Commands.literal("setowner").then(net.minecraft.commands.Commands.argument("player", net.minecraft.commands.arguments.EntityArgument.player())
						.executes(ctx -> {
							Shrine s = nearest(ctx.getSource().getPosition());
							ServerPlayer p = net.minecraft.commands.arguments.EntityArgument.getPlayer(ctx, "player");
							if (s == null || AuraState.get(p.getServer()).getOrCreate(p.getUUID()).aura == null) return 0;
							s.ruined = false;
							build(ctx.getSource().getServer().overworld(), s);
							attune(ctx.getSource().getServer(), s, p);
							return 1;
						})))));
	}

	private static int placeCmd(net.minecraft.commands.CommandSourceStack src, boolean ruined) {
		if (src.getLevel().dimension() != Level.OVERWORLD) {
			src.sendFailure(Component.literal("Shrines stand in the overworld."));
			return 0;
		}
		BlockPos at = BlockPos.containing(src.getPosition()).offset(0, 0, 6);
		Shrine s = place(src.getLevel(), at, ruined, null);
		src.sendSuccess(() -> Component.literal("Shrine #" + s.id + " placed at " + s.core.toShortString() + (ruined ? " (ruined)" : "")), true);
		return 1;
	}

	private static int info(net.minecraft.commands.CommandSourceStack src) {
		Shrine s = nearest(src.getPosition());
		if (s == null) {
			src.sendSuccess(() -> Component.literal("No shrine found yet. Ruined shrines wait in the wild: one every " + AuraConfig.SHRINE_SPACING + " blocks or so.").withStyle(ChatFormatting.GRAY), false);
			return 0;
		}
		int dist = (int) Math.round(Math.sqrt(src.getPosition().distanceToSqr(Vec3.atCenterOf(s.core))));
		src.sendSuccess(() -> Component.literal("⛩ Nearest shrine: x " + s.core.getX() + ", z " + s.core.getZ() + " (" + dist + " blocks) · "
				+ (s.ruined ? "ruined" : s.owned() ? s.teamName + "'s, " + s.aura.displayName + ", " + Math.round(s.power) + "%" : "unclaimed")).withStyle(ChatFormatting.AQUA), false);
		return 1;
	}

	public static void forget(UUID id) {
		CHANNELS.remove(id);
	}

	/** Dev test: the channel finished at once. */
	public static void devAttune(MinecraftServer server, Shrine s, ServerPlayer p) {
		attune(server, s, p);
	}

	public static void devDrain(MinecraftServer server, Shrine s, ServerPlayer rival) {
		drain(server, server.overworld(), s, rival);
	}

	public static void devRepair(ServerLevel level, ServerPlayer p, Shrine s) {
		repair(level, p, s);
	}

	/** Server stopped (after the last save): nothing static survives into the next world. */
	public static void clearAll() {
		SHRINES.clear();
		SITES.clear();
		CHANNELS.clear();
		TEST_PLAYERS.clear();
	}
}
