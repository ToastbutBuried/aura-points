package com.toast.aurapoints.rift;

import com.toast.aurapoints.ability.AuraFx;
import com.toast.aurapoints.ability.Props;
import com.toast.aurapoints.config.AuraConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.LodestoneTracker;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * A wing's broken portal (5.50, reworked 5.52). Take the Keystone compass off the pedestal's lodestone and follow it to
 * the Keystones in the decorated pots; rebuild the arch from the stones lying about the wing, each in its place by the
 * model on the drafting table; then rub the Keystones alight on the pedestal ({@link SkillChecks}), and the portal tears
 * itself open. Everything handed out is a plain vanilla item with a tag, taken back on the way out; what gets lost
 * comes back.
 */
public final class PortalRepair {

	static final String STONE = "aurapoints_archstone", COMPASS = "aurapoints_keycompass";
	private static final String[] KIND_NAME = {"post stone", "lintel stone", "crown stone", "lamp"};
	/** How many of the arch's pieces are missing, per tier. */
	private static final int[] MISSING = {8, 10, 12, 14, 16};
	/** The opening scene: the burst that opens the portal, and the scene's end (ticks). */
	private static final int BURST = 124, SCENE = 156;
	private static final String RUNES = "ᚠᚢᚦᚨᚱᚲᚷᚹ";
	/** The miniature's scale. */
	private static final float MINI = 0.17f;

	private final Trials.Trial t;
	int needed, set;
	final List<BlockPos> caches = new ArrayList<>();
	BlockPos pedestal, table;
	/** The arch's missing pieces, and the stones lying about (position -> kind). */
	final Map<BlockPos, TrialPortal.Piece> missing = new LinkedHashMap<>();
	final Map<BlockPos, Integer> loose = new HashMap<>();
	private Display.TextDisplay sign;
	private final Map<BlockPos, Display.BlockDisplay> ghosts = new HashMap<>(), mini = new HashMap<>();
	private final List<Display.TextDisplay> runes = new ArrayList<>();
	private final List<Display.BlockDisplay> flying = new ArrayList<>();
	private final Set<UUID> told = new HashSet<>();
	private final Map<UUID, Long> clickCd = new HashMap<>();
	private boolean pulse;
	private int placed;
	/** The opening scene's first tick (-1 = not begun). */
	long sceneAt = -1;

	PortalRepair(Trials.Trial t) {
		this.t = t;
	}

	// ---------------------------------------------------------------
	// Items
	// ---------------------------------------------------------------

	private static CompoundTag tag(ItemStack s) {
		CustomData d = s.get(DataComponents.CUSTOM_DATA);
		return d == null ? new CompoundTag() : d.copyTag();
	}

	public static boolean isStone(ItemStack s) {
		return !s.isEmpty() && tag(s).contains(STONE);
	}

	static boolean isCompass(ItemStack s) {
		return s.is(Items.COMPASS) && tag(s).contains(COMPASS);
	}

	private boolean mine(ItemStack s, String key) {
		if (s.isEmpty()) return false;
		CompoundTag c = tag(s);
		return c.contains(key) && c.getInt(key) == t.index && c.getInt("run") == t.run.id;
	}

	private boolean myKeystone(ItemStack s) {
		return Trials.isKeystone(s) && Trials.keystoneWing(s) == t.index && Trials.keystoneRun(s) == t.run.id;
	}

	private ItemStack item(net.minecraft.world.level.ItemLike like, String key, String name, String... lore) {
		ItemStack s = new ItemStack(like);
		CompoundTag c = new CompoundTag();
		c.putInt(key, t.index);
		c.putInt("run", t.run.id);
		s.set(DataComponents.CUSTOM_DATA, CustomData.of(c));
		s.set(DataComponents.CUSTOM_NAME, Component.literal(name).withStyle(st -> st.withColor(t.rgb).withItalic(false).withBold(true)));
		List<Component> l = new ArrayList<>();
		for (String x : lore) l.add(Component.literal(x).withStyle(st -> st.withColor(0xAAAAAA).withItalic(false)));
		s.set(DataComponents.LORE, new ItemLore(l));
		return s;
	}

	ItemStack makeStone(int kind) {
		ItemStack s = item(TrialPortal.stones(t.pal)[kind], STONE, "Arch " + KIND_NAME[kind], "Right-click its place in the " + Trials.WING_NAME[t.index] + " wing's broken arch.", "The model on the drafting table shows where.");
		CompoundTag c = tag(s);
		c.putInt("kind", kind);
		s.set(DataComponents.CUSTOM_DATA, CustomData.of(c));
		return s;
	}

	private ItemStack makeCompass(ServerLevel level) {
		ItemStack s = item(Items.COMPASS, COMPASS, "Keystone compass - " + Trials.WING_NAME[t.index] + " wing", "Points at the nearest hidden Keystone of this wing,", "then back at the portal.");
		point(level, s, Vec3.atCenterOf(pedestal));
		return s;
	}

