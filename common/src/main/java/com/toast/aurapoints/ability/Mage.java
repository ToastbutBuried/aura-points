package com.toast.aurapoints.ability;

import com.mojang.math.Transformation;
import com.toast.aurapoints.ability.AuraAbilities.Ability;
import com.toast.aurapoints.config.AuraConfig;
import com.toast.aurapoints.data.AuraPlayerData;
import com.toast.aurapoints.data.AuraState;
import com.toast.aurapoints.mixin.DisplayAccessor;
import com.toast.aurapoints.mixin.ItemDisplayAccessor;
import com.toast.aurapoints.network.BendingAnimPacket;
import com.toast.aurapoints.network.NetworkHandler;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Brightness;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomModelData;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The Mage aura (5.62.0): no cooldowns, every spell spends mana.
 * <ul>
 * <li>Mana: a pool saved per player ({@link AuraPlayerData#mageMana}) that grows with the mana level
 * ({@link AuraPlayerData#mageLevel}); hits, kills, spent mana, focusing and wisps give mana XP.</li>
 * <li>Focus (the passive, every branch): sneaking refills mana {@code MAGE_FOCUS_REGEN_FACTOR} times as fast, at once.</li>
 * <li>Arcane Bolt (tap shock / held chain), Arcane Barrier (absorption around yourself), Arcane Orb (held, grows, fired
 * on release): need the Apprentice Wand in the main hand.</li>
 * <li>Last Prism (Ultimate, held): the wand is put away (its model hidden through custom_model_data 1), six beams
 * converge into one.</li>
 * <li>Mana wisps: item displays dropped by mobs killed near a Mage, drifting into the nearest Mage.</li>
 * </ul>
 * Mage abilities have cooldownTicks() 0, so AuraAbilities.markUsed only reports the use (hooks, chains, stats).
 */
public final class Mage {

	private Mage() {
	}

	public static final int VIOLET = 0x7A6CFF, CYAN = 0x6FE7FF, PALE = 0xD9D2FF;
	private static final String TAG = "aurapoints_mage";
	/** The wand's custom_model_data while the Last Prism is out: an empty model (the wand is "put away"). */
	public static final int WAND_STOWED = 1;
	/** custom_model_data of the mage_fx item: the orb, the wisp, the prism. */
	public static final int FX_ORB = 1, FX_WISP = 2, FX_PRISM = 3;

	private static final ResourceLocation PRISM_SLOW = ResourceLocation.fromNamespaceAndPath("aurapoints", "mage_prism");
	private static final ResourceLocation ORB_SLOW = ResourceLocation.fromNamespaceAndPath("aurapoints", "mage_orb");

	/** Displays of ours that are alive (anything else with TAG is a leftover from a crash). */
	private static final Set<UUID> LIVE = new HashSet<>();

	public static boolean isLeftover(Entity e) {
		return e.getTags().contains(TAG) && !LIVE.contains(e.getUUID());
	}

	private static void sound(ServerLevel level, Vec3 at, SoundEvent sound, float volume, float pitch) {
		level.playSound(null, at.x, at.y, at.z, sound, SoundSource.PLAYERS, volume, pitch);
	}

	private static AuraPlayerData data(ServerPlayer p) {
		return AuraState.get(p.getServer()).getOrCreate(p.getUUID());
	}

	// ---------------------------------------------------------------
	// Mana pool, regen and levels
	// ---------------------------------------------------------------

	/** Per player, not saved: the tick of the last spend (regen waits after it) and of the last cast (MAGE_CAST_DELAY_TICKS). */
	private static final Map<UUID, Long> LAST_SPEND = new HashMap<>(), LAST_CAST = new HashMap<>();
	/** What the client was last told (mana in tenths, max, level, xp in hundredths, turn in hundredths): resend only on change. */
	private static final Map<UUID, int[]> SENT = new HashMap<>();

	public static int level(AuraPlayerData data) {
		return Mth.clamp(data.mageLevel, 1, Math.max(1, AuraConfig.MAGE_LEVEL_MAX));
	}

	public static int maxMana(AuraPlayerData data) {
		return Math.max(1, AuraConfig.MAGE_MANA_BASE + AuraConfig.MAGE_MANA_PER_LEVEL * (level(data) - 1));
	}

	/** Mana back per second (before Focus). */
	public static double regenPerSecond(AuraPlayerData data) {
		return Math.max(0, AuraConfig.MAGE_REGEN_PER_SECOND + AuraConfig.MAGE_REGEN_PER_LEVEL * (level(data) - 1));
	}

	/** Mana XP needed to go from {@code level} to the next. */
	public static double xpFor(int level) {
		return Math.max(1, AuraConfig.MAGE_XP_BASE * Math.pow(Math.max(1.0, AuraConfig.MAGE_XP_GROWTH), Math.max(0, level - 1)));
	}

	public static double mana(ServerPlayer p) {
		AuraPlayerData data = data(p);
		return data.mageMana < 0 ? maxMana(data) : data.mageMana;
	}

	private static void setMana(ServerPlayer p, AuraPlayerData data, double mana) {
		data.mageMana = Mth.clamp(mana, 0, maxMana(data));
		AuraState.get(p.getServer()).markDirty();
		sync(p, data, false);
	}

	/** Gives mana back (wisps, dev commands). */
	public static void addMana(ServerPlayer p, double amount) {
		if (amount <= 0) return;
		AuraPlayerData data = data(p);
		setMana(p, data, mana(p) + amount);
	}

	/** True if the spells are free right now: /aura cooldowns off, or the Training Hall with its cooldowns off. */
	private static boolean free(ServerPlayer p) {
		return AuraAbilities.cooldownsDisabled(p);
	}

	/** Spends {@code cost} or says how much is missing (and returns false). */
	static boolean spend(ServerPlayer p, double cost) {
		if (cost <= 0 || free(p)) return true;
		AuraPlayerData data = data(p);
		double have = mana(p);
		if (have + 1.0e-6 < cost) {
			p.displayClientMessage(Component.literal(String.format("Not enough mana: %d / %d", (int) Math.floor(have), (int) Math.ceil(cost)))
					.withStyle(ChatFormatting.BLUE), true);
			return false;
		}
		setMana(p, data, have - cost);
		LAST_SPEND.put(p.getUUID(), now(p));
		addXp(p, data, cost * AuraConfig.MAGE_XP_PER_MANA);
		return true;
	}

	/** Drains {@code amount} for a held spell; false (and the pool at 0) once there isn't enough left. */
	private static boolean drain(ServerPlayer p, double amount) {
		if (amount <= 0 || free(p)) return true;
		AuraPlayerData data = data(p);
		double have = mana(p);
		LAST_SPEND.put(p.getUUID(), now(p));
		if (have < amount) {
			setMana(p, data, 0);
			return false;
		}
		data.mageMana = have - amount;
		addXp(p, data, amount * AuraConfig.MAGE_XP_PER_MANA);
		sync(p, data, false);
		return true;
	}

	private static long now(ServerPlayer p) {
		return p.getServer() == null ? 0 : p.getServer().getTickCount();
	}

	/** MAGE_CAST_DELAY_TICKS between two casts (silent: a double press just doesn't fire twice). */
	private static boolean castReady(ServerPlayer p, long now) {
		Long last = LAST_CAST.get(p.getUUID());
		return last == null || now < last || now - last >= Math.max(0, AuraConfig.MAGE_CAST_DELAY_TICKS);
	}

	private static void casted(ServerPlayer p, long now) {
		LAST_CAST.put(p.getUUID(), now);
	}

	/** Mana XP, with the level-ups it brings (nothing while progress is paused: arena, cooldowns off). */
	static void addXp(ServerPlayer p, AuraPlayerData data, double xp) {
		if (xp <= 0 || AuraAbilities.progressPaused(p)) return;
		int max = Math.max(1, AuraConfig.MAGE_LEVEL_MAX);
		if (level(data) >= max) return;
		data.mageXp += xp;
		boolean up = false;
		while (level(data) < max && data.mageXp >= xpFor(level(data))) {
			data.mageXp -= xpFor(level(data));
			data.mageLevel = level(data) + 1;
			up = true;
		}
		if (level(data) >= max) data.mageXp = 0;
		AuraState.get(p.getServer()).markDirty();
		if (up) levelUp(p, data);
	}

	private static void levelUp(ServerPlayer p, AuraPlayerData data) {
		ServerLevel level = p.serverLevel();
		AuraFx.title(p, Component.literal("Mana Level " + level(data)).withStyle(ChatFormatting.BLUE, ChatFormatting.BOLD),
				Component.literal("Your mana pool holds " + maxMana(data) + " now").withStyle(ChatFormatting.GRAY));
		AuraFx.helix(p, AuraFx.dust(CYAN, 1.0f), 24, 0.8, 2.2);
		AuraFx.particles(level, ParticleTypes.ENCHANT, p.getX(), p.getY() + 1.2, p.getZ(), 60, 0.6, 0.8, 0.6, 0.6);
		sound(level, p.position(), AuraSounds.PLAYER_LEVELUP.value(), 0.7f, 1.4f);
		sound(level, p.position(), AuraSounds.AMETHYST_BLOCK_CHIME.value(), 1.6f, 0.8f);
		sync(p, data, true);
	}

	/** Tells the player's HUD their pool, level and progress (only what changed, unless {@code force}). */
	public static void sync(ServerPlayer p, AuraPlayerData data, boolean force) {
		boolean mage = data.aura == AuraType.MAGE;
		double mana = data.mageMana < 0 ? maxMana(data) : data.mageMana;
		int lvl = level(data);
		double xp = lvl >= AuraConfig.MAGE_LEVEL_MAX ? 1.0 : data.mageXp / xpFor(lvl);
		float turn = PRISMS.containsKey(p.getUUID()) ? (float) Mth.clamp(AuraConfig.MAGE_PRISM_TURN_FACTOR, 0.05, 1.0) : 1f;
		int[] now = {mage ? (int) Math.floor(mana * 10) : -1, maxMana(data), lvl, (int) Math.floor(xp * 100), Math.round(turn * 100)};
		int[] was = SENT.get(p.getUUID());
		if (!force && was != null && java.util.Arrays.equals(was, now)) return;
		SENT.put(p.getUUID(), now);
		NetworkHandler.sendMana(p, mage ? (float) mana : -1f, maxMana(data), lvl, (float) xp, turn, costs());
	}

	/** The spells' mana costs for the HUD: bolt, barrier, orb, prism. */
	private static String costs() {
		return Math.round(AuraConfig.MAGE_BOLT_COST) + "," + Math.round(AuraConfig.MAGE_BARRIER_COST) + ","
				+ Math.round(AuraConfig.MAGE_ORB_COST) + "," + Math.round(AuraConfig.MAGE_PRISM_COST);
	}

	/** Every server tick for every player: regen, Focus, the wand back after the Prism. */
	public static void tickPassives(ServerPlayer p, AuraPlayerData data, long now) {
		if (data.aura != AuraType.MAGE) {
			if (SENT.containsKey(p.getUUID()) && SENT.get(p.getUUID())[0] != -1) sync(p, data, true);
			return;
		}
		if (!data.mageWandGiven && p.isAlive()) giveWand(p, data);
		UUID id = p.getUUID();
		boolean channeling = BOLTS.containsKey(id) || ORBS.containsKey(id) || PRISMS.containsKey(id);
		boolean focus = p.isShiftKeyDown() && p.isAlive() && !p.isSpectator() && !channeling;
		double max = maxMana(data), mana = data.mageMana < 0 ? max : data.mageMana;
		if (!channeling && mana < max) {
			Long spent = LAST_SPEND.get(id);
			long since = spent == null || now < spent ? Long.MAX_VALUE : now - spent;
			// Focus shortens the wait after a spell, it doesn't skip it (no sneak-casting on free mana)
			boolean waited = since >= Math.max(0, focus ? AuraConfig.MAGE_FOCUS_DELAY_TICKS : AuraConfig.MAGE_REGEN_DELAY_TICKS);
			if (waited) {
				double perTick = regenPerSecond(data) / 20.0 * (focus ? Math.max(1.0, AuraConfig.MAGE_FOCUS_REGEN_FACTOR) : 1.0);
				data.mageMana = Math.min(max, mana + perTick);
				if (focus) addXp(p, data, AuraConfig.MAGE_FOCUS_XP_PER_SECOND / 20.0);
				if (now % 20 == 0) AuraState.get(p.getServer()).markDirty();
			}
		} else if (data.mageMana > max) {
			data.mageMana = max;
		}
		if (focus && now % 4 == 0 && !p.isInvisible()) focusFx(p, mana < max);
		if (now % 2 == 0) sync(p, data, false);
		if (now % 20 == 7 && !PRISMS.containsKey(id)) unstowWands(p);
	}

	/** Focusing: motes of mana drift up and in around you (brighter while the pool still fills). */
	private static void focusFx(ServerPlayer p, boolean filling) {
		ServerLevel level = p.serverLevel();
		var r = p.getRandom();
		double a = r.nextDouble() * Math.PI * 2, d = 0.9 + r.nextDouble() * 0.5;
		double x = p.getX() + Math.cos(a) * d, z = p.getZ() + Math.sin(a) * d, y = p.getY() + 0.2 + r.nextDouble() * 1.2;
		Vec3 to = AuraFx.chest(p).subtract(x, y, z).normalize();
		// count 0: the offsets are the mote's direction
		AuraFx.particles(level, filling ? AuraFx.dust(CYAN, 0.7f) : AuraFx.dust(VIOLET, 0.5f), x, y, z, 1, 0, 0, 0, 0);
		AuraFx.particles(level, ParticleTypes.ENCHANT, x, y + 0.6, z, 0, to.x, to.y, to.z, 0.6);
	}

	// ---------------------------------------------------------------
	// The wand
	// ---------------------------------------------------------------

	public static boolean isWand(ItemStack stack) {
		return !stack.isEmpty() && stack.is(com.toast.aurapoints.AuraPoints.APPRENTICE_WAND.get());
	}

	/** True with the wand in the main hand; otherwise tells the player. */
	private static boolean wandReady(ServerPlayer p) {
		if (isWand(p.getMainHandItem())) return true;
		p.displayClientMessage(Component.literal("Hold your Apprentice Wand to cast that (lost it? /aura wand)").withStyle(ChatFormatting.BLUE), true);
		return false;
	}

	/** The free wand a new Mage gets (once; /aura wand gives another if it is gone). */
	private static void giveWand(ServerPlayer p, AuraPlayerData data) {
		data.mageWandGiven = true;
		AuraState.get(p.getServer()).markDirty();
		if (hasWand(p)) return;
		ItemStack wand = new ItemStack(com.toast.aurapoints.AuraPoints.APPRENTICE_WAND.get());
		if (!p.getInventory().add(wand)) p.drop(wand, false);
		p.sendSystemMessage(Component.literal("You got an Apprentice Wand: Arcane Bolt, Barrier and Orb are cast with it in hand.")
				.withStyle(ChatFormatting.BLUE));
	}

	private static boolean hasWand(ServerPlayer p) {
		for (ItemStack s : p.getInventory().items) if (isWand(s)) return true;
		for (ItemStack s : p.getInventory().offhand) if (isWand(s)) return true;
		return false;
	}

	/** /aura wand: a new wand if the Mage has none. Returns what to tell them. */
	public static String wandCommand(ServerPlayer p) {
		AuraPlayerData data = data(p);
		if (data.aura != AuraType.MAGE) return "Only a Mage can carry an Apprentice Wand.";
		if (hasWand(p)) return "You already carry an Apprentice Wand.";
		ItemStack wand = new ItemStack(com.toast.aurapoints.AuraPoints.APPRENTICE_WAND.get());
		if (!p.getInventory().add(wand)) p.drop(wand, false);
		data.mageWandGiven = true;
		AuraState.get(p.getServer()).markDirty();
		return "Here is a new Apprentice Wand.";
	}

	/** The wand's look back after the Last Prism (any wand in the inventory still marked as put away). */
	private static void unstowWands(ServerPlayer p) {
		var inv = p.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack s = inv.getItem(i);
			if (isWand(s) && s.has(DataComponents.CUSTOM_MODEL_DATA)) s.remove(DataComponents.CUSTOM_MODEL_DATA);
		}
	}

	/** Where the wand's crystal is with the arm pointed at the aim (bolt, the fired orb). */
	private static Vec3 tipPointed(ServerPlayer p) {
		return Light.pointingHand(p).add(p.getLookAngle().scale(0.65));
	}

	/** The look tipped up by {@code degrees}. */
	private static Vec3 raised(ServerPlayer p, double degrees) {
		float yaw = p.getYRot() * Mth.DEG_TO_RAD, pitch = (float) (p.getXRot() - degrees) * Mth.DEG_TO_RAD;
		return new Vec3(-Mth.sin(yaw) * Mth.cos(pitch), -Mth.sin(pitch), Mth.cos(yaw) * Mth.cos(pitch));
	}

	/** Above the crystal of the raised wand (the forming orb). */
	private static Vec3 orbSpot(ServerPlayer p, double size) {
		float yaw = p.getYRot() * Mth.DEG_TO_RAD;
		Vec3 right = new Vec3(-Math.cos(yaw), 0, -Math.sin(yaw));
		Vec3 shoulder = p.position().add(0, p.isShiftKeyDown() ? 1.15 : 1.37, 0).add(right.scale(0.31));
		Vec3 up = raised(p, 25);
		return shoulder.add(up.scale(0.72 + 0.45)).add(0, 0.05 + size * 0.3, 0);
	}

	// ---------------------------------------------------------------
	// Targets and damage
	// ---------------------------------------------------------------

	/** What the bolt locks onto: the entity under the crosshair, else the one nearest it within MAGE_BOLT_LOCK_DEGREES, in sight. */
	private static LivingEntity lockOn(ServerPlayer p, double range) {
		ServerLevel level = p.serverLevel();
		Vec3 eye = p.getEyePosition(), look = p.getLookAngle();
		Vec3 end = eye.add(look.scale(range));
		HitResult wall = level.clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
		var direct = ProjectileUtil.getEntityHitResult(level, p, eye, wall.getLocation(), new AABB(eye, wall.getLocation()).inflate(1.0),
				e -> Handhelds.enemy(p, e), 0.3f);
		if (direct != null && direct.getEntity() instanceof LivingEntity l) return l;
		double cos = Math.cos(Math.toRadians(Math.max(0.5, AuraConfig.MAGE_BOLT_LOCK_DEGREES)));
		LivingEntity best = null;
		double bestDot = cos;
		for (LivingEntity e : Handhelds.enemies(p, level, eye.add(look.scale(range / 2)), range / 2 + 1)) {
			Vec3 to = AuraFx.chest(e).subtract(eye);
			double len = to.length();
			if (len < 1.0e-3 || len > range) continue;
			double dot = to.scale(1 / len).dot(look);
			if (dot <= bestDot) continue;
			if (level.clip(new ClipContext(eye, AuraFx.chest(e), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p)).getType() != HitResult.Type.MISS) continue;
			best = e;
			bestDot = dot;
		}
		return best;
	}

	/** Spell damage with mana XP for the hit (and the kill). */
	private static void hit(ServerPlayer p, LivingEntity target, float damage) {
		if (!target.isAlive() || damage <= 0) return;
		Handhelds.hurt(p, target, damage);
		AuraPlayerData data = data(p);
		if (target instanceof ArmorStand) return;
		addXp(p, data, AuraConfig.MAGE_XP_PER_HIT);
		if (!target.isAlive()) addXp(p, data, AuraConfig.MAGE_XP_PER_KILL);
	}

	/** A jagged arc of light from {@code from} to {@code to}. */
	private static void arc(ServerLevel level, Vec3 from, Vec3 to, float scale) {
		Vec3 d = to.subtract(from);
		double len = d.length();
		if (len < 1.0e-3) return;
		int n = Math.max(3, (int) (len * 2.2));
		var r = level.getRandom();
		Vec3 prev = from;
		for (int i = 1; i <= n; i++) {
			double t = i / (double) n;
			double jitter = i == n ? 0 : 0.22 * Math.sin(t * Math.PI);
			Vec3 at = from.add(d.scale(t)).add((r.nextDouble() - 0.5) * jitter * 2, (r.nextDouble() - 0.5) * jitter * 2, (r.nextDouble() - 0.5) * jitter * 2);
			AuraFx.line(level, prev, at, AuraFx.dust(i % 2 == 0 ? CYAN : PALE, scale * 1.3f), 4);
			prev = at;
		}
		AuraFx.particles(level, ParticleTypes.ELECTRIC_SPARK, to.x, to.y, to.z, 6, 0.15, 0.15, 0.15, 0.2);
	}

	// ---------------------------------------------------------------
	// Arcane Bolt: tap = quick shock, hold = charged (lone bonus or chain)
	// ---------------------------------------------------------------

	private static final class Bolt {
		final long started;
		int t;
		boolean charged;

		Bolt(long started) {
			this.started = started;
		}
	}

	private static final Map<UUID, Bolt> BOLTS = new HashMap<>();

	static boolean boltPress(ServerPlayer p, long now) {
		if (BOLTS.containsKey(p.getUUID()) || busy(p)) return false;
		if (!castReady(p, now) || !wandReady(p) || !spend(p, AuraConfig.MAGE_BOLT_COST)) return false;
		casted(p, now);
		BOLTS.put(p.getUUID(), new Bolt(now));
		NetworkHandler.sendBendAnim(p, BendingAnimPacket.WAND_POINT);
		return true;
	}

	public static void boltRelease(ServerPlayer p, long now) {
		Bolt b = BOLTS.remove(p.getUUID());
		if (b != null) fireBolt(p, b, now);
	}

	/** Returns false once the bolt is fired or dropped. */
	private static boolean tickBolt(ServerPlayer p, Bolt b, long now) {
		if (!p.isAlive() || !isWand(p.getMainHandItem()) || !AuraAbilities.hasAbility(data(p), Ability.ARCANE_BOLT)) {
			NetworkHandler.sendBendAnim(p, BendingAnimPacket.NONE);
			return false;
		}
		b.t++;
		ServerLevel level = p.serverLevel();
		Vec3 tip = tipPointed(p);
		// held: mana drains from the first tick (a quick tap costs a little more than its price, a charge a lot more)
		if (!drain(p, AuraConfig.MAGE_BOLT_DRAIN) || b.t >= Math.max(AuraConfig.MAGE_BOLT_HOLD_TICKS + 1, AuraConfig.MAGE_BOLT_MAX_HOLD_TICKS)) {
			fireBolt(p, b, now);
			return false;
		}
		if (b.t >= Math.max(1, AuraConfig.MAGE_BOLT_HOLD_TICKS)) {
			if (!b.charged) {
				b.charged = true;
				sound(level, tip, AuraSounds.CHARGE_THUNDER.value(), 0.6f, 1.6f);
			}
			// charging: sparks gather on the crystal
			AuraFx.particles(level, ParticleTypes.ELECTRIC_SPARK, tip.x, tip.y, tip.z, 2, 0.08, 0.08, 0.08, 0.05);
			if (b.t % 2 == 0) AuraFx.particles(level, AuraFx.dust(CYAN, 0.6f), tip.x, tip.y, tip.z, 1, 0.05, 0.05, 0.05, 0);
		} else if (b.t % 3 == 0) {
			AuraFx.particles(level, AuraFx.dust(VIOLET, 0.5f), tip.x, tip.y, tip.z, 1, 0.03, 0.03, 0.03, 0);
		}
		return true;
	}

	private static void fireBolt(ServerPlayer p, Bolt b, long now) {
		ServerLevel level = p.serverLevel();
		Vec3 tip = tipPointed(p);
		double range = Math.max(4, AuraConfig.MAGE_BOLT_RANGE);
		LivingEntity target = lockOn(p, range);
		boolean charged = b.charged;
		float boost = AuraFx.boost;
		AuraFx.boost = Mastery.particleBoost(p);
		try {
			if (target == null) {
				// nothing to lock onto: the shock grounds where you point
				Vec3 eye = p.getEyePosition();
				Vec3 to = level.clip(new ClipContext(eye, eye.add(p.getLookAngle().scale(range)), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p)).getLocation();
				arc(level, tip, to, charged ? 1.0f : 0.7f);
			} else if (!charged) {
				arc(level, tip, AuraFx.chest(target), 0.7f);
				hit(p, target, AuraConfig.MAGE_BOLT_DAMAGE);
			} else {
				// the charged bolt: alone, the target takes it all (and more); with company it jumps on and the damage is shared
				List<LivingEntity> chain = new ArrayList<>();
				chain.add(target);
				LivingEntity from = target;
				double r = Math.max(0.5, AuraConfig.MAGE_BOLT_CHAIN_RADIUS);
				while (chain.size() <= Math.max(0, AuraConfig.MAGE_BOLT_CHAIN_TARGETS)) {
					LivingEntity next = null;
					double best = Double.MAX_VALUE;
					for (LivingEntity e : Handhelds.enemies(p, level, AuraFx.chest(from), r)) {
						if (chain.contains(e)) continue;
						double d = e.distanceToSqr(from);
						if (d < best) {
							best = d;
							next = e;
						}
					}
					if (next == null) break;
					chain.add(next);
					from = next;
				}
				Vec3 prev = tip;
				if (chain.size() == 1) {
					arc(level, prev, AuraFx.chest(target), 1.2f);
					hit(p, target, (float) (AuraConfig.MAGE_BOLT_CHARGED_DAMAGE * Math.max(1.0, AuraConfig.MAGE_BOLT_LONE_FACTOR)));
					Vec3 c = AuraFx.chest(target);
					AuraFx.particles(level, ParticleTypes.FLASH, c.x, c.y, c.z, 1, 0, 0, 0, 0);
				} else {
					float each = AuraConfig.MAGE_BOLT_CHARGED_DAMAGE / chain.size();
					for (LivingEntity e : chain) {
						Vec3 c = AuraFx.chest(e);
						arc(level, prev, c, 1.0f);
						hit(p, e, each);
						prev = c;
					}
				}
			}
		} finally {
			AuraFx.boost = boost;
		}
		sound(level, tip, AuraSounds.LIGHTNING_BOLT_IMPACT.value(), charged ? 0.7f : 0.35f, charged ? 1.5f : 1.9f);
		sound(level, tip, AuraSounds.AMETHYST_BLOCK_HIT.value(), 1.2f, charged ? 0.9f : 1.4f);
		NetworkHandler.sendBendAnim(p, BendingAnimPacket.NONE);
		NetworkHandler.sendGesture(p, BendingAnimPacket.GESTURE_MAGE, charged ? 1 : 0);
		AuraAbilities.markUsed(p, Ability.ARCANE_BOLT, now);
	}

	// ---------------------------------------------------------------
	// Arcane Barrier: absorption around yourself
	// ---------------------------------------------------------------

	/** player -> the tick the barrier fades */
	private static final Map<UUID, Long> BARRIERS = new HashMap<>();

	static boolean barrier(ServerPlayer p, long now) {
		if (busy(p) || !castReady(p, now) || !wandReady(p) || !spend(p, AuraConfig.MAGE_BARRIER_COST)) return false;
		casted(p, now);
		int ticks = Math.max(20, AuraConfig.MAGE_BARRIER_TICKS);
		float amount = Math.max(1f, AuraConfig.MAGE_BARRIER_ABSORB);
		// 1.21: absorption is capped by the max-absorption attribute, which only the effect raises (4 per level)
		int amp = Math.max(0, (int) Math.ceil(amount / 4f) - 1);
		float before = p.getAbsorptionAmount();
		MobEffectInstance had = p.getEffect(MobEffects.ABSORPTION);
		if (had == null || had.getAmplifier() <= amp) p.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, ticks, amp, false, false, true));
		// the effect fills in steps of 4: the shield is exactly ABSORB (or what was there, if more)
		p.setAbsorptionAmount(Math.max(before, amount));
		BARRIERS.put(p.getUUID(), now + ticks);
		ServerLevel level = p.serverLevel();
		float boost = AuraFx.boost;
		AuraFx.boost = Mastery.particleBoost(p);
		try {
			// runes snap shut around the body: two rings closing in, a flash of glyphs
			for (int k = 0; k < 3; k++) {
				final int ring = k;
				AuraFx.later(level.getServer(), k * 2, () -> {
					double rr = 1.4 - ring * 0.3;
					AuraFx.ring(level, p.getX(), p.getY() + 0.1, p.getZ(), rr, AuraFx.dust(ring == 2 ? PALE : VIOLET, 1.0f), 22);
					AuraFx.ring(level, p.getX(), p.getY() + 1.1, p.getZ(), rr * 0.8, AuraFx.dust(CYAN, 0.8f), 16);
				});
			}
			AuraFx.particles(level, ParticleTypes.ENCHANT, p.getX(), p.getY() + 1.0, p.getZ(), 40, 0.5, 0.7, 0.5, 0.8);
		} finally {
			AuraFx.boost = boost;
		}
		sound(level, p.position(), AuraSounds.ENCHANTMENT_TABLE_USE.value(), 1.0f, 1.2f);
		sound(level, p.position(), AuraSounds.AMETHYST_BLOCK_RESONATE.value(), 1.6f, 1.3f);
		NetworkHandler.sendGesture(p, BendingAnimPacket.GESTURE_MAGE, 2);
		AuraAbilities.markUsed(p, Ability.ARCANE_BARRIER, now);
		return true;
	}

	private static void tickBarriers(MinecraftServer server, long now) {
		if (BARRIERS.isEmpty()) return;
		var it = BARRIERS.entrySet().iterator();
		while (it.hasNext()) {
			var e = it.next();
			ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
			if (p == null || !p.isAlive() || now >= e.getValue() || p.getAbsorptionAmount() <= 0.01f) {
				if (p != null && p.isAlive() && now < e.getValue()) {
					// broken: the runes scatter
					sound(p.serverLevel(), p.position(), AuraSounds.AMETHYST_CLUSTER_BREAK.value(), 1.2f, 1.1f);
					AuraFx.particles(p.serverLevel(), AuraFx.dust(VIOLET, 0.9f), p.getX(), p.getY() + 1, p.getZ(), 16, 0.5, 0.6, 0.5, 0.05);
				}
				it.remove();
				continue;
			}
			if (now % 5 == 0 && !p.isInvisible()) {
				// a slow ring of runes turning close around the waist, and a shimmer over the body now and then
				double a = (now % 120) / 120.0 * Math.PI * 2;
				ServerLevel level = p.serverLevel();
				for (int i = 0; i < 4; i++) {
					double b = a + i * Math.PI / 2;
					AuraFx.particles(level, AuraFx.dust(i % 2 == 0 ? VIOLET : CYAN, 0.6f), p.getX() + Math.cos(b) * 0.65, p.getY() + 0.95,
							p.getZ() + Math.sin(b) * 0.65, 1, 0, 0, 0, 0);
				}
				if (now % 20 == 0) AuraFx.particles(level, ParticleTypes.ENCHANT, p.getX(), p.getY() + 1.0, p.getZ(), 4, 0.35, 0.6, 0.35, 0.2);
			}
		}
	}

	// ---------------------------------------------------------------
	// Arcane Orb: hold to grow it over the raised wand, release to fire
	// ---------------------------------------------------------------

	private static final class Orb {
		final UUID owner;
		final ServerLevel level;
		final Display.ItemDisplay ball;
		int t;
		double size;

		Orb(UUID owner, ServerLevel level, Display.ItemDisplay ball) {
			this.owner = owner;
			this.level = level;
			this.ball = ball;
		}
	}

	private static final class Shot {
		final UUID owner;
		final ServerLevel level;
		final Display.ItemDisplay ball;
		Vec3 pos;
		final Vec3 dir;
		final double size;
		double flown;

		Shot(UUID owner, ServerLevel level, Display.ItemDisplay ball, Vec3 pos, Vec3 dir, double size) {
			this.owner = owner;
			this.level = level;
			this.ball = ball;
			this.pos = pos;
			this.dir = dir;
			this.size = size;
		}
	}

	private static final Map<UUID, Orb> ORBS = new HashMap<>();
	private static final List<Shot> SHOTS = new ArrayList<>();

	private static Display.ItemDisplay fx(ServerLevel level, Vec3 at, int model, float scale) {
		Display.ItemDisplay d = new Display.ItemDisplay(EntityType.ITEM_DISPLAY, level);
		d.setPos(at.x, at.y, at.z);
		ItemStack stack = new ItemStack(com.toast.aurapoints.AuraPoints.MAGE_FX.get());
		stack.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(model));
		((ItemDisplayAccessor) d).aurapoints$setItemStack(stack);
		((ItemDisplayAccessor) d).aurapoints$setItemTransform(ItemDisplayContext.FIXED);
		DisplayAccessor acc = (DisplayAccessor) d;
		acc.aurapoints$setPosRotInterpolationDuration(1);
		acc.aurapoints$setViewRange(2.0f);
		acc.aurapoints$setBrightnessOverride(new Brightness(15, 15));
		if (model != FX_PRISM) acc.aurapoints$setBillboardConstraints(Display.BillboardConstraints.CENTER);
		acc.aurapoints$setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(scale), null));
		d.addTag(TAG);
		LIVE.add(d.getUUID());
		level.addFreshEntity(d);
		return d;
	}

	private static void scale(Display.ItemDisplay d, float scale, int ticks) {
		DisplayAccessor acc = (DisplayAccessor) d;
		acc.aurapoints$setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(scale), null));
		acc.aurapoints$setInterpolationDuration(ticks);
		d.getEntityData().set(DisplayAccessor.aurapoints$startDelta(), 0, true);
	}

	private static void removeFx(Display.ItemDisplay d) {
		if (d == null) return;
		LIVE.remove(d.getUUID());
		d.discard();
	}

	/** 0..1 of the orb's growth. */
	private static double orbGrowth(Orb o) {
		return Mth.clamp(o.t / (double) Math.max(1, AuraConfig.MAGE_ORB_MAX_HOLD_TICKS), 0, 1);
	}

	private static float orbScale(double growth) {
		return (float) (0.18 + 0.5 * growth);
	}

	static boolean orbPress(ServerPlayer p, long now) {
		if (ORBS.containsKey(p.getUUID()) || busy(p)) return false;
		if (!castReady(p, now) || !wandReady(p) || !spend(p, AuraConfig.MAGE_ORB_COST)) return false;
		casted(p, now);
		ServerLevel level = p.serverLevel();
		Orb o = new Orb(p.getUUID(), level, fx(level, orbSpot(p, 0), FX_ORB, orbScale(0)));
		ORBS.put(p.getUUID(), o);
		AttributeInstance speed = p.getAttribute(Attributes.MOVEMENT_SPEED);
		if (speed != null) {
			speed.removeModifier(ORB_SLOW);
			speed.addTransientModifier(new AttributeModifier(ORB_SLOW, -0.25, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
		}
		NetworkHandler.sendBendAnim(p, BendingAnimPacket.WAND_RAISE);
		sound(level, orbSpot(p, 0), AuraSounds.CHARGE_CORE.value(), 0.8f, 1.3f);
		return true;
	}

	public static void orbRelease(ServerPlayer p, long now) {
		Orb o = ORBS.remove(p.getUUID());
		if (o != null) fireOrb(p, o, now);
	}

	private static void endOrbPose(ServerPlayer p) {
		AttributeInstance speed = p.getAttribute(Attributes.MOVEMENT_SPEED);
		if (speed != null) speed.removeModifier(ORB_SLOW);
	}

	/** Returns false once the orb left the hand (fired or dropped). */
	private static boolean tickOrb(ServerPlayer p, Orb o, long now) {
		if (!p.isAlive() || p.level() != o.level || !isWand(p.getMainHandItem()) || !AuraAbilities.hasAbility(data(p), Ability.ARCANE_ORB)) {
			dropOrb(p, o);
			return false;
		}
		boolean grow = o.t < Math.max(1, AuraConfig.MAGE_ORB_MAX_HOLD_TICKS);
		if (grow && !drain(p, AuraConfig.MAGE_ORB_DRAIN)) {
			// dry: it goes off with what it has
			fireOrb(p, o, now);
			return false;
		}
		if (grow) o.t++;
		double g = orbGrowth(o);
		o.size = g;
		Vec3 at = orbSpot(p, orbScale(g));
		o.ball.setPos(at.x, at.y, at.z);
		if (o.t % 2 == 0) scale(o.ball, orbScale(g) * (1f + 0.06f * Mth.sin(o.t * 0.7f)), 2);
		ServerLevel level = o.level;
		// motes appear around it and fall into it, more and from further out as it grows; full = they swirl
		var r = level.getRandom();
		int motes = grow ? 1 + (int) (g * 3) : 2;
		for (int i = 0; i < motes; i++) {
			double dist = 0.7 + g * 1.1 + r.nextDouble() * 0.5;
			Vec3 off = new Vec3(r.nextDouble() - 0.5, r.nextDouble() - 0.5, r.nextDouble() - 0.5).normalize().scale(dist);
			Vec3 from = at.add(off);
			AuraFx.particles(level, AuraFx.dust(i % 2 == 0 ? CYAN : PALE, 0.4f + (float) g * 0.3f), from.x, from.y, from.z, 1, 0, 0, 0, 0);
			Vec3 in = off.scale(-1).normalize();
			AuraFx.particles(level, ParticleTypes.ENCHANT, from.x, from.y, from.z, 0, in.x, in.y, in.z, dist * 0.5);
		}
		if (o.t % 10 == 0 && grow) sound(level, at, AuraSounds.AMETHYST_BLOCK_CHIME.value(), 0.6f + (float) g * 0.6f, 0.8f + (float) g * 0.8f);
		if (!grow && o.t % 20 == 0) sound(level, at, AuraSounds.CHARGE_READY.value(), 0.5f, 1.2f);
		if (o.t == Math.max(1, AuraConfig.MAGE_ORB_MAX_HOLD_TICKS)) {
			o.t++;
			sound(level, at, AuraSounds.CHARGE_READY.value(), 0.9f, 1.0f);
			AuraFx.particles(level, AuraFx.dust(PALE, 1.0f), at.x, at.y, at.z, 10, 0.25, 0.25, 0.25, 0);
		}
		return true;
	}

	private static void dropOrb(ServerPlayer p, Orb o) {
		removeFx(o.ball);
		if (p != null) {
			endOrbPose(p);
			NetworkHandler.sendBendAnim(p, BendingAnimPacket.NONE);
			Vec3 at = o.ball.position();
			AuraFx.particles(o.level, AuraFx.dust(VIOLET, 0.8f), at.x, at.y, at.z, 10, 0.2, 0.2, 0.2, 0.02);
			sound(o.level, at, AuraSounds.AMETHYST_BLOCK_BREAK.value(), 0.8f, 1.4f);
		}
	}

	private static void fireOrb(ServerPlayer p, Orb o, long now) {
		endOrbPose(p);
		double g = orbGrowth(o);
		Vec3 eye = p.getEyePosition();
		Vec3 start = o.ball.position();
		// it flies at the crosshair (from where it sits over the wand)
		Vec3 aim = Handhelds.aim(p, Math.max(4, AuraConfig.MAGE_ORB_RANGE));
		Vec3 dir = aim.subtract(start);
		if (dir.lengthSqr() < 1.0e-4) dir = p.getLookAngle();
		dir = dir.normalize();
		if (aim.distanceToSqr(eye) < 4) dir = p.getLookAngle();
		SHOTS.add(new Shot(p.getUUID(), o.level, o.ball, start, dir, g));
		sound(o.level, start, AuraSounds.BREEZE_SHOOT.value(), 0.9f, 1.3f);
		sound(o.level, start, AuraSounds.ILLUSIONER_CAST_SPELL.value(), 1.0f, 1.2f);
		NetworkHandler.sendBendAnim(p, BendingAnimPacket.WAND_POINT);
		AuraFx.later(p.getServer(), 6, () -> {
			if (!ORBS.containsKey(p.getUUID()) && !BOLTS.containsKey(p.getUUID())) NetworkHandler.sendBendAnim(p, BendingAnimPacket.NONE);
		});
		AuraAbilities.markUsed(p, Ability.ARCANE_ORB, now);
	}

	/** Returns false once the shot burst (or flew its range). */
	private static boolean tickShot(MinecraftServer server, Shot s) {
		ServerPlayer p = server.getPlayerList().getPlayer(s.owner);
		if (p == null || p.level() != s.level || s.ball.isRemoved()) {
			removeFx(s.ball);
			return false;
		}
		double speed = Math.max(0.2, AuraConfig.MAGE_ORB_SPEED);
		Vec3 next = s.pos.add(s.dir.scale(speed));
		ServerLevel level = s.level;
		HitResult wall = level.clip(new ClipContext(s.pos, next, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
		Vec3 end = wall.getType() == HitResult.Type.MISS ? next : wall.getLocation();
		double hitR = 0.3 + s.size * 0.5;
		var entityHit = ProjectileUtil.getEntityHitResult(level, p, s.pos, end, new AABB(s.pos, end).inflate(hitR + 0.5),
				e -> Handhelds.enemy(p, e), (float) hitR);
		s.flown += speed;
		if (entityHit != null || wall.getType() != HitResult.Type.MISS || s.flown > Math.max(4, AuraConfig.MAGE_ORB_RANGE)) {
			burst(p, s, entityHit != null ? entityHit.getLocation() : end);
			return false;
		}
		s.pos = end;
		s.ball.setPos(end.x, end.y, end.z);
		AuraFx.particles(level, AuraFx.dust(VIOLET, 0.6f + (float) s.size * 0.5f), end.x, end.y, end.z, 2, 0.08, 0.08, 0.08, 0);
		if (s.flown % 2 < speed) AuraFx.particles(level, ParticleTypes.END_ROD, end.x, end.y, end.z, 1, 0.05, 0.05, 0.05, 0.01);
		return true;
	}

	private static void burst(ServerPlayer p, Shot s, Vec3 at) {
		removeFx(s.ball);
		ServerLevel level = s.level;
		double g = s.size;
		double r = Mth.lerp(g, AuraConfig.MAGE_ORB_MIN_RADIUS, AuraConfig.MAGE_ORB_MAX_RADIUS);
		float damage = (float) Mth.lerp(g, AuraConfig.MAGE_ORB_MIN_DAMAGE, AuraConfig.MAGE_ORB_MAX_DAMAGE);
		float boost = AuraFx.boost;
		AuraFx.boost = Mastery.particleBoost(p);
		try {
			for (LivingEntity e : Handhelds.enemies(p, level, at, Math.max(0.5, r) + 0.5)) {
				hit(p, e, damage);
				Vec3 push = AuraFx.chest(e).subtract(at);
				if (push.lengthSqr() > 1.0e-4) {
					push = push.normalize().scale(0.3 + g * 0.5);
					e.push(push.x, 0.15 + g * 0.2, push.z);
					e.hurtMarked = true;
				}
			}
			AuraFx.particles(level, ParticleTypes.FLASH, at.x, at.y, at.z, 1, 0, 0, 0, 0);
			AuraFx.burst(level, at.x, at.y, at.z, AuraFx.dust(CYAN, 1.2f), (int) (14 + g * 30), r * 0.5, 0);
			AuraFx.burst(level, at.x, at.y, at.z, AuraFx.dust(VIOLET, 1.4f), (int) (14 + g * 30), r * 0.7, 0);
			AuraFx.particles(level, ParticleTypes.END_ROD, at.x, at.y, at.z, (int) (8 + g * 20), 0.1, 0.1, 0.1, 0.12 + g * 0.15);
			AuraFx.ring(level, at.x, at.y, at.z, r, AuraFx.dust(PALE, 1.0f), (int) (r * 10) + 8);
		} finally {
			AuraFx.boost = boost;
		}
		sound(level, at, AuraSounds.BREEZE_WIND_CHARGE_BURST.value(), 0.8f + (float) g * 0.6f, 1.4f - (float) g * 0.4f);
		sound(level, at, AuraSounds.AMETHYST_CLUSTER_BREAK.value(), 1.4f, 0.8f + (float) (1 - g) * 0.6f);
	}

	// ---------------------------------------------------------------
	// Last Prism (Ultimate, held)
	// ---------------------------------------------------------------

	/** Rainbow, red to violet (stained glass for the six beams). */
	private static final BlockState[] RAINBOW = {
			Blocks.RED_CONCRETE.defaultBlockState(), Blocks.ORANGE_CONCRETE.defaultBlockState(),
			Blocks.YELLOW_CONCRETE.defaultBlockState(), Blocks.LIME_CONCRETE.defaultBlockState(),
			Blocks.LIGHT_BLUE_CONCRETE.defaultBlockState(), Blocks.PURPLE_CONCRETE.defaultBlockState()};
	private static final int[] RAINBOW_RGB = {0xFF4A4A, 0xFFA03A, 0xFFE94A, 0x7CFF5A, 0x5AD8FF, 0xB45AFF};

	private static final class Prism {
		final UUID owner;
		final ServerLevel level;
		final Display.ItemDisplay prism;
		final Display.BlockDisplay[] beams = new Display.BlockDisplay[6];
		Display.BlockDisplay core, glow;
		int t;
		double spin;

		Prism(UUID owner, ServerLevel level, Display.ItemDisplay prism) {
			this.owner = owner;
			this.level = level;
			this.prism = prism;
		}
	}

	private static final Map<UUID, Prism> PRISMS = new HashMap<>();

	public static boolean isChanneling(ServerPlayer p) {
		return PRISMS.containsKey(p.getUUID());
	}

	static boolean prismPress(ServerPlayer p, long now) {
		if (PRISMS.containsKey(p.getUUID()) || busy(p)) return false;
		if (!castReady(p, now) || !spend(p, AuraConfig.MAGE_PRISM_COST)) return false;
		casted(p, now);
		dropHeld(p);
		ServerLevel level = p.serverLevel();
		// the wand is put away: its model goes empty while the Prism is out
		ItemStack hand = p.getMainHandItem();
		if (isWand(hand)) hand.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(WAND_STOWED));
		Prism pr = new Prism(p.getUUID(), level, fx(level, prismSpot(p), FX_PRISM, 0.32f));
		var zero = Props.along(Vec3.ZERO, p.getLookAngle(), 0.001f, 0.001f);
		for (int i = 0; i < 6; i++) {
			pr.beams[i] = Props.spawn(level, prismSpot(p), RAINBOW[i], zero, -1, 1);
			Props.glow(pr.beams[i]);
		}
		PRISMS.put(p.getUUID(), pr);
		AttributeInstance speed = p.getAttribute(Attributes.MOVEMENT_SPEED);
		if (speed != null) {
			speed.removeModifier(PRISM_SLOW);
			speed.addTransientModifier(new AttributeModifier(PRISM_SLOW, -0.45, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
		}
		NetworkHandler.sendBendAnim(p, BendingAnimPacket.PRISM_AIM);
		sync(p, data(p), true);
		sound(level, p.position(), AuraSounds.BEACON_ACTIVATE.value(), 1.2f, 1.5f);
		sound(level, p.position(), AuraSounds.AMETHYST_BLOCK_RESONATE.value(), 1.6f, 0.7f);
		com.toast.aurapoints.AuraPoints.LOGGER.info("[ultimate] {} used Last Prism", p.getGameProfile().getName());
		AuraAbilities.markUsed(p, Ability.LAST_PRISM, now);
		return true;
	}

	public static void prismRelease(ServerPlayer p, long now) {
		Prism pr = PRISMS.remove(p.getUUID());
		if (pr != null) endPrism(p, pr);
	}

	/** Held in front of the hands. */
	private static Vec3 prismSpot(ServerPlayer p) {
		return Light.pointingHand(p).add(p.getLookAngle().scale(0.25));
	}

	private static void endPrism(ServerPlayer p, Prism pr) {
		removeFx(pr.prism);
		for (Display.BlockDisplay b : pr.beams) if (b != null) Props.remove(b);
		if (pr.core != null) Props.remove(pr.core);
		if (pr.glow != null) Props.remove(pr.glow);
		if (p == null) return;
		AttributeInstance speed = p.getAttribute(Attributes.MOVEMENT_SPEED);
		if (speed != null) speed.removeModifier(PRISM_SLOW);
		unstowWands(p);
		NetworkHandler.sendBendAnim(p, BendingAnimPacket.NONE);
		sync(p, data(p), true);
		sound(pr.level, p.position(), AuraSounds.BEACON_DEACTIVATE.value(), 1.0f, 1.4f);
	}

	/** 0..1: how far the six beams have closed in. */
	private static double converged(Prism pr) {
		return Mth.clamp(pr.t / (double) Math.max(1, AuraConfig.MAGE_PRISM_CONVERGE_TICKS), 0, 1);
	}

	/** Returns false when the channel is over. */
	private static boolean tickPrism(MinecraftServer server, Prism pr) {
		ServerPlayer p = server.getPlayerList().getPlayer(pr.owner);
		if (p == null || !p.isAlive() || p.isSpectator() || p.level() != pr.level
				|| !AuraAbilities.hasAbility(AuraState.get(server).getOrCreate(p.getUUID()), Ability.LAST_PRISM)
				|| pr.t > Math.max(20, AuraConfig.MAGE_PRISM_MAX_TICKS)) {
			endPrism(p, pr);
			return false;
		}
		double c = converged(pr);
		if (!drain(p, Mth.lerp(c, AuraConfig.MAGE_PRISM_DRAIN_START, AuraConfig.MAGE_PRISM_DRAIN_FULL))) {
			p.displayClientMessage(Component.literal("Out of mana").withStyle(ChatFormatting.BLUE), true);
			endPrism(p, pr);
			return false;
		}
		pr.t++;
		ServerLevel level = pr.level;
		Vec3 from = prismSpot(p);
		// the beams leave the prism in the hand but meet where the crosshair is, not parallel to the view
		Vec3 eye = p.getEyePosition();
		double reach = Math.max(4, AuraConfig.MAGE_PRISM_RANGE);
		Vec3 aimAt = level.clip(new ClipContext(eye, eye.add(p.getLookAngle().scale(reach)), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p)).getLocation();
		Vec3 look = aimAt.subtract(from);
		look = look.lengthSqr() < 0.25 ? p.getLookAngle() : look.normalize();
		pr.prism.setPos(from.x, from.y, from.z);
		pr.prism.setYRot(p.getYRot());
		pr.prism.setXRot(p.getXRot());
		double range = Math.max(4, AuraConfig.MAGE_PRISM_RANGE);
		boolean hitTick = pr.t % Math.max(1, AuraConfig.MAGE_PRISM_HIT_TICKS) == 0;
		// the spin speeds up as the beams close in, like the Terraria original
		pr.spin += 0.08 + 0.32 * c;
		if (c < 1) {
			// six beams, fanned out around the aim and spiralling inward
			Vec3 side = look.cross(new Vec3(0, 1, 0));
			if (side.lengthSqr() < 1.0e-4) side = new Vec3(1, 0, 0);
			side = side.normalize();
			Vec3 up = side.cross(look).normalize();
			double spread = Math.toRadians(14) * (1 - c);
			for (int i = 0; i < 6; i++) {
				double phi = pr.spin + i * Math.PI / 3;
				Vec3 off = side.scale(Math.cos(phi)).add(up.scale(Math.sin(phi)));
				Vec3 dir = look.scale(Math.cos(spread)).add(off.scale(Math.sin(spread))).normalize();
				LivingEntity target = beam(p, pr.beams[i], from, dir, range, 0.12f + 0.06f * (float) c, RAINBOW_RGB[i]);
				if (target != null && hitTick) hit(p, target, AuraConfig.MAGE_PRISM_DAMAGE);
			}
			if (pr.t % 20 == 1) sound(level, from, AuraSounds.BEACON_AMBIENT.value(), 1.0f, 1.2f + (float) c * 0.8f);
		} else {
			// converged: one thick white beam in a rainbow glow, the six put away
			if (pr.core == null) {
				var zero = Props.along(Vec3.ZERO, look, 0.001f, 0.001f);
				pr.core = Props.spawn(level, from, Blocks.WHITE_CONCRETE.defaultBlockState(), zero, -1, 1);
				pr.glow = Props.spawn(level, from, Blocks.PINK_CONCRETE.defaultBlockState(), zero, -1, 1);
				Props.glow(pr.core);
				Props.glow(pr.glow);
				for (Display.BlockDisplay b : pr.beams) Props.set(b, zero, 1);
				// a burst of rainbow where the beams meet (no FLASH: it sits at the hand and whites out the player's own view)
				for (int i = 0; i < 6; i++) AuraFx.particles(level, AuraFx.dust(RAINBOW_RGB[i], 1.2f), from.x, from.y, from.z, 3, 0.15, 0.15, 0.15, 0);
				sound(level, from, AuraSounds.WARDEN_SONIC_CHARGE.value(), 0.7f, 1.8f);
				sound(level, from, AuraSounds.BEACON_POWER_SELECT.value(), 1.2f, 1.3f);
			}
			float pulse = 1f + 0.12f * Mth.sin(pr.t * 0.9f);
			// the white core runs a hair ahead of a thinner pink sheath behind it, so both show (no see-through glass)
			LivingEntity target = beam(p, pr.core, from, look, range, 0.22f * pulse, 0xFFFFFF);
			beam(p, pr.glow, from.subtract(look.scale(0.05)), look, range, 0.14f, RAINBOW_RGB[(pr.t / 3) % 6]);
			// rainbow motes spiralling round the beam
			for (int k = 0; k < 3; k++) {
				double along = level.getRandom().nextDouble() * Math.min(range, 16);
				double a = pr.t * 0.6 + k * Math.PI * 2 / 3;
				Vec3 side = look.cross(new Vec3(0, 1, 0));
				if (side.lengthSqr() < 1.0e-4) side = new Vec3(1, 0, 0);
				side = side.normalize();
				Vec3 up = side.cross(look).normalize();
				Vec3 at = from.add(look.scale(along)).add(side.scale(Math.cos(a) * 0.35)).add(up.scale(Math.sin(a) * 0.35));
				AuraFx.particles(level, AuraFx.dust(RAINBOW_RGB[(pr.t + k * 2) % 6], 0.8f), at.x, at.y, at.z, 1, 0, 0, 0, 0);
			}
			if (target != null && hitTick) hit(p, target, (float) (AuraConfig.MAGE_PRISM_DAMAGE * Math.max(1.0, AuraConfig.MAGE_PRISM_FULL_FACTOR)));
			if (pr.t % 16 == 0) sound(level, from, AuraSounds.BEACON_AMBIENT.value(), 1.4f, 2.0f);
		}
		return true;
	}

	/** Aims one beam display from {@code from} along {@code dir}: stops at walls and at the first enemy, which it returns. */
	private static LivingEntity beam(ServerPlayer p, Display.BlockDisplay d, Vec3 from, Vec3 dir, double range, float width, int rgb) {
		ServerLevel level = p.serverLevel();
		Vec3 end = from.add(dir.scale(range));
		HitResult wall = level.clip(new ClipContext(from, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
		end = wall.getLocation();
		var entityHit = ProjectileUtil.getEntityHitResult(level, p, from, end, new AABB(from, end).inflate(0.6), e -> Handhelds.enemy(p, e), 0.25f);
		LivingEntity target = entityHit != null && entityHit.getEntity() instanceof LivingEntity l ? l : null;
		if (target != null) end = entityHit.getLocation();
		float len = (float) from.distanceTo(end);
		Props.move(d, from);
		Props.set(d, Props.along(Vec3.ZERO, dir, width, len), 1);
		AuraFx.particles(level, AuraFx.dust(rgb, 1.1f), end.x, end.y, end.z, 2, 0.12, 0.12, 0.12, 0);
		return target;
	}

	// ---------------------------------------------------------------
	// Mana wisps
	// ---------------------------------------------------------------

	private static final class Wisp {
		final ServerLevel level;
		final Display.ItemDisplay d;
		Vec3 pos, vel;
		int age;
		final double phase;

		Wisp(ServerLevel level, Display.ItemDisplay d, Vec3 pos, Vec3 vel, double phase) {
			this.level = level;
			this.d = d;
			this.pos = pos;
			this.vel = vel;
			this.phase = phase;
		}
	}

	private static final List<Wisp> WISPS = new ArrayList<>();

	/** Any death: a mob dying near a Mage may leave mana wisps. */
	public static void onDeath(LivingEntity dead) {
		if (dead instanceof Player || dead instanceof ArmorStand || !(dead.level() instanceof ServerLevel level)) return;
		if (com.toast.aurapoints.arena.Arena.in(dead)) return;
		MinecraftServer server = level.getServer();
		double range = Math.max(1, AuraConfig.MAGE_WISP_DROP_RANGE);
		boolean mageNear = false;
		for (ServerPlayer p : level.players()) {
			if (p.distanceToSqr(dead) <= range * range && data(p).aura == AuraType.MAGE && !p.isSpectator()) {
				mageNear = true;
				break;
			}
		}
		if (!mageNear) return;
		int count = dead.getType().is(com.toast.aurapoints.AuraTags.BOSSES) ? Math.max(0, AuraConfig.MAGE_WISP_BOSS_COUNT)
				: level.getRandom().nextDouble() < AuraConfig.MAGE_WISP_CHANCE ? 1 : 0;
		var r = level.getRandom();
		Vec3 c = AuraFx.chest(dead);
		for (int i = 0; i < count; i++) {
			Vec3 vel = new Vec3((r.nextDouble() - 0.5) * 0.12, 0.06 + r.nextDouble() * 0.05, (r.nextDouble() - 0.5) * 0.12);
			Wisp w = new Wisp(level, fx(level, c, FX_WISP, 0.5f), c, vel, r.nextDouble() * Math.PI * 2);
			WISPS.add(w);
		}
		if (count > 0) sound(level, c, AuraSounds.SOUL_ESCAPE.value(), 0.8f, 1.5f);
	}

	/** Returns false once the wisp is gone (taken or faded). */
	private static boolean tickWisp(MinecraftServer server, Wisp w) {
		if (w.d.isRemoved()) return false;
		w.age++;
		int life = Math.max(40, AuraConfig.MAGE_WISP_TICKS);
		if (w.age > life) {
			removeFx(w.d);
			AuraFx.particles(w.level, ParticleTypes.SOUL, w.pos.x, w.pos.y, w.pos.z, 3, 0.1, 0.1, 0.1, 0.02);
			return false;
		}
		// the nearest Mage within reach pulls it in
		double pull = Math.max(1, AuraConfig.MAGE_WISP_PULL_RADIUS);
		ServerPlayer mage = null;
		double best = pull * pull;
		for (ServerPlayer p : w.level.players()) {
			if (!p.isAlive() || p.isSpectator()) continue;
			double d = AuraFx.chest(p).distanceToSqr(w.pos);
			if (d < best && data(p).aura == AuraType.MAGE) {
				best = d;
				mage = p;
			}
		}
		if (mage != null) {
			Vec3 to = AuraFx.chest(mage).subtract(w.pos);
			double d = to.length();
			if (d < 0.7) {
				absorb(mage, w);
				return false;
			}
			w.vel = w.vel.scale(0.82).add(to.scale(1 / d).scale(0.06 + 0.25 / Math.max(1, d)));
		} else {
			// floats where it is, bobbing a little, slowing down
			w.vel = w.vel.scale(0.9).add(0, Math.sin((w.age + w.phase * 20) * 0.12) * 0.004, 0);
		}
		w.pos = w.pos.add(w.vel);
		w.d.setPos(w.pos.x, w.pos.y, w.pos.z);
		// fading out in its last second
		if (w.age == life - 20) scale(w.d, 0.05f, 20);
		if (w.age % 3 == 0) AuraFx.particles(w.level, ParticleTypes.SOUL_FIRE_FLAME, w.pos.x, w.pos.y - 0.1, w.pos.z, 1, 0.04, 0.02, 0.04, 0.005);
		if (w.age % 5 == 0) AuraFx.particles(w.level, AuraFx.dust(CYAN, 0.5f), w.pos.x, w.pos.y, w.pos.z, 1, 0.12, 0.12, 0.12, 0);
		return true;
	}

	private static void absorb(ServerPlayer p, Wisp w) {
		removeFx(w.d);
		addMana(p, AuraConfig.MAGE_WISP_MANA);
		addXp(p, data(p), AuraConfig.MAGE_WISP_XP);
		Vec3 c = AuraFx.chest(p);
		AuraFx.particles(w.level, AuraFx.dust(CYAN, 0.9f), c.x, c.y, c.z, 8, 0.3, 0.4, 0.3, 0);
		AuraFx.particles(w.level, ParticleTypes.SOUL_FIRE_FLAME, c.x, c.y, c.z, 4, 0.2, 0.3, 0.2, 0.02);
		w.level.playSound(null, p.getX(), p.getY(), p.getZ(), AuraSounds.AMETHYST_BLOCK_CHIME.value(), SoundSource.PLAYERS, 1.4f, 1.7f);
		w.level.playSound(null, p.getX(), p.getY(), p.getZ(), AuraSounds.EXPERIENCE_ORB_PICKUP.value(), SoundSource.PLAYERS, 0.4f, 0.7f);
	}

	// ---------------------------------------------------------------
	// Holds and lifecycle
	// ---------------------------------------------------------------

	/** Any Mage hold running (no other spell starts meanwhile). */
	private static boolean busy(ServerPlayer p) {
		UUID id = p.getUUID();
		return BOLTS.containsKey(id) || ORBS.containsKey(id) || PRISMS.containsKey(id);
	}

	/** Frozen, stunned, dead or logged out: whatever the Mage held falls apart (the bolt fizzles, the orb pops, the Prism ends). */
	public static void dropHeld(ServerPlayer p) {
		if (BOLTS.remove(p.getUUID()) != null) NetworkHandler.sendBendAnim(p, BendingAnimPacket.NONE);
		Orb o = ORBS.remove(p.getUUID());
		if (o != null) dropOrb(p, o);
		Prism pr = PRISMS.remove(p.getUUID());
		if (pr != null) endPrism(p, pr);
	}

	public static void tickAll(MinecraftServer server) {
		long now = server.getTickCount();
		tickBarriers(server, now);
		if (!BOLTS.isEmpty()) {
			for (var e : new ArrayList<>(BOLTS.entrySet())) {
				ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
				if (p == null || !tickBolt(p, e.getValue(), now)) BOLTS.remove(e.getKey());
			}
		}
		if (!ORBS.isEmpty()) {
			for (Orb o : new ArrayList<>(ORBS.values())) {
				ServerPlayer p = server.getPlayerList().getPlayer(o.owner);
				boolean alive;
				try {
					alive = p != null && tickOrb(p, o, now);
				} catch (Exception ex) {
					com.toast.aurapoints.AuraPoints.LOGGER.error("Arcane Orb failed", ex);
					alive = false;
				}
				if (!alive) {
					if (p == null) removeFx(o.ball);
					ORBS.remove(o.owner);
				}
			}
		}
		if (!SHOTS.isEmpty()) SHOTS.removeIf(s -> {
			try {
				return !tickShot(server, s);
			} catch (Exception ex) {
				com.toast.aurapoints.AuraPoints.LOGGER.error("Arcane Orb shot failed", ex);
				removeFx(s.ball);
				return true;
			}
		});
		if (!PRISMS.isEmpty()) {
			for (Prism pr : new ArrayList<>(PRISMS.values())) {
				boolean alive;
				try {
					alive = tickPrism(server, pr);
				} catch (Exception ex) {
					com.toast.aurapoints.AuraPoints.LOGGER.error("Last Prism failed", ex);
					endPrism(server.getPlayerList().getPlayer(pr.owner), pr);
					alive = false;
				}
				if (!alive) PRISMS.remove(pr.owner);
			}
		}
		if (!WISPS.isEmpty()) WISPS.removeIf(w -> !tickWisp(server, w));
	}

	public static void onLogout(ServerPlayer p) {
		dropHeld(p);
		BARRIERS.remove(p.getUUID());
		LAST_SPEND.remove(p.getUUID());
		LAST_CAST.remove(p.getUUID());
		SENT.remove(p.getUUID());
	}

	/** Respawned: the held spells are gone (the pool keeps what it had). */
	public static void onRespawn(ServerPlayer p) {
		dropHeld(p);
		BARRIERS.remove(p.getUUID());
		if (p.getServer() != null) sync(p, data(p), true);
	}

	/** Server stop: nothing carries into the next world. */
	public static void clearAll() {
		for (Orb o : ORBS.values()) removeFx(o.ball);
		for (Shot s : SHOTS) removeFx(s.ball);
		for (Prism pr : PRISMS.values()) endPrism(null, pr);
		for (Wisp w : WISPS) removeFx(w.d);
		BOLTS.clear();
		ORBS.clear();
		SHOTS.clear();
		PRISMS.clear();
		WISPS.clear();
		BARRIERS.clear();
		LAST_SPEND.clear();
		LAST_CAST.clear();
		SENT.clear();
		LIVE.clear();
	}

	// ---------------------------------------------------------------
	// Dev test hooks
	// ---------------------------------------------------------------

	public static int wispCount() {
		return WISPS.size();
	}

	public static boolean holdingOrb(ServerPlayer p) {
		return ORBS.containsKey(p.getUUID());
	}

	public static boolean holdingBolt(ServerPlayer p) {
		return BOLTS.containsKey(p.getUUID());
	}

	/** Dev: the mana pool straight to {@code mana} (and the HUD told). */
	public static void devSetMana(ServerPlayer p, double mana) {
		setMana(p, data(p), mana);
	}

	/** Dev: mana XP straight in (level-ups included). */
	public static void devAddXp(ServerPlayer p, double xp) {
		addXp(p, data(p), xp);
	}

	/** Dev: forget when this player last spent (regen starts at once). */
	public static void devForgetSpend(ServerPlayer p) {
		LAST_SPEND.remove(p.getUUID());
	}

	/** Dev: forget the cast delay so a test can cast twice in one tick. */
	public static void devReady(ServerPlayer p) {
		LAST_CAST.remove(p.getUUID());
	}

	/** Dev: tick one player's holds (fake players don't tick, but the server loop finds them through the player list only). */
	public static void devTick(ServerPlayer p, long now) {
		Bolt b = BOLTS.get(p.getUUID());
		if (b != null && !tickBolt(p, b, now)) BOLTS.remove(p.getUUID());
		Orb o = ORBS.get(p.getUUID());
		if (o != null && !tickOrb(p, o, now)) ORBS.remove(p.getUUID());
	}
}
