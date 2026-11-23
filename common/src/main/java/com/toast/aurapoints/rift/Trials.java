package com.toast.aurapoints.rift;

import com.toast.aurapoints.ability.AuraSounds;
import com.toast.aurapoints.ability.AuraFx;
import com.toast.aurapoints.ability.AuraType;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.WallBannerBlock;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The Reliquary's four trials (5.49.0). Each wing hall is an antechamber with a portal into the trial's own arena,
 * built beyond the monument (see {@link #ARENAS}); solving it frees a Relic Shard you carry back through the portal to
 * the altar. Every trial shares the 3-strike ladder ({@link Trial#strike}): 1 the room reacts, 2 a hazard and a
 * partial reset, 3 a full reset with a new layout (and on Hardcore the one who caused it is knocked out).
 * Pool: {@link TrialDig}, {@link TrialEchoes}, {@link TrialGlyph}, {@link TrialPrism}, {@link TrialBells},
 * {@link TrialStatues}; Wind, Light and Thunder rifts always hold {@link TrialRings} (an elytra course) in one wing.
 * <p>
 * Two frames: the wing (antechamber) frame around {@link Trial#c} with {@link Trial#at}, and the arena frame around
 * {@link Trial#a} with {@link Trial#arena}. Both use f = forward (away from the altar), l = to the side, dy from the floor.
 */
final class Trials {

	private Trials() {
	}

	enum Kind {
		DIG, ECHOES, GLYPH, PRISM, BELLS, STATUES, RINGS;

		static final List<Kind> POOL = List.of(DIG, ECHOES, GLYPH, PRISM, BELLS, STATUES);
	}

	static Trial make(Kind kind, Rifts.Run run, int index) {
		return switch (kind) {
			case DIG -> new TrialDig(run, index);
			case ECHOES -> new TrialEchoes(run, index);
			case GLYPH -> new TrialGlyph(run, index);
			case PRISM -> new TrialPrism(run, index);
			case BELLS -> new TrialBells(run, index);
			case STATUES -> new TrialStatues(run, index);
			case RINGS -> new TrialRings(run, index);
		};
	}

	/** The four kinds of a new run: Rings forced in for the sky auras, the rest drawn from the pool. */
	static List<Kind> pick(AuraType aura, RandomSource r) {
		List<Kind> pool = new ArrayList<>(Kind.POOL);
		java.util.Collections.shuffle(pool, new java.util.Random(r.nextLong()));
		List<Kind> out = new ArrayList<>(pool.subList(0, 4));
		if (aura == AuraType.WIND || aura == AuraType.LIGHT || aura == AuraType.THUNDER) out.set(r.nextInt(4), Kind.RINGS);
		return out;
	}

	// ---------------------------------------------------------------
	// Portal Keystones (5.50): what the broken portals need
	// ---------------------------------------------------------------

	static final String KEYSTONE = "aurapoints_keystone";
	static final String[] WING_NAME = {"West", "North-West", "North-East", "East"};

	/** A Keystone of wing {@code index} in run {@code runId}: a glowing amethyst shard in the wing's colour (a plain vanilla item, no client update). */
	static ItemStack makeKeystone(int runId, int index) {
		ItemStack st = new ItemStack(Items.AMETHYST_SHARD);
		CompoundTag tag = new CompoundTag();
		tag.putInt(KEYSTONE, index);
		tag.putInt("run", runId);
		st.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
		int rgb = Reliquary.WING_RGB[Math.max(0, Math.min(3, index))];
		st.set(DataComponents.CUSTOM_NAME, Component.literal("Keystone - " + WING_NAME[index] + " wing").withStyle(c -> c.withColor(rgb).withItalic(false).withBold(true)));
		st.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
		st.set(DataComponents.LORE, new ItemLore(List.of(
				Component.literal("Once the " + WING_NAME[index] + " wing's arch is whole again, rub it alight").withStyle(c -> c.withColor(0xAAAAAA).withItalic(false)),
				Component.literal("on the pedestal beside it (hold right-click, let go in the green).").withStyle(c -> c.withColor(0xAAAAAA).withItalic(false)))));
		return st;
	}

	static boolean isKeystone(ItemStack s) {
		if (!s.is(Items.AMETHYST_SHARD)) return false;
		CustomData d = s.get(DataComponents.CUSTOM_DATA);
		return d != null && d.copyTag().contains(KEYSTONE);
	}

	static int keystoneWing(ItemStack s) {
		CustomData d = s.get(DataComponents.CUSTOM_DATA);
		return d == null ? -1 : d.copyTag().getInt(KEYSTONE);
	}

	static int keystoneRun(ItemStack s) {
		CustomData d = s.get(DataComponents.CUSTOM_DATA);
		return d == null ? -1 : d.copyTag().getInt("run");
	}

	/** Keystones taken back on the way out of the rift. */
	static void purgeKeystones(ServerPlayer p) {
		var inv = p.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) if (isKeystone(inv.getItem(i))) inv.setItem(i, ItemStack.EMPTY);
		if (isKeystone(p.containerMenu.getCarried())) p.containerMenu.setCarried(ItemStack.EMPTY);
		PortalRepair.purge(p);
	}

	/** Elytra and anything else a trial lends out, taken back (Rifts.purge on the way out). */
	static void purgeLent(ServerPlayer p) {
		TrialRings.purgeLent(p);
		TrialBells.purgeBatons(p);
		purgeKeystones(p);
	}

	static final String DISPLAY_TAG = "aurapoints_rift_trial";

	/**
	 * Arena centres relative to the monument origin, per wing (W, NW, NE, E): 330 blocks out, far past the monument's
	 * bounds (x/z +-105) and inside the run slot (x +-512). An arena may use up to {@link Trial#arenaHalf} = 110 around it.
	 */
	static final int[][] ARENAS = {{-330, 0}, {-150, -330}, {150, -330}, {330, 0}};

	/** Run slots whose arena chunks are held loaded. */
	private static final java.util.Map<Integer, Integer> PRELOADED = new java.util.HashMap<>();

	/**
	 * Holds the four arena areas of a run slot loaded (forced region tickets, radius 9 chunks, 12 for auras with the big Skyward Rings sky) so their
	 * chunks generate off the main thread while the gate forms and the party votes; a cold sync load froze the server
	 * for 2-3 s per big arena.
	 */
	static void preload(ServerLevel level, int slot, AuraType aura, boolean on) {
		if (on == PRELOADED.containsKey(slot)) return;
		// the sky auras' Skyward Rings reach 160 blocks out
		int radius = on ? aura == AuraType.WIND || aura == AuraType.LIGHT || aura == AuraType.THUNDER ? 12 : 9 : PRELOADED.remove(slot);
		if (on) PRELOADED.put(slot, radius);
		var origin = Monument.origin(slot);
		// the monument itself too: a wing whose chunk unloaded and came back lost its displays (leftovers are cleared on load)
		List<int[]> areas = new ArrayList<>(List.of(ARENAS));
		areas.add(new int[]{0, 0});
		for (int[] o : areas) {
			var pos = new net.minecraft.world.level.ChunkPos(new BlockPos(origin.getX() + o[0], 0, origin.getZ() + o[1]));
			int r = o[0] == 0 && o[1] == 0 ? 9 : radius;
			if (on) level.getChunkSource().addRegionTicket(net.minecraft.server.level.TicketType.FORCED, pos, r, pos);
			else level.getChunkSource().removeRegionTicket(net.minecraft.server.level.TicketType.FORCED, pos, r, pos);
		}
	}

	/** Server stop / dev reset: tickets don't outlive the server, so only the bookkeeping goes. */
	static void forgetPreloads() {
		PRELOADED.clear();
	}

	// ---------------------------------------------------------------
	// The base
	// ---------------------------------------------------------------

	abstract static class Trial {
		final Rifts.Run run;
		final int index;
		final Kind kind;
		/** The wing hall's middle (antechamber), and the arena's middle; both at floor height. */
		final BlockPos c, a;
		final int fx, fz, lx, lz;
		final RealmBuilder.Palette pal;
		/** Half the wing hall's inner size, and the difficulty tier (0 Easy .. 4 Hardcore). */
		final int hall, tier;
		final int rgb;
		boolean started, solved, built;
		/** The shard is out in the realm (dropped, carried or waiting) and must be kept from getting lost. */
		boolean shardOut;
		/** 0..2: strikes since the last full reset; total strikes this run. */
		int strikes, totalStrikes;
		boolean introPlayed;
		final List<Display> displays = new ArrayList<>();
		/** Antechamber portal (in the wing) and the arena's way back. */
		TrialPortal gate, back;
		/** More ways out of the arena (a big gate opened by the solve), leading to the wing like {@link #back}. */
		final List<TrialPortal> exits = new ArrayList<>();
		/** Players inside this trial (antechamber or arena) last tick. */
		final Set<UUID> inside = new HashSet<>();
		/** The mobs this trial's mistakes called up (capped by RIFT_TRIAL_MOB_CAP, cleared when the trial resets). */
		final List<Mob> hazards = new ArrayList<>();
		RandomSource random;
		/** The way into the arena stands (see {@link #repair}). */
		boolean repaired = true;

		Trial(Rifts.Run run, int index, Kind kind) {
			this.run = run;
			this.index = index;
			this.kind = kind;
			this.c = run.island(index);
			int[] dir = Monument.anchors().wingDirs.get(index);
			this.fx = dir[0];
			this.fz = dir[1];
			this.hall = Monument.anchors().hall;
			this.tier = run.difficulty.tier();
			this.lx = -fz;
			this.lz = fx;
			this.pal = RealmBuilder.palette(run.aura);
			this.rgb = Reliquary.WING_RGB[index];
			BlockPos hub = run.hub();
			this.a = new BlockPos(hub.getX() + ARENAS[index][0], Rifts.FLOOR, hub.getZ() + ARENAS[index][1]);
		}

		// --- frames

		/** A block in the wing (antechamber) frame; dy is relative to the floor (0 = the floor itself, 1 = standing height). */
		BlockPos at(int f, int l, int dy) {
			return new BlockPos(c.getX() + fx * f + lx * l, Rifts.FLOOR + dy, c.getZ() + fz * f + lz * l);
		}

		/** A block in the arena frame. */
		BlockPos arena(int f, int l, int dy) {
			return new BlockPos(a.getX() + fx * f + lx * l, Rifts.FLOOR + dy, a.getZ() + fz * f + lz * l);
		}

		/** The arena-frame (f, l) of a world position. */
		double af(Vec3 p) {
			return (p.x - a.getX() - 0.5) * fx + (p.z - a.getZ() - 0.5) * fz;
		}

		double al(Vec3 p) {
			return (p.x - a.getX() - 0.5) * lx + (p.z - a.getZ() - 0.5) * lz;
		}

		/** Sets a block in the arena frame. */
		void set(ServerLevel level, int f, int l, int dy, Block b) {
			BlockPos p = arena(f, l, dy);
			RealmBuilder.set(level, p.getX(), p.getY(), p.getZ(), b);
		}

		void set(ServerLevel level, int f, int l, int dy, net.minecraft.world.level.block.state.BlockState s) {
			BlockPos p = arena(f, l, dy);
			RealmBuilder.set(level, p.getX(), p.getY(), p.getZ(), s);
		}

		/** Yaw that looks along the arena's forward. */
		float forwardYaw() {
			return (float) Math.toDegrees(Math.atan2(-fx, fz));
		}

		/** Arena half-size used for its box (contains, clearing). Override for a bigger or smaller arena (max 110). */
		int arenaHalf() {
			return 40;
		}

		/** How far the arena reaches below and above the floor. */
		int arenaDown() {
			return 30;
		}

		int arenaUp() {
			return 60;
		}

		/** Where the way back stands (arena frame f): near the arena's back edge, facing forward. */
		int backF() {
			return -arenaHalf() + 4;
		}

		boolean inArena(Vec3 p) {
			double f = af(p), l = al(p);
			return Math.abs(f) <= arenaHalf() + 2 && Math.abs(l) <= arenaHalf() + 2 && p.y > Rifts.FLOOR - arenaDown() - 10 && p.y < Rifts.FLOOR + arenaUp() + 10;
		}

		boolean inWing(Vec3 p) {
			double dx = p.x - (c.getX() + 0.5), dz = p.z - (c.getZ() + 0.5);
			return Math.abs(dx) <= hall + 1.5 && Math.abs(dz) <= hall + 1.5 && p.y > Rifts.FLOOR - 30 && p.y < Rifts.FLOOR + 30;
		}

		boolean contains(Vec3 p) {
			return inWing(p) || inArena(p);
		}

		/** Where a player who fell in this trial comes back. */
		Vec3 respawn(Vec3 p) {
			return inArena(p) && back != null ? back.exit() : Vec3.atBottomCenterOf(at(-hall + 2, 0, 1));
		}

		/** Where a lost shard comes back to (default: the arena's middle; trials with a plinth override). */
		Vec3 plinth() {
			return Vec3.atBottomCenterOf(arena(0, 0, 1)).add(0, 0.2, 0);
		}

		// --- what a trial fills in

		abstract String name();

		/** One line for chat on entering the arena. */
		abstract String hint();

		/** Builds the arena (the box around {@link #a} is already empty) and the puzzle's first layout. */
		abstract void build(ServerLevel level, RandomSource r);

		/** The first player steps into the arena. */
		void begin(ServerLevel level, List<ServerPlayer> here) {
		}

		/** Every server tick while the run lasts; {@code here} = run players inside the arena. */
		abstract void tick(ServerLevel level, List<ServerPlayer> here, long now);

		/** A right-click on a block of the arena (main hand). True = it was this trial's (cancels the click). */
		boolean use(ServerLevel level, ServerPlayer p, BlockPos pos) {
			return false;
		}

		/** Strike 1: the room reacts (the base already plays a sound and a dark pulse). */
		void onReact(ServerLevel level, Vec3 at) {
		}

		/** Strike 2: a hazard plus a partial reset. Default: mobs. */
		void onHazard(ServerLevel level, Vec3 at) {
			punish(level, at, 2 + tier / 2);
		}

		/** Strike 3: a full reset with a new layout. */
		abstract void onReset(ServerLevel level, RandomSource r);

		/** A player leaves the trial (walks out, the run ends, logs off): give back what was lent, etc. */
		void release(ServerPlayer p) {
		}

		/** The camera intro the first time someone enters the arena; null = the default flyover. */
		RiftCam intro() {
			return null;
		}

		// --- shared machinery

		/** All chunks of the arena's box are loaded (built without a blocking chunk load). */
		boolean chunksReady(ServerLevel level) {
			int h = arenaHalf() + 4;
			for (int cx = (a.getX() - h) >> 4; cx <= (a.getX() + h) >> 4; cx++)
				for (int cz = (a.getZ() - h) >> 4; cz <= (a.getZ() + h) >> 4; cz++) if (!level.getChunkSource().hasChunk(cx, cz)) return false;
			return true;
		}

		/** The arena: empties its box, builds the trial, puts the way back in. Called once, a little after the vote. */
		final void buildAll(ServerLevel level, RandomSource r) {
			this.random = r;
			long t0 = System.nanoTime();
			int h = arenaHalf() + 4;
			Monument.clearColumns(level, a.getX() - h, a.getZ() - h, 2 * h + 1);
			build(level, r);
			// the antechamber: a portal at the far wall of the wing, facing the altar
			gate = new TrialPortal(at(hall - 3, 0, 0), -fx, -fz, rgb, kind);
			back = new TrialPortal(arena(backF() - 1, 0, 0), fx, fz, rgb, kind);
			gate.to = back;
			back.to = gate;
			back.build(level, pal);
			// a landing under the way back, in case the arena left it floating
			for (int f = backF() - 3; f <= backF() + 4; f++)
				for (int l = -3; l <= 3; l++) if (level.getBlockState(arena(f, l, 0)).isAir()) set(level, f, l, 0, pal.floor());
			label(level, Vec3.atBottomCenterOf(at(hall - 3, 0, PortalShapes.top(kind) + 1)).add(0, 0.8, 0), Component.literal(name()).withStyle(s -> s.withColor(rgb).withBold(true)), 2.2f);
			dressWing(level);
			repair.setup(level, r);
			built = true;
			com.toast.aurapoints.AuraPoints.LOGGER.debug("[rift] built the {} arena in {} ms", name(), (System.nanoTime() - t0) / 1_000_000);
			if (repaired) gate.setOpen(level, true, pal.gate());
			back.setOpen(level, true, pal.gate());
		}

		// --- the portal's repair (5.50; the arch, the compass and the opening scene 5.52: PortalRepair)

		final PortalRepair repair = new PortalRepair(this);

		boolean repairUse(ServerLevel level, ServerPlayer p, BlockPos pos) {
			return repair.use(level, p, pos);
		}

		/** An arch stone in the hand, aimed at its slot. */
		boolean stoneUse(ServerLevel level, ServerPlayer p) {
			return repair.stoneUse(level, p);
		}

		private static final Block[] WING_CARPET = {Blocks.BLUE_CARPET, Blocks.LIME_CARPET, Blocks.YELLOW_CARPET, Blocks.RED_CARPET};
		private static final Block[] WING_BANNER = {Blocks.BLUE_WALL_BANNER, Blocks.LIME_WALL_BANNER, Blocks.YELLOW_WALL_BANNER, Blocks.RED_WALL_BANNER};

		/** Air only: a block of the wing's dressing. */
		private void putW(ServerLevel level, int f, int l, int dy, net.minecraft.world.level.block.state.BlockState st) {
			BlockPos p = at(f, l, dy);
			if (level.getBlockState(p).isAir()) RealmBuilder.set(level, p.getX(), p.getY(), p.getZ(), st);
		}

		/**
		 * The antechamber is no empty box: a carpet runner in the wing's colour from the door to the portal, pillars with a
		 * brazier at their feet in pairs down both sides, and the wing's banners on the long walls under the windows.
		 * Everything stays off the path (|l| <= 3 from the door to the portal) and out of the portal's frame.
		 */
		private void dressWing(ServerLevel level) {
			int idx = Math.max(0, Math.min(3, index));
			for (int f = -hall + 1; f <= hall - 6; f++) for (int l = -1; l <= 1; l++) putW(level, f, l, 1, WING_CARPET[idx].defaultBlockState());
			for (int f : new int[]{-11, -4, 3, 10}) {
				for (int side = -1; side <= 1; side += 2) {
					for (int dy = 1; dy <= 9; dy++) putW(level, f, side * 8, dy, pal.edge().defaultBlockState());
					putW(level, f, side * 8, 10, pal.accent().defaultBlockState());
					putW(level, f, side * 5, 1, pal.edge().defaultBlockState());
					putW(level, f, side * 5, 2, pal.accent().defaultBlockState());
					putW(level, f, side * 5, 3, pal.light().defaultBlockState());
				}
			}
			for (int f : new int[]{-12, -6, 0, 6, 12}) {
				for (int side = -1; side <= 1; side += 2) {
					putW(level, f, side * hall, 6, WING_BANNER[idx].defaultBlockState().setValue(WallBannerBlock.FACING, TrialDecor.dir(this, 0, -side)));
				}
			}
		}

		/** Base tick: portals, the intro, enter/leave; then the trial's own tick with the arena players. */
		final void tickAll(ServerLevel level, List<ServerPlayer> up, long now) {
			if (!built) return;
			List<ServerPlayer> wing = new ArrayList<>(), arena = new ArrayList<>();
			Set<UUID> nowIn = new HashSet<>();
			for (ServerPlayer p : up) {
				if (inArena(p.position())) arena.add(p);
				else if (inWing(p.position())) wing.add(p);
				else continue;
				nowIn.add(p.getUUID());
			}
			for (UUID id : inside) if (!nowIn.contains(id)) {
				ServerPlayer p = level.getServer().getPlayerList().getPlayer(id);
				if (p == null) p = Rifts.TEST_PLAYERS.get(id);
				if (p != null) release(p);
			}
			inside.clear();
			inside.addAll(nowIn);
			gate.tick(level, wing, now);
			back.tick(level, arena, now);
			for (TrialPortal e : exits) e.tick(level, arena, now);
			if (!repaired || repair.sceneRunning(now)) repair.tick(level, up, wing, now);
			if (!started && !arena.isEmpty()) {
				started = true;
				for (ServerPlayer p : arena) {
					AuraFx.title(p, Component.literal(name()).withStyle(s -> s.withColor(rgb).withBold(true)), Component.literal(run.difficulty.label).withStyle(run.difficulty.color));
					p.sendSystemMessage(Component.literal("✦ " + name() + ": ").withStyle(s -> s.withColor(rgb)).append(Component.literal(hint()).withStyle(ChatFormatting.GRAY)));
				}
				if (!introPlayed) {
					introPlayed = true;
					RiftCam cam = intro();
					(cam != null ? cam : defaultIntro()).send(arena);
				}
				begin(level, arena);
			}
			if (started) tick(level, arena, now);
		}

		/** A slow pass from behind the way back, up over the arena's middle, then home. */
		RiftCam defaultIntro() {
			int h = arenaHalf();
			Vec3 mid = Vec3.atCenterOf(arena(0, 0, 2));
			Vec3 from = Vec3.atCenterOf(arena(-h, 0, 14)), over = Vec3.atCenterOf(arena(-h / 3, h / 3, h / 2 + 10)), side = Vec3.atCenterOf(arena(h / 3, -h / 2, 18));
			return RiftCam.shot(rgb).title(name(), run.difficulty.label).skippable(true)
					.key(0, from, mid).key(60, over, mid).key(120, side, mid);
		}

		/** Hints allowed: Easy after the first strike, Medium after the second, Hard and up never. */
		boolean hintsAllowed() {
			return tier == 0 ? totalStrikes >= 1 : tier == 1 && totalStrikes >= 2;
		}

		/** A mistake by {@code who} (null = no one in particular) at {@code at}: walks the 3-strike ladder. */
		void strike(ServerLevel level, ServerPlayer who, Vec3 at, List<ServerPlayer> here) {
			if (solved) return;
			strikes++;
			totalStrikes++;
			level.playSound(null, at.x, at.y, at.z, AuraSounds.WARDEN_HEARTBEAT.value(), SoundSource.HOSTILE, 2f, 0.6f);
			level.sendParticles(ParticleTypes.SCULK_SOUL, at.x, at.y + 1, at.z, 20, 1.5, 0.8, 1.5, 0.02);
			for (ServerPlayer p : here) p.addEffect(new MobEffectInstance(MobEffects.DARKNESS, 50, 0, false, false));
			actionBar(here, "Strike " + strikes + " / 3", strikes >= 3 ? ChatFormatting.DARK_RED : ChatFormatting.RED);
			if (strikes == 1) {
				onReact(level, at);
			} else if (strikes == 2) {
				level.playSound(null, at.x, at.y, at.z, AuraSounds.EVOKER_PREPARE_SUMMON.value(), SoundSource.HOSTILE, 1.5f, 0.8f);
				onHazard(level, at);
			} else {
				strikes = 0;
				clearHazards();
				level.playSound(null, at.x, at.y, at.z, AuraSounds.RESPAWN_ANCHOR_DEPLETE.value(), SoundSource.HOSTILE, 2f, 0.6f);
				for (ServerPlayer p : here) AuraFx.title(p, Component.literal("The trial resets").withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD), Component.literal("a new layout").withStyle(ChatFormatting.GRAY));
				onReset(level, random != null ? random : level.random);
				if (run.difficulty.oneLife() && who != null) Rifts.knockoutFromTrial(who);
			}
		}

		/** Solved: the shard comes out at {@code at}, with a short shot of it for those in the arena. */
		void solve(ServerLevel level, Vec3 at) {
			if (solved) return;
			solved = true;
			if (at != null) {
				List<ServerPlayer> here = new ArrayList<>();
				for (UUID id : inside) {
					ServerPlayer p = level.getServer().getPlayerList().getPlayer(id);
					if (p != null && inArena(p.position())) here.add(p);
				}
				solvedCam(at.add(0, 0.5, 0)).send(here);
			}
			Rifts.onTrialSolved(level, run, this, at);
		}

		/** The shot of the freed shard. */
		RiftCam solvedCam(Vec3 look) {
			Vec3 dir = new Vec3(-fx, 0, -fz);
			return RiftCam.shot(rgb).title("Shard freed", "carry it back through the portal").skippable(true)
					.key(0, look.add(dir.scale(9)).add(0, 6, 0), look).key(50, look.add(dir.scale(5)).add(lx * 3, 2.5, lz * 3), look);
		}

		void remove() {
			for (Display d : displays) d.discard();
			displays.clear();
			repair.remove();
			if (gate != null) gate.remove();
			if (back != null) back.remove();
			for (TrialPortal e : exits) e.remove();
		}

		Display.TextDisplay label(ServerLevel level, Vec3 at, Component text, float scale) {
			Display.TextDisplay d = (Display.TextDisplay) com.toast.aurapoints.arena.Holograms.makePublic(level, at, text, scale, 0);
			if (d == null) return null;
			d.addTag(DISPLAY_TAG);
			level.addFreshEntity(d);
			displays.add(d);
			return d;
		}

		void actionBar(List<ServerPlayer> to, String text, ChatFormatting color) {
			for (ServerPlayer p : to) p.displayClientMessage(Component.literal(text).withStyle(color), true);
		}

		/** {@code n} of the aura's touched mobs at {@code at}. */
		void punish(ServerLevel level, Vec3 at, int n) {
			hazards.removeIf(m -> !m.isAlive());
			int cap = Math.max(1, Math.min(12, com.toast.aurapoints.config.AuraConfig.RIFT_TRIAL_MOB_CAP));
			for (int k = 0; k < n && hazards.size() < cap; k++) {
				Mob m = Rifts.trialMob(level, run, at);
				if (m != null) hazards.add(m);
			}
			level.playSound(null, at.x, at.y, at.z, AuraSounds.EVOKER_PREPARE_SUMMON.value(), SoundSource.HOSTILE, 1f, 1.2f);
		}

		/** The trial resets: its mistakes' mobs go with the old layout. */
		void clearHazards() {
			for (Mob m : hazards) {
				run.mobs.remove(m);
				if (m.isAlive()) m.discard();
			}
			hazards.clear();
		}

		/** Hazard mobs stand still (a camera shot has the players' eyes: nothing may hit them meanwhile). */
		void freezeHazards(boolean frozen) {
			for (Mob m : hazards) if (m.isAlive()) m.setNoAi(frozen);
		}

		/**
		 * DEV ONLY: plays this trial through its real inputs with a fake player standing in the arena, logs lines with
		 * "(must be X)" through {@code log} and calls {@code done} when finished (now or later via AuraFx.later).
		 */
		void devPlay(net.minecraft.server.MinecraftServer server, ServerLevel level, ServerPlayer fake, java.util.function.Consumer<String> log, Runnable done) {
			log.accept(name() + ": no dev test yet");
			done.run();
		}

		/** Air at an arena column from dy0 to dy1 (holes, pits). */
		void clearColumn(ServerLevel level, int f, int l, int dy0, int dy1) {
			for (int dy = dy0; dy <= dy1; dy++) set(level, f, l, dy, Blocks.AIR);
		}
	}
}