	/** Every stone and compass taken back (on the way out of the rift). */
	static void purge(ServerPlayer p) {
		var inv = p.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) if (isStone(inv.getItem(i)) || isCompass(inv.getItem(i))) inv.setItem(i, ItemStack.EMPTY);
		ItemStack c = p.containerMenu.getCarried();
		if (isStone(c) || isCompass(c)) p.containerMenu.setCarried(ItemStack.EMPTY);
	}

	private static void give(ServerPlayer p, ItemStack s) {
		if (!p.getInventory().add(s)) p.drop(s, false);
	}

	/** The nearest Keystone pot from {@code from} (the pedestal once there are none). */
	BlockPos target(Vec3 from) {
		BlockPos best = pedestal;
		double d = Double.MAX_VALUE;
		for (BlockPos c : caches) {
			double x = from.distanceToSqr(Vec3.atCenterOf(c));
			if (x < d) {
				d = x;
				best = c;
			}
		}
		return best;
	}

	private void point(ServerLevel level, ItemStack s, Vec3 from) {
		LodestoneTracker want = new LodestoneTracker(Optional.of(GlobalPos.of(level.dimension(), target(from))), false);
		if (!want.equals(s.get(DataComponents.LODESTONE_TRACKER))) s.set(DataComponents.LODESTONE_TRACKER, want);
	}

	// ---------------------------------------------------------------
	// Setting up
	// ---------------------------------------------------------------

	void setup(ServerLevel level, RandomSource r) {
		remove();
		needed = Math.max(0, Math.min(6, AuraConfig.RIFT_PORTAL_KEYSTONES));
		set = 0;
		placed = 0;
		sceneAt = -1;
		told.clear();
		if (needed == 0) {
			t.repaired = true;
			t.gate.build(level, t.pal);
			return;
		}
		t.repaired = false;
		List<TrialPortal.Piece> all = t.gate.frame(t.pal);
		int n = MISSING[Math.max(0, Math.min(4, t.tier))];
		for (TrialPortal.Piece pc : all.subList(all.size() - n, all.size())) missing.put(pc.pos(), pc);
		t.gate.buildBroken(level, t.pal, missing.keySet());
		// the pedestal on one side of the way, the drafting table with the model on the other
		pedestal = t.at(t.hall - 6, 3, 1);
		put(level, pedestal, t.pal.edge());
		put(level, pedestal.above(), t.pal.edge());
		put(level, pedestal.above(2), Blocks.LODESTONE);
		sign = t.label(level, Vec3.atBottomCenterOf(pedestal.above(3)).add(0, 0.25, 0), signText(), 0.75f);
		if (AuraConfig.RIFT_PORTAL_STONES) {
			table = t.at(t.hall - 6, -3, 1);
			put(level, table, Blocks.CARTOGRAPHY_TABLE);
			t.label(level, Vec3.atBottomCenterOf(table.above()).add(0, (TrialPortal.H + 4) * MINI + 0.25, 0), Component.literal("How it stood").withStyle(s -> s.withColor(t.rgb)), 0.8f);
			Vec3 c = Vec3.atBottomCenterOf(table.above());
			for (TrialPortal.Piece pc : all) mini.put(pc.pos(), RuinKit.prop(t, level, c, pc.block().defaultBlockState(), miniT(pc, 1f), 0, true));
			for (TrialPortal.Piece pc : missing.values()) ghost(level, pc);
			List<Integer> kinds = new ArrayList<>();
			for (TrialPortal.Piece pc : missing.values()) kinds.add(pc.kind());
			scatter(level, r, kinds);
		}
		// dark runes up the posts: each Keystone lights some
		for (int k = 0; k < 8; k++) {
			Display.TextDisplay d = t.label(level, t.gate.point((k % 2 == 0 ? -1 : 1) * (TrialPortal.W + 1), 0.4 + (k / 2), 0.62), rune(k, false), 1.3f);
			if (d != null) runes.add(d);
		}
		addCaches(level, r, needed);
	}

	private Component rune(int k, boolean lit) {
		return Component.literal(RUNES.substring(k, k + 1)).withStyle(s -> s.withColor(lit ? t.rgb : 0x34343C).withBold(true));
	}

	private static void put(ServerLevel level, BlockPos p, Block b) {
		RealmBuilder.set(level, p.getX(), p.getY(), p.getZ(), b);
	}

	/** A piece of the miniature; {@code k} = its size (missing ones breathe). */
	private com.mojang.math.Transformation miniT(TrialPortal.Piece pc, float k) {
		int[] sl = t.gate.slot(pc);
		Vec3 o = t.gate.point(sl[0], sl[1] - 1, 0).subtract(t.gate.point(0, 0, 0));
		return RuinKit.centred(new Vector3f((float) o.x * MINI, (float) o.y * MINI + MINI / 2 + 0.04f, (float) o.z * MINI), MINI * k, MINI * k, MINI * k, new Quaternionf());
	}

	/** The empty place in the arch: the stone itself on Easy and Medium, only an outline above. */
	private void ghost(ServerLevel level, TrialPortal.Piece pc) {
		boolean tell = t.tier <= 1;
		float w = tell ? 0.42f : 0.3f;
		BlockState b = tell ? pc.block().defaultBlockState() : Blocks.WHITE_STAINED_GLASS.defaultBlockState();
		ghosts.put(pc.pos(), RuinKit.prop(t, level, Vec3.atCenterOf(pc.pos()), b, RuinKit.centred(new Vector3f(), w, w, w, new Quaternionf()), 0, true));
	}

	/** Puts stones of these kinds on the wing's floor, off the way to the portal. */
	private void scatter(ServerLevel level, RandomSource r, List<Integer> kinds) {
		Block[] st = TrialPortal.stones(t.pal);
		for (int kind : kinds) {
			for (int tries = 0; tries < 400; tries++) {
				int f = -t.hall + 3 + r.nextInt(2 * t.hall - 9), l = (r.nextBoolean() ? 1 : -1) * (4 + r.nextInt(Math.max(1, t.hall - 6)));
				BlockPos floor = t.at(f, l, 0);
				if (!openFloor(level, floor)) continue;
				boolean near = false;
				if (tries < 250) for (BlockPos o : loose.keySet()) if (o.distSqr(floor.above()) < 9) near = true;
				for (BlockPos o : t.run.caches) if (o.distSqr(floor.above()) < 4) near = true;
				// never onto someone's feet
				if (level.hasNearbyAlivePlayer(floor.getX() + 0.5, floor.getY() + 1, floor.getZ() + 0.5, 2)) near = true;
				if (near) continue;
				put(level, floor.above(), st[kind]);
				loose.put(floor.above(), kind);
				break;
			}
		}
	}

	private void addCaches(ServerLevel level, RandomSource r, int n) {
		for (BlockPos pos : pickCaches(level, r, n)) {
			put(level, pos, Blocks.DECORATED_POT);
			caches.add(pos);
			t.run.caches.add(pos);
		}
	}

	/** {@code n} pot spots across the monument: nave sides, entry hall flanks, transepts, the apse and the wing halls; apart, on open floor, never on a path. */
	private List<BlockPos> pickCaches(ServerLevel level, RandomSource r, int n) {
		var an = Monument.anchors();
		BlockPos hub = t.run.hub();
		List<int[]> zones = new ArrayList<>(List.of(new int[]{4, 11, 16, 66}, new int[]{-11, -4, 16, 66}, new int[]{10, 13, 76, 108}, new int[]{-13, -10, 76, 108},
				new int[]{13, 27, 3, 8}, new int[]{13, 27, -8, -3}, new int[]{-27, -13, 3, 8}, new int[]{-27, -13, -8, -3}, new int[]{6, 14, -17, -12}, new int[]{-14, -6, -17, -12}));
		List<BlockPos> out = new ArrayList<>();
		for (int attempt = 0; attempt < 900 && out.size() < n; attempt++) {
			int x, z;
			if (attempt % 3 == 0) {
				// inside one of the wing halls, off the aisle (pillars stand at 8, braziers at 5, the carpet at +-1)
				int w = r.nextInt(an.wings.size());
				BlockPos wc = an.wings.get(w);
				int[] d = an.wingDirs.get(w);
				int f = -10 + r.nextInt(17), l = (r.nextBoolean() ? 1 : -1) * (10 + r.nextInt(5));
				x = wc.getX() + d[0] * f + (-d[1]) * l;
				z = wc.getZ() + d[1] * f + d[0] * l;
			} else {
				int[] zn = zones.get(r.nextInt(zones.size()));
				x = zn[0] + r.nextInt(zn[1] - zn[0] + 1);
				z = zn[2] + r.nextInt(zn[3] - zn[2] + 1);
			}
			BlockPos floor = new BlockPos(hub.getX() + x, Rifts.FLOOR, hub.getZ() + z);
			if (!openFloor(level, floor)) continue;
			boolean clear = true;
			for (BlockPos o : t.run.caches) if (o.distSqr(floor.above()) < 49) clear = false;
			for (BlockPos o : out) if (o.distSqr(floor.above()) < 49) clear = false;
			for (int[] dd : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) if (!openFloor(level, floor.offset(dd[0], 0, dd[1]))) clear = false;
			if (clear) out.add(floor.above());
		}
		return out;
	}

	private static boolean openFloor(ServerLevel level, BlockPos floor) {
		return !level.getBlockState(floor).isAir() && level.getBlockState(floor.above()).isAir() && level.getBlockState(floor.above(2)).isAir();
	}

	boolean whole() {
		return missing.isEmpty();
	}

	private Component signText() {
		String how;
		if (t.repaired || sceneAt >= 0) how = "the way is open";
		else if (!whole() && AuraConfig.RIFT_PORTAL_STONES) how = "rebuild the arch: " + missing.size() + " stones left\nlodestone: take a compass to the Keystones";
		else if (set < needed) how = AuraConfig.RIFT_SKILL_CHECKS ? "hold a Keystone, rub it alight here\n(hold right-click, let go in the green)" : "right-click with a Keystone";
		else how = "";
		return Component.literal(t.repaired ? "Portal restored\n" : "Broken portal\n").withStyle(ChatFormatting.GRAY)
				.append(Component.literal(set + " / " + needed + " Keystones alight\n").withStyle(s -> s.withColor(t.rgb).withBold(true)))
				.append(Component.literal(how).withStyle(ChatFormatting.GRAY));
	}

	private void refreshSign() {
		if (sign != null) TrialDecor.text(sign, signText());
	}

	// ---------------------------------------------------------------
	// Clicks
	// ---------------------------------------------------------------

	boolean use(ServerLevel level, ServerPlayer p, BlockPos pos) {
		if (t.repaired) return false;
		long now = level.getGameTime();
		// a pot
		if (caches.remove(pos)) {
			t.run.caches.remove(pos);
			level.destroyBlock(pos, false);
			give(p, Trials.makeKeystone(t.run.id, t.index));
			RiftSfx.potBreak(level, Vec3.atCenterOf(pos));
			level.sendParticles(AuraFx.dust(t.rgb, 1.6f), pos.getX() + 0.5, pos.getY() + 0.6, pos.getZ() + 0.5, 20, 0.3, 0.4, 0.3, 0);
			say(p, "A Keystone of the " + Trials.WING_NAME[t.index] + " wing" + (caches.isEmpty() ? " - the last one: bring them to the portal" : " - " + caches.size() + " still hidden"), t.rgb);
			return true;
		}
		// a stone on the floor
		Integer kind = loose.get(pos);
		if (kind != null) {
			loose.remove(pos);
			BlockState was = level.getBlockState(pos);
			level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, was), pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 20, 0.3, 0.3, 0.3, 0.05);
			put(level, pos, Blocks.AIR);
			give(p, makeStone(kind));
			RiftSfx.pickStone(level, Vec3.atCenterOf(pos));
			clickCd.put(p.getUUID(), now + 4);
			say(p, "An arch " + KIND_NAME[kind] + " - the model shows its place", t.rgb);
			return true;
		}
		if (table != null && pos.equals(table)) {
			int[] left = new int[4];
			for (TrialPortal.Piece pc : missing.values()) left[pc.kind()]++;
			StringBuilder sb = new StringBuilder();
			for (int k = 0; k < 4; k++) if (left[k] > 0) sb.append(sb.length() == 0 ? "" : ", ").append(left[k]).append(' ').append(KIND_NAME[k]).append(left[k] > 1 ? "s" : "");
			say(p, sb.length() == 0 ? "The arch is whole - light it with the Keystones" : "Still missing: " + sb + " (they pulse in the model)", t.rgb);
			RiftSfx.play(level, Vec3.atCenterOf(table), SoundEvents.UI_CARTOGRAPHY_TABLE_TAKE_RESULT, 0.8f, 1f);
			return true;
		}
		if (pedestal == null || !(pos.equals(pedestal) || pos.equals(pedestal.above()) || pos.equals(pedestal.above(2)))) return false;
		if (sceneAt >= 0) return true;
		ItemStack held = p.getMainHandItem();
		if (!Trials.isKeystone(held)) {
			compass(level, p);
			return true;
		}
		if (!myKeystone(held)) {
			say(p, "This Keystone belongs to the " + Trials.WING_NAME[Math.max(0, Math.min(3, Trials.keystoneWing(held)))] + " wing", 0xFF5555);
			return true;
		}
		if (AuraConfig.RIFT_PORTAL_STONES && !whole()) {
			if (AuraFx.ready(p, "arch", 20)) RiftSfx.wrongStone(level, Vec3.atCenterOf(pedestal.above(2)));
			say(p, "The arch is still broken - " + missing.size() + " stones to set before it can be lit", 0xAAAAAA);
			return true;
		}
		if (!SkillChecks.enabled()) {
			light(level, p);
			return true;
		}
		if (SkillChecks.busy(p)) return true;
		Vec3 at = Vec3.atBottomCenterOf(pedestal.above(3)).add(0, 0.1, 0);
		SkillChecks.start(p, "Rub the Keystone alight", t.rgb, t.tier, at, true, () -> sceneAt < 0 && !t.repaired && myKeystone(p.getMainHandItem()), res -> {
			if (res == SkillChecks.GOOD) light(level, p);
			else if (res == SkillChecks.EARLY) say(p, "Not hot enough - it cools again", 0xAAAAAA);
			else if (res == SkillChecks.LATE) say(p, "It slipped - try again", 0xAAAAAA);
		});
		return true;
	}

	private void say(ServerPlayer p, String text, int rgb) {
		p.displayClientMessage(Component.literal(text).withStyle(s -> s.withColor(rgb)), true);
	}

	private void compass(ServerLevel level, ServerPlayer p) {
		boolean has = false;
		var inv = p.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) if (mine(inv.getItem(i), COMPASS)) has = true;
		Vec3 c = Vec3.atBottomCenterOf(pedestal.above(3));
		if (has) {
			say(p, caches.isEmpty() ? "No Keystone of this wing lies hidden any more" : "Follow the compass: " + caches.size() + " Keystone" + (caches.size() == 1 ? "" : "s") + " still hidden", t.rgb);
			return;
		}
		ItemStack s = makeCompass(level);
		point(level, s, p.position());
		if (p.getMainHandItem().isEmpty()) p.setItemInHand(InteractionHand.MAIN_HAND, s);
		else give(p, s);
		RiftSfx.compass(level, c);
		level.sendParticles(ParticleTypes.ENCHANT, c.x, c.y + 0.4, c.z, 25, 0.3, 0.4, 0.3, 0.6);
		say(p, "The compass points at this wing's Keystones", t.rgb);
	}

	/** The empty place of the arch {@code p} aims at (within 6.5 blocks), or null. */
	TrialPortal.Piece aimed(ServerPlayer p) {
		Vec3 eye = p.getEyePosition(), dir = p.getLookAngle();
		TrialPortal.Piece best = null;
		double bestD = 0.8;
		for (TrialPortal.Piece pc : missing.values()) {
			Vec3 to = Vec3.atCenterOf(pc.pos()).subtract(eye);
			double along = to.dot(dir);
			if (along < 0.3 || along > 6.5) continue;
			double off = to.subtract(dir.scale(along)).length();
			if (off < bestD) {
				bestD = off;
				best = pc;
			}
		}
		return best;
	}

	private boolean supported(ServerLevel level, TrialPortal.Piece pc) {
		if (t.gate.slot(pc)[1] == 1) return true;
		if (!level.getBlockState(pc.pos().below()).isAir() && !ghostAt(pc.pos().below())) return true;
		for (TrialPortal.Piece o : t.gate.frame(t.pal)) {
			if (missing.containsKey(o.pos()) || o.kind() != pc.kind()) continue;
			if (o.pos().getY() == pc.pos().getY() && o.pos().distManhattan(pc.pos()) == 1) return true;
		}
		return false;
	}

	private boolean ghostAt(BlockPos p) {
		return missing.containsKey(p);
	}

	/** A click with one of this wing's arch stones in the hand. */
	boolean stoneUse(ServerLevel level, ServerPlayer p) {
		ItemStack held = p.getMainHandItem();
		if (!mine(held, STONE)) return false;
		long now = level.getGameTime();
		if (now < clickCd.getOrDefault(p.getUUID(), 0L)) return true;
		clickCd.put(p.getUUID(), now + 4);
		TrialPortal.Piece pc = aimed(p);
		if (pc == null) {
			say(p, "Aim at an empty place in the broken arch", 0xAAAAAA);
			return true;
		}
		int kind = tag(held).getInt("kind");
		Vec3 c = Vec3.atCenterOf(pc.pos());
		if (pc.kind() != kind) {
			RiftSfx.wrongStone(level, c);
			level.sendParticles(ParticleTypes.SMOKE, c.x, c.y, c.z, 8, 0.3, 0.3, 0.3, 0.01);
			say(p, "A " + KIND_NAME[kind] + " does not fit there - look at the model", 0xFF8855);
			return true;
		}
		if (!supported(level, pc)) {
			RiftSfx.wrongStone(level, c);
			say(p, "Nothing holds it there yet - build up from below", 0xFF8855);
			return true;
		}
		held.shrink(1);
		setPiece(level, pc);
		if (whole()) {
			RiftSfx.archWhole(level, t.gate.point(0, 3, 0));
			for (ServerPlayer q : Rifts.online(level.getServer(), t.run)) say(q, "The " + Trials.WING_NAME[t.index] + " arch is whole - rub its Keystones alight on the pedestal", t.rgb);
		} else say(p, missing.size() + " stones to go", t.rgb);
		return true;
	}

	private void setPiece(ServerLevel level, TrialPortal.Piece pc) {
		missing.remove(pc.pos());
		put(level, pc.pos(), pc.block());
		RuinKit.drop(t, ghosts.remove(pc.pos()));
		Display.BlockDisplay m = mini.get(pc.pos());
		if (m != null) Props.set(m, miniT(pc, 1f), 4);
		Vec3 c = Vec3.atCenterOf(pc.pos());
		level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, pc.block().defaultBlockState()), c.x, c.y, c.z, 18, 0.35, 0.35, 0.35, 0.05);
		level.sendParticles(AuraFx.dust(t.rgb, 1.2f), c.x, c.y, c.z, 10, 0.45, 0.45, 0.45, 0);
		RiftSfx.setStone(level, c, pc.kind(), placed++);
		refreshSign();
	}

	/** A Keystone caught fire: some of the arch's runes light, the last one starts the opening. */
	private void light(ServerLevel level, ServerPlayer p) {
		if (sceneAt >= 0 || set >= needed) return;
		ItemStack held = p.getMainHandItem();
		if (!myKeystone(held)) return;
		held.shrink(1);
		set++;
		if (!AuraConfig.RIFT_PORTAL_STONES) {
			// without the stones each Keystone rebuilds its share of the arch
			int keep = missing.size() * (needed - set) / Math.max(1, needed - set + 1);
			for (TrialPortal.Piece pc : new ArrayList<>(missing.values())) if (missing.size() > keep) setPiece(level, pc);
		}
		Vec3 c = Vec3.atBottomCenterOf(pedestal.above(3));
		RiftSfx.ignite(level, c, set);
		level.sendParticles(ParticleTypes.FLAME, c.x, c.y + 0.2, c.z, 20, 0.2, 0.2, 0.2, 0.06);
		int lit = runes.size() * set / Math.max(1, needed);
		for (int k = 0; k < runes.size(); k++) {
			if (k >= lit) continue;
			Display.TextDisplay d = runes.get(k);
			TrialDecor.text(d, rune(k, true));
			AuraFx.line(level, c, d.position(), AuraFx.dust(t.rgb, 1.1f), 14);
			level.sendParticles(ParticleTypes.END_ROD, d.getX(), d.getY() + 0.2, d.getZ(), 4, 0.1, 0.2, 0.1, 0.03);
		}
		refreshSign();
		if (set >= needed) {
			sceneAt = level.getGameTime();
			startScene(level);
		} else say(p, set + " / " + needed + " Keystones alight", t.rgb);
	}

	// ---------------------------------------------------------------
	// The opening
	// ---------------------------------------------------------------

	private List<ServerPlayer> inWing(ServerLevel level) {
		List<ServerPlayer> out = new ArrayList<>();
		for (ServerPlayer p : Rifts.online(level.getServer(), t.run)) if (t.inWing(p.position())) out.add(p);
		return out;
	}

	private void startScene(ServerLevel level) {
		t.freezeHazards(true);
		Vec3 mid = t.gate.point(0, 2.4, 0), ped = Vec3.atBottomCenterOf(pedestal.above(3));
		RiftCam.shot(t.rgb).title("", "").skippable(true)
				.key(0, t.gate.point(1.5, 2.6, 9), ped).key(34, t.gate.point(0.5, 1.6, 7), mid)
				.cut(36, t.gate.point(-3.6, 0.6, 2.6), t.gate.point(-2, 1.5, 0)).key(66, t.gate.point(-3.2, 4.6, 3.2), t.gate.point(-1.5, 5, 0))
				.cut(68, t.gate.point(0, 1.6, 7.5), mid).key(BURST - 4, t.gate.point(0, 2.2, 4.5), mid)
				.cut(BURST - 3, t.gate.point(0, 2.6, 8), mid).key(SCENE, t.gate.point(0, 3.2, 12), mid)
				.send(inWing(level));
		for (int k = 0; k < Math.min(3, needed); k++)
			flying.add(RuinKit.prop(t, level, ped, Blocks.AMETHYST_BLOCK.defaultBlockState(), RuinKit.centred(new Vector3f(), 0.35f, 0.35f, 0.35f, new Quaternionf().rotationXYZ(0.6f, 0.6f, 0)), 2, true));
	}

	/** The opening scene is still playing (its last beats come after the portal opened). */
	boolean sceneRunning(long now) {
		return sceneAt >= 0 && now - sceneAt <= SCENE;
	}

	private void scene(ServerLevel level, long now) {
		int n = (int) (now - sceneAt);
		Vec3 mid = t.gate.point(0, 2.4, 0), ped = Vec3.atBottomCenterOf(pedestal.above(3));
		// the stones tremble
		if (n < BURST && n % 4 == 0) {
			Vec3 q = t.gate.point((level.random.nextBoolean() ? -2 : 2) + level.random.nextDouble() - 0.5, level.random.nextDouble() * 6, 0.2);
			level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, t.pal.edge().defaultBlockState()), q.x, q.y, q.z, 6, 0.3, 0.3, 0.3, 0.02);
			level.sendParticles(ParticleTypes.CLOUD, q.x, t.gate.base.getY() + 1.1, q.z, 2, 0.6, 0.05, 0.6, 0.01);
		}
		if (n < 70 && n % 12 == 0) {
			RiftSfx.play(level, mid, SoundEvents.DEEPSLATE_BREAK, 1.4f, 0.5f);
			RiftSfx.play(level, mid, SoundEvents.WARDEN_HEARTBEAT, 1.6f, 0.6f + n / 120f);
		}
		// runes climb both posts
		if (n >= 18 && n <= 66 && n % 6 == 0) {
			int k = (n - 18) / 6;
			double up = k * 0.7;
			for (int side : new int[]{-2, 2}) {
				Vec3 q = t.gate.point(side, up, 0.6);
				level.sendParticles(ParticleTypes.END_ROD, q.x, q.y, q.z, 8, 0.15, 0.2, 0.15, 0.04);
				level.sendParticles(AuraFx.dust(t.rgb, 1.8f), q.x, q.y, q.z, 12, 0.25, 0.3, 0.25, 0);
			}
			RiftSfx.play(level, mid, SoundEvents.NOTE_BLOCK_CHIME, 1.4f, RiftSfx.note(k));
		}
		// lightning between the posts
		if (n >= 60 && n < BURST - 8 && n % 7 == 0) {
			Vec3 a = t.gate.point(-1.6, level.random.nextDouble() * 4, 0), b = t.gate.point(1.6, level.random.nextDouble() * 4, 0), prev = a;
			for (int k = 1; k <= 6; k++) {
				Vec3 next = a.lerp(b, k / 6.0).add(level.random.nextGaussian() * 0.15, level.random.nextGaussian() * 0.25, level.random.nextGaussian() * 0.15);
				AuraFx.line(level, prev, next, ParticleTypes.ELECTRIC_SPARK, 5);
				prev = next;
			}
			RiftSfx.play(level, mid, SoundEvents.FIREWORK_ROCKET_TWINKLE, 0.8f, 1.6f);
			RiftSfx.play(level, mid, SoundEvents.TRIDENT_RETURN, 0.9f, 0.7f + (n - 60) / 80f);
		}
		// the Keystones leave the pedestal and spiral into the middle, faster and faster
		if (n >= 40 && n <= BURST - 12) {
			double u = (n - 40) / (double) (BURST - 52), e = u * u;
			for (int k = 0; k < flying.size(); k++) {
				double ang = k * Math.PI * 2 / flying.size() + u * u * 22, rad = 2.6 * (1 - e) + 0.05;
				Vec3 orbit = t.gate.point(Math.cos(ang) * rad, 2.4 + Math.sin(ang) * rad * 0.8, 0.9 * (1 - e));
				Vec3 at = u < 0.25 ? ped.add(0, u * 4, 0).lerp(orbit, u * 4 * u * 4) : orbit;
				Props.move(flying.get(k), at);
				level.sendParticles(AuraFx.dust(t.rgb, 1.0f), at.x, at.y, at.z, 2, 0.05, 0.05, 0.05, 0);
			}
			if (n % 5 == 0) RiftSfx.play(level, mid, SoundEvents.AMETHYST_BLOCK_RESONATE, 1.2f, (float) (0.6 + u * 1.4));
		}
		// a held breath
		if (n == BURST - 12) {
			RiftSfx.play(level, mid, SoundEvents.BEACON_DEACTIVATE, 1.4f, 0.6f);
			for (Display.BlockDisplay d : flying) RuinKit.drop(t, d);
			flying.clear();
			level.sendParticles(AuraFx.dust(0xFFFFFF, 2f), mid.x, mid.y, mid.z, 6, 0.05, 0.05, 0.05, 0);
			for (ServerPlayer p : inWing(level)) p.addEffect(new MobEffectInstance(MobEffects.DARKNESS, 30, 0, false, false));
		}
		if (n == BURST) open(level);
		if (n > BURST && n <= BURST + 10) {
			// the shockwave rolls out over the floor
			double rad = (n - BURST) * 1.3;
			for (int k = 0; k < 40; k++) {
				double a = k * Math.PI * 2 / 40;
				Vec3 q = t.gate.point(Math.cos(a) * rad, 0.3, Math.abs(Math.sin(a)) * rad);
				level.sendParticles(AuraFx.dust(t.rgb, 1.8f), q.x, q.y, q.z, 1, 0.05, 0.05, 0.05, 0);
			}
		}
	}

	/** The burst: the curtain tears open. */
	private void open(ServerLevel level) {
		Vec3 mid = t.gate.point(0, 2.4, 0);
		level.sendParticles(ParticleTypes.FLASH, mid.x, mid.y, mid.z, 3, 0.3, 0.6, 0.3, 0);
		level.sendParticles(ParticleTypes.EXPLOSION, mid.x, mid.y, mid.z, 3, 0.8, 1.2, 0.8, 0);
		level.sendParticles(ParticleTypes.REVERSE_PORTAL, mid.x, mid.y, mid.z, 160, 0.6, 1.2, 0.6, 0.5);
		level.sendParticles(ParticleTypes.END_ROD, mid.x, mid.y, mid.z, 60, 0.4, 1, 0.4, 0.25);
		RiftSfx.play(level, mid, SoundEvents.GENERIC_EXPLODE, 0.7f, 0.7f);
		RiftSfx.play(level, mid, SoundEvents.END_PORTAL_SPAWN, 0.5f, 1.2f);
		t.gate.setOpen(level, true, t.pal.gate());
		t.freezeHazards(false);
		t.repaired = true;
		for (int k = 0; k < runes.size(); k++) TrialDecor.text(runes.get(k), rune(k, true));
		// compasses and spare stones have done their work
		for (ServerPlayer q : Rifts.online(level.getServer(), t.run)) {
			var inv = q.getInventory();
			for (int i = 0; i < inv.getContainerSize(); i++) if (mine(inv.getItem(i), COMPASS) || mine(inv.getItem(i), STONE)) inv.setItem(i, ItemStack.EMPTY);
		}
		refreshSign();
		for (ServerPlayer q : Rifts.online(level.getServer(), t.run)) {
			if (t.inWing(q.position())) continue;
			AuraFx.title(q, Component.literal(t.name() + " portal restored").withStyle(s -> s.withColor(t.rgb).withBold(true)), Component.literal("the way to the trial is open").withStyle(ChatFormatting.GRAY));
		}
	}

	// ---------------------------------------------------------------
	// Ticking
	// ---------------------------------------------------------------

	void tick(ServerLevel level, List<ServerPlayer> up, List<ServerPlayer> wing, long now) {
		if (sceneAt >= 0) {
			scene(level, now);
			if (!t.repaired && now - sceneAt > SCENE) open(level);
			return;
		}
		for (ServerPlayer p : wing) {
			if (!told.add(p.getUUID())) continue;
			p.sendSystemMessage(Component.literal("✦ The " + Trials.WING_NAME[t.index] + " portal is broken. ").withStyle(s -> s.withColor(t.rgb))
					.append(Component.literal("Take a compass from the lodestone and find its " + needed + " Keystones." + (AuraConfig.RIFT_PORTAL_STONES
							? " Rebuild the arch from the stones lying in this hall - the model on the table shows where each goes. Then rub the Keystones alight on the pedestal." : " Then light them on the pedestal.")).withStyle(ChatFormatting.GRAY)));
		}
		if (now % 4 == 0) {
			for (ServerPlayer p : wing) {
				if (!mine(p.getMainHandItem(), STONE)) continue;
				TrialPortal.Piece pc = aimed(p);
				if (pc == null) continue;
				Vec3 c = Vec3.atCenterOf(pc.pos());
				for (int x = -1; x <= 1; x += 2) for (int y = -1; y <= 1; y += 2) for (int z = -1; z <= 1; z += 2)
					level.sendParticles(p, AuraFx.dust(0xFFFFFF, 0.8f), true, c.x + x * 0.5, c.y + y * 0.5, c.z + z * 0.5, 1, 0, 0, 0, 0);
			}
		}
		if (now % 20 == 0) {
			pulse = !pulse;
			for (TrialPortal.Piece pc : missing.values()) {
				Display.BlockDisplay d = mini.get(pc.pos());
				if (d != null) Props.set(d, miniT(pc, pulse ? 0.55f : 1f), 18);
			}
			for (BlockPos pos : loose.keySet()) {
				for (ServerPlayer p : wing) level.sendParticles(p, AuraFx.dust(t.rgb, 1f), false, pos.getX() + 0.5, pos.getY() + 1.1, pos.getZ() + 0.5, 4, 0.3, 0.1, 0.3, 0);
			}
			for (ServerPlayer p : up) compassTick(level, p);
		}
		if (now % 10 == 0) {
			// close by, a Keystone's pot glimmers; from afar the compass finds it
			for (BlockPos pos : caches) {
				for (ServerPlayer p : up) {
					if (p.distanceToSqr(pos.getX(), pos.getY(), pos.getZ()) > 24 * 24) continue;
					for (int k = 0; k < 4; k++) level.sendParticles(p, AuraFx.dust(t.rgb, 1.4f), true, pos.getX() + 0.5, pos.getY() + 0.9 + k * 0.6, pos.getZ() + 0.5, 1, 0.04, 0.1, 0.04, 0);
					level.sendParticles(p, ParticleTypes.END_ROD, true, pos.getX() + 0.5, pos.getY() + 1.1, pos.getZ() + 0.5, 1, 0.2, 0.3, 0.2, 0.01);
				}
			}
		}
		if (now % 200 == 100) recoverLost(level, up);
	}

	private void compassTick(ServerLevel level, ServerPlayer p) {
		var inv = p.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) if (mine(inv.getItem(i), COMPASS)) point(level, inv.getItem(i), p.position());
		if (!mine(p.getMainHandItem(), COMPASS) && !mine(p.getOffhandItem(), COMPASS)) return;
		int d = (int) Math.round(p.position().distanceTo(Vec3.atCenterOf(target(p.position()))));
		say(p, caches.isEmpty() ? "Every Keystone is found - the portal is " + d + " blocks away" : "Keystone: " + d + " blocks (" + caches.size() + " hidden)", t.rgb);
		if (!caches.isEmpty() && d < 20) p.playNotifySound(SoundEvents.NOTE_BLOCK_HAT.value(), net.minecraft.sounds.SoundSource.PLAYERS, 0.4f, 1.8f - d * 0.04f);
	}

	private interface Match {
		int count(ItemStack s);
	}

	/** This wing's Keystones and stones that people here carry, have on the cursor or have dropped (one scan for all counts). */
	private List<ItemStack> afield(ServerLevel level, List<ServerPlayer> up) {
		List<ItemStack> out = new ArrayList<>();
		for (ServerPlayer p : up) {
			var inv = p.getInventory();
			for (int i = 0; i < inv.getContainerSize(); i++) if (myKeystone(inv.getItem(i)) || mine(inv.getItem(i), STONE)) out.add(inv.getItem(i));
			ItemStack c = p.containerMenu.getCarried();
			if (myKeystone(c) || mine(c, STONE)) out.add(c);
		}
		Vec3 hub = Vec3.atCenterOf(t.run.hub());
		for (ItemEntity e : level.getEntitiesOfClass(ItemEntity.class, new AABB(hub, hub).inflate(500, 220, 500), e -> myKeystone(e.getItem()) || mine(e.getItem(), STONE)))
			if (e.getY() > Rifts.FLOOR - 15) out.add(e.getItem());
		return out;
	}

	private static int count(List<ItemStack> all, Match m) {
		int n = 0;
		for (ItemStack s : all) n += m.count(s);
		return n;
	}

	/** Keystones and stones carried, lying about or still in place must add up: a lost one (void, despawn, broken) comes back. */
	void recoverLost(ServerLevel level, List<ServerPlayer> up) {
		caches.removeIf(c -> {
			if (level.getBlockState(c).is(Blocks.DECORATED_POT)) return false;
			t.run.caches.remove(c);
			return true;
		});
		List<ItemStack> field = afield(level, up);
		int lost = needed - set - caches.size() - count(field, s -> myKeystone(s) ? s.getCount() : 0);
		RandomSource r = t.random != null ? t.random : level.random;
		if (lost > 0) {
			addCaches(level, r, lost);
			for (ServerPlayer p : up) say(p, "A lost " + Trials.WING_NAME[t.index] + " Keystone shimmers back into a pot", t.rgb);
		}
		if (!AuraConfig.RIFT_PORTAL_STONES) return;
		Block[] st = TrialPortal.stones(t.pal);
		loose.entrySet().removeIf(e -> !level.getBlockState(e.getKey()).is(st[e.getValue()]));
		List<Integer> back = new ArrayList<>();
		for (int k = 0; k < 4; k++) {
			int kind = k, want = 0, have = 0;
			for (TrialPortal.Piece pc : missing.values()) if (pc.kind() == kind) want++;
			for (int v : loose.values()) if (v == kind) have++;
			have += count(field, s -> mine(s, STONE) && tag(s).getInt("kind") == kind ? s.getCount() : 0);
			for (int n = have; n < want; n++) back.add(kind);
		}
		scatter(level, r, back);
	}

	void remove() {
		for (Display.BlockDisplay d : ghosts.values()) RuinKit.drop(t, d);
		for (Display.BlockDisplay d : mini.values()) RuinKit.drop(t, d);
		for (Display.BlockDisplay d : flying) RuinKit.drop(t, d);
		for (Display.TextDisplay d : runes) RuinKit.drop(t, d);
		RuinKit.drop(t, sign);
		sign = null;
		ghosts.clear();
		mini.clear();
		flying.clear();
		runes.clear();
		caches.clear();
		missing.clear();
		loose.clear();
	}

	// ---------------------------------------------------------------
	// Dev
	// ---------------------------------------------------------------

	/** DEV: sets every missing stone. */
	void devStones(ServerLevel level) {
		for (TrialPortal.Piece pc : new ArrayList<>(missing.values())) setPiece(level, pc);
	}

	/** DEV: every Keystone alight, the scene starts. */
	void devLight(ServerLevel level, ServerPlayer p) {
		devStones(level);
		while (set < needed) {
			p.setItemInHand(InteractionHand.MAIN_HAND, Trials.makeKeystone(t.run.id, t.index));
			int before = set;
			light(level, p);
			if (set == before) break;
		}
		p.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
	}

	/** Puts the dev fake in front of a piece, looking at it. */
	private void devAim(ServerPlayer fake, TrialPortal.Piece pc) {
		Vec3 c = Vec3.atCenterOf(pc.pos()), from = c.add(t.gate.nx * 4, -1.62, t.gate.nz * 4);
		Vec3 d = c.subtract(from.add(0, fake.getEyeHeight(), 0));
		fake.moveTo(from.x, from.y, from.z, (float) Math.toDegrees(Math.atan2(-d.x, d.z)), (float) -Math.toDegrees(Math.atan2(d.y, Math.hypot(d.x, d.z))));
	}

	/** DEV ONLY: breaks this wing's portal again and walks the whole repair through a fake player. */
	void devTest(ServerLevel level, ServerPlayer fake, java.util.function.Consumer<String> log) {
		int keep = AuraConfig.RIFT_PORTAL_KEYSTONES;
		Vec3 stood = fake.position();
		AuraConfig.RIFT_PORTAL_KEYSTONES = 3;
		RandomSource r = t.random != null ? t.random : level.random;
		t.gate.setOpen(level, false, t.pal.gate());
		setup(level, r);
		Block[] st = TrialPortal.stones(t.pal);
		log.accept("repair: four different stones " + (new HashSet<>(List.of(st)).size() == 4) + " (must be true)");
		boolean all = true;
		for (var a : com.toast.aurapoints.ability.AuraType.values()) if (new HashSet<>(List.of(TrialPortal.stones(RealmBuilder.palette(a)))).size() != 4) all = false;
		log.accept("repair: four different stones for every aura " + all + " (must be true)");
		boolean pots = caches.size() == 3;
		for (BlockPos pos : caches) if (!level.getBlockState(pos).is(Blocks.DECORATED_POT)) pots = false;
		int want = MISSING[t.tier];
		log.accept("repair: broken " + !t.repaired + " (must be true), closed " + !t.gate.open + " (must be true), pots " + pots + " (must be true), missing " + missing.size() + " loose " + loose.size() + " (both must be " + want + ")");
		// the compass
		fake.getInventory().clearContent();
		fake.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
		Vec3 pp = Vec3.atBottomCenterOf(pedestal.offset(0, 0, 0)).add(t.gate.nx * 1.5, 0, t.gate.nz * 1.5);
		fake.moveTo(pp.x, pp.y, pp.z);
		use(level, fake, pedestal.above(2));
		LodestoneTracker tr = fake.getMainHandItem().get(DataComponents.LODESTONE_TRACKER);
		log.accept("repair: lodestone gives a compass " + isCompass(fake.getMainHandItem()) + " (must be true), at a pot " + (tr != null && tr.target().isPresent() && caches.contains(tr.target().get().pos())) + " (must be true)");
		use(level, fake, pedestal.above(2));
		int comps = 0;
		for (int i = 0; i < fake.getInventory().getContainerSize(); i++) if (isCompass(fake.getInventory().getItem(i))) comps++;
		log.accept("repair: only one compass each, has " + comps + " (must be 1)");
		// Keystones out of the pots; refused before the arch is whole; another wing's refused
		fake.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
		for (BlockPos pos : new ArrayList<>(caches)) use(level, fake, pos);
		log.accept("repair: pots emptied, Keystones " + count(afield(level, List.of(fake)), s -> myKeystone(s) ? s.getCount() : 0) + " (must be 3)");
		fake.setItemInHand(InteractionHand.MAIN_HAND, Trials.makeKeystone(t.run.id, t.index));
		use(level, fake, pedestal);
		log.accept("repair: Keystone on a broken arch -> check open " + SkillChecks.busy(fake) + " (must be false), set " + set + " (must be 0)");
		// the stones: lost ones come back, wrong kind and no support refused
		fake.getInventory().clearContent();
		for (BlockPos pos : new ArrayList<>(loose.keySet())) use(level, fake, pos);
		log.accept("repair: stones picked up " + count(afield(level, List.of(fake)), s -> mine(s, STONE) ? s.getCount() : 0) + " (must be " + want + "), left " + loose.size() + " (must be 0)");
		purge(fake);
		recoverLost(level, List.of(fake));
		log.accept("repair: lost stones come back " + loose.size() + " (must be " + want + "), lost Keystones too " + caches.size() + " (must be 3)");
		TrialPortal.Piece first = missing.values().iterator().next(), last = null;
		for (TrialPortal.Piece pc : missing.values()) last = pc;
		clickCd.clear();
		devAim(fake, first);
		log.accept("repair: aims at the slot " + (aimed(fake) == first) + " (must be true)");
		fake.setItemInHand(InteractionHand.MAIN_HAND, makeStone((first.kind() + 1) % 4));
		stoneUse(level, fake);
		log.accept("repair: wrong stone refused, still held " + isStone(fake.getMainHandItem()) + " (must be true), missing " + missing.size() + " (must be " + want + ")");
		clickCd.clear();
		devAim(fake, last);
		fake.setItemInHand(InteractionHand.MAIN_HAND, makeStone(last.kind()));
		stoneUse(level, fake);
		log.accept("repair: no support refused, missing " + missing.size() + " (must be " + want + ")");
		for (TrialPortal.Piece pc : new ArrayList<>(missing.values())) {
			clickCd.clear();
			devAim(fake, pc);
			fake.setItemInHand(InteractionHand.MAIN_HAND, makeStone(pc.kind()));
			stoneUse(level, fake);
		}
		int built = 0;
		for (TrialPortal.Piece pc : t.gate.frame(t.pal)) if (level.getBlockState(pc.pos()).is(pc.block())) built++;
		log.accept("repair: arch built in order, pieces " + built + "/" + t.gate.frame(t.pal).size() + " (must be all), whole " + whole() + " (must be true), ghosts left " + ghosts.size() + " (must be 0)");
		// rubbing the Keystones: early, late, a stale id, then good
		fake.moveTo(pp.x, pp.y, pp.z);
		fake.setItemInHand(InteractionHand.MAIN_HAND, Trials.makeKeystone(t.run.id, (t.index + 1) % 4));
		use(level, fake, pedestal);
		log.accept("repair: another wing's Keystone -> check open " + SkillChecks.busy(fake) + " (must be false)");
		for (int res : new int[]{SkillChecks.EARLY, SkillChecks.LATE, SkillChecks.CANCEL}) {
			fake.setItemInHand(InteractionHand.MAIN_HAND, Trials.makeKeystone(t.run.id, t.index));
			use(level, fake, pedestal);
			boolean on = SkillChecks.busy(fake);
			int id = SkillChecks.devId(fake);
			SkillChecks.answer(fake, id + 7, SkillChecks.GOOD);
			boolean kept = SkillChecks.busy(fake);
			SkillChecks.devAnswer(fake, res);
			SkillChecks.answer(fake, id, SkillChecks.GOOD);
			log.accept("repair: rub result " + res + ": check opened " + on + " (must be true), wrong id ignored " + kept + " (must be true), set " + set + " (must be 0), Keystone kept " + myKeystone(fake.getMainHandItem()) + " (must be true)");
		}
		use(level, fake, pedestal);
		fake.moveTo(pp.x + 20, pp.y, pp.z);
		SkillChecks.tick();
		log.accept("repair: walked off -> check gone " + !SkillChecks.busy(fake) + " (must be true)");
		fake.moveTo(pp.x, pp.y, pp.z);
		SkillChecks.clear();
		for (int k = 1; k <= 3; k++) {
			fake.setItemInHand(InteractionHand.MAIN_HAND, Trials.makeKeystone(t.run.id, t.index));
			use(level, fake, pedestal);
			SkillChecks.devAnswer(fake, SkillChecks.GOOD);
			log.accept("repair: Keystone " + k + " lit -> " + set + " (must be " + k + "), scene " + (sceneAt >= 0) + " (must be " + (k == 3) + ")");
		}
		for (int n = 0; n <= SCENE && !t.repaired; n++) tick(level, List.of(fake), List.of(fake), sceneAt + n);
		log.accept("repair: the scene ends open " + (t.repaired && t.gate.open) + " (must be true), flying stones gone " + flying.isEmpty() + " (must be true)");
		fake.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
		fake.getInventory().clearContent();
		fake.moveTo(stood.x, stood.y, stood.z);
		AuraConfig.RIFT_PORTAL_KEYSTONES = keep;
	}
}
