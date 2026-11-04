package com.toast.aurapoints.network;

import com.toast.aurapoints.ability.AuraAbilities;
import com.toast.aurapoints.ability.AuraType;

import java.util.EnumMap;
import java.util.Map;

/**
 * What the client knows for the cooldown HUD: its aura, and when each ability
 * comes off cooldown (wall-clock millis, so it keeps counting while paused or
 * lagging). Plain data with no client-only imports, so the packet handlers in
 * the common NetworkHandler can write it safely.
 */
public final class ClientAuraState {

	private ClientAuraState() {
	}

	public static volatile AuraType aura = null;

	/** /aura selfhide: hide yourself in third person while Shadow-cloaked. */
	public static volatile boolean selfHide = false;

	/** Fire Bending charge bar: shown while an orb is held; charge and readyAt are 0..1 of full charge. */
	public static volatile boolean fireBending = false;
	public static volatile float fireCharge = 0;
	public static volatile float fireReadyAt = 0.5f;

	public static void setFireBending(boolean active, float charge, float readyAt) {
		fireCharge = charge;
		fireReadyAt = readyAt;
		fireBending = active;
		if (!active) setHighlighted(java.util.List.of());
	}

	/** Blood aura's blood bar (Blood Moon needs bloodMoonCost of it). */
	public static volatile int bloodBar = 0, bloodMax = 100, bloodMoonCost = 100;

	public static void setBloodBar(int blood, int max, int moonCost) {
		bloodMax = Math.max(1, max);
		bloodMoonCost = Math.max(0, moonCost);
		bloodBar = blood;
	}

	/** 0..1 of what Blood Moon needs (1 = it can be cast once its cooldown is over). */
	public static float bloodMoonFill() {
		return bloodMoonCost <= 0 ? 1f : Math.min(1f, bloodBar / (float) bloodMoonCost);
	}

	/** Abyssal Bubble held by this player: the shake settings and where they looked when it opened. */
	public static volatile BubblePacket bubble = null;
	public static volatile long bubbleSince = 0;

	public static void setBubble(BubblePacket packet) {
		bubbleSince = System.currentTimeMillis();
		bubble = packet.active() ? packet : null;
	}

	/** Someone else's Abyssal Bubble pulling this player (client/BubblePull), and when it last said so (millis). */
	public static volatile BubblePullPacket bubblePull = null;
	public static volatile long bubblePullAt = 0;

	public static void setBubblePull(BubblePullPacket packet) {
		bubblePullAt = System.currentTimeMillis();
		bubblePull = packet;
	}

	/** Every player's aura by entity id, for the label over their name (absent = none). */
	private static final java.util.Map<Integer, AuraType> PLAYER_AURAS = new java.util.HashMap<>();

	/** Their mastery of that aura (absent = none). */
	private static final java.util.Map<Integer, Integer> PLAYER_MASTERY = new java.util.HashMap<>();

	/** "fire", or "fire*3" with Mastery III. */
	public static synchronized void setPlayerAura(int entityId, String aura) {
		int star = aura.indexOf('*');
		int mastery = 0;
		if (star >= 0) {
			try {
				mastery = Integer.parseInt(aura.substring(star + 1));
			} catch (NumberFormatException ignored) {
			}
			aura = aura.substring(0, star);
		}
		AuraType type = aura.isEmpty() ? null : AuraType.byId(aura);
		if (type == null) PLAYER_AURAS.remove(entityId);
		else PLAYER_AURAS.put(entityId, type);
		if (type == null || mastery <= 0) PLAYER_MASTERY.remove(entityId);
		else PLAYER_MASTERY.put(entityId, mastery);
	}

	public static synchronized AuraType playerAura(int entityId) {
		return PLAYER_AURAS.get(entityId);
	}

	public static synchronized int playerMastery(int entityId) {
		return PLAYER_MASTERY.getOrDefault(entityId, 0);
	}

	public static synchronized void clearPlayerAuras() {
		PLAYER_AURAS.clear();
		PLAYER_MASTERY.clear();
	}

	/** Being carried by a Vine Hook until this time (millis), see client/CarriedSmoothing. */
	public static volatile long carriedUntil = 0;

	public static void setCarried(int ticks) {
		carriedUntil = ticks <= 0 ? 0 : System.currentTimeMillis() + ticks * 50L + 100;
	}

	public static boolean isCarried() {
		return carriedUntil != 0 && System.currentTimeMillis() < carriedUntil;
	}

	/** Rooted in place (entangled, or steering a Verdant Viper), and whether to mash W A S D free, see client/RootedControls. */
	public static volatile boolean rooted = false, mash = false;

	/** This player's Ice Shell (null until the server sends it). */
	public static volatile IceShellPacket iceShell = null;

	/** Frozen solid by an Ice Spike until this time (millis); the hotbar slot it froze on (-1 = not yet noted). */
	public static volatile long frozenUntil = 0;
	public static volatile int frozenSlot = -1;

	public static void setFrozen(int ticks) {
		frozenUntil = ticks <= 0 ? 0 : System.currentTimeMillis() + ticks * 50L + 250;
	}

	public static boolean isFrozen() {
		return frozenUntil != 0 && System.currentTimeMillis() < frozenUntil;
	}

	/** Fire Bending pose per player entity id (BendingAnimPacket states), and when it last changed (millis). */
	private static final java.util.Map<Integer, long[]> BEND_STATES = new java.util.HashMap<>();

	public static synchronized void setBendState(int entityId, byte state) {
		setBendState(entityId, state, 0, 0);
	}

	/** The held pose to go back to when the gesture playing now ends (none = absent). */
	private static final java.util.Map<Integer, long[]> BEND_BASE = new java.util.HashMap<>();

	/**
	 * A new pose from the server. Rules that keep poses from sticking or cutting each other off:
	 *  - a gesture always plays to its end: a held pose (or none) arriving meanwhile becomes what
	 *    follows it, instead of cutting it short;
	 *  - a gesture started over a held pose goes back to that pose afterwards;
	 *  - THROW and SLAM replace anything at once.
	 */
	public static synchronized void setBendState(int entityId, byte state, int windup, int hold) {
		long[] now = BEND_STATES.get(entityId);
		byte current = now == null ? BendingAnimPacket.NONE : (byte) now[0];
		if (current == BendingAnimPacket.GESTURE && (state == BendingAnimPacket.NONE || BendingAnimPacket.isHeld(state))) {
			if (state == BendingAnimPacket.NONE) BEND_BASE.remove(entityId);
			else BEND_BASE.put(entityId, new long[]{state, 0, windup, hold});
			return;
		}
		if (state == BendingAnimPacket.GESTURE) {
			if (BendingAnimPacket.isHeld(current)) BEND_BASE.put(entityId, now);
			else if (current != BendingAnimPacket.GESTURE) BEND_BASE.remove(entityId);
		} else {
			BEND_BASE.remove(entityId);
		}
		if (state == BendingAnimPacket.NONE) BEND_STATES.remove(entityId);
		else BEND_STATES.put(entityId, new long[]{state, System.currentTimeMillis(), windup, hold});
	}

	/** A gesture finished: back to the held pose it interrupted, or to none. */
	public static synchronized void endGesture(int entityId) {
		long[] base = BEND_BASE.remove(entityId);
		if (base == null) BEND_STATES.remove(entityId);
		else BEND_STATES.put(entityId, new long[]{base[0], System.currentTimeMillis(), base[2], base[3]});
	}

	/** {state, since millis, windup ticks, hold ticks}, or null for no pose. */
	public static synchronized long[] bendState(int entityId) {
		return BEND_STATES.get(entityId);
	}

	public static synchronized void clearBendStates() {
		BEND_STATES.clear();
		BEND_BASE.clear();
	}

	/** Fire sight: entity ids drawn glowing (only on this client) while a charged orb is held. */
	private static volatile java.util.Set<Integer> highlighted = java.util.Set.of();

	/** Abyssal Grasp, per tentacled player (entity id): the phase their tentacles are in and since when (client millis). */
	public record Grasp(int phase, int target, double x, double y, double z, long since, int ticks) {
	}

	private static final java.util.Map<Integer, Grasp> GRASPS = new java.util.concurrent.ConcurrentHashMap<>();

	public static void setGrasp(GraspPacket p) {
		if (p.phase() == GraspPacket.NONE) GRASPS.remove(p.owner());
		else GRASPS.put(p.owner(), new Grasp(p.phase(), p.target(), p.x(), p.y(), p.z(), System.currentTimeMillis(), p.ticks()));
	}

	public static Grasp grasp(int owner) {
		return GRASPS.get(owner);
	}

	/** Echolocation's violet fog (blocks); drawn while the aura is Resonance and Echolocation isn't off. */
	public static volatile float echoFogStart = 8f;
	public static volatile float echoFogEnd = 20f;
	/** Abyssal Grasp: the FOV multiplier at full charge (the charge itself rides on the fire-bending bar). */
	public static volatile float graspZoom = 0.85f;
	/** Fireborn: how far you see in lava (blocks, 0 = vanilla); drawn while the aura is Fire and Fireborn isn't off. */
	public static volatile float lavaSight = 16f;

	public static boolean lavaSight() {
		return aura == AuraType.FIRE && !isDisabled(AuraAbilities.Ability.FIREBORN) && lavaSight > 0;
	}

	public static boolean echoFog() {
		return aura == AuraType.RESONANCE && !isDisabled(AuraAbilities.Ability.ECHOLOCATION) && echoFogEnd > 0;
	}

	public static void setHighlighted(java.util.List<Integer> ids) {
		highlighted = java.util.Set.copyOf(ids);
	}

	public static boolean isHighlighted(int entityId) {
		return highlighted.contains(entityId);
	}

	/** ability -> {start millis, end millis} */
	public static final Map<AuraAbilities.Ability, long[]> COOLDOWNS = new EnumMap<>(AuraAbilities.Ability.class);
	/** The extra cooldowns (flight set shown while gliding, Wind launch), same layout. */
	public static final Map<AuraAbilities.Slot, Map<AuraAbilities.Ability, long[]>> EXTRA_COOLDOWNS = new EnumMap<>(AuraAbilities.Slot.class);

	static {
		for (AuraAbilities.Slot slot : AuraAbilities.Slot.values()) EXTRA_COOLDOWNS.put(slot, new EnumMap<>(AuraAbilities.Ability.class));
	}

	private static Map<AuraAbilities.Ability, long[]> map(AuraAbilities.Slot slot) {
		return slot == null ? COOLDOWNS : EXTRA_COOLDOWNS.get(slot);
	}

	public static synchronized void setAura(String id) {
		aura = id.isEmpty() ? null : AuraType.byId(id);
		COOLDOWNS.clear();
		EXTRA_COOLDOWNS.values().forEach(Map::clear);
	}

	public static synchronized void startCooldown(String ability, int ticks) {
		try {
			long now = System.currentTimeMillis();
			AuraAbilities.Slot slot = null;
			int sep = ability.indexOf(CooldownPacket.SLOT_SEPARATOR);
			if (sep >= 0) {
				slot = AuraAbilities.Slot.valueOf(ability.substring(sep + 1));
				ability = ability.substring(0, sep);
			}
			map(slot).put(AuraAbilities.Ability.valueOf(ability), new long[]{now, now + ticks * 50L});
		} catch (IllegalArgumentException ignored) {
			// ability this client doesn't know - nothing to draw
		}
	}

	/** Entity ids of Shadow-cloaked players (drawn not at all while they are invisible). */
	private static final java.util.Set<Integer> CLOAKED = new java.util.HashSet<>();

	public static synchronized void setCloaked(int entityId, boolean cloaked) {
		if (cloaked) CLOAKED.add(entityId);
		else CLOAKED.remove(entityId);
	}

	public static synchronized boolean isCloaked(int entityId) {
		return CLOAKED.contains(entityId);
	}

	public static synchronized void clearCloaked() {
		CLOAKED.clear();
	}

	/** Abilities the server switched off; the HUD leaves them out. */
	/** This player is a server owner (sent with the disabled abilities). */
	public static volatile boolean owner;

	private static final java.util.Set<AuraAbilities.Ability> DISABLED = java.util.EnumSet.noneOf(AuraAbilities.Ability.class);

	public static synchronized void setDisabled(String names) {
		DISABLED.clear();
		for (String n : names.split(",")) {
			try {
				if (!n.isEmpty()) DISABLED.add(AuraAbilities.Ability.valueOf(n));
			} catch (IllegalArgumentException ignored) {
				// unknown to this client
			}
		}
	}

	/** Active abilities bought in /aura shop; the HUD only shows these. */
	private static final java.util.Set<AuraAbilities.Ability> OWNED = java.util.EnumSet.noneOf(AuraAbilities.Ability.class);

	public static synchronized void setOwned(String names) {
		OWNED.clear();
		for (String n : names.split(",")) {
			try {
				if (!n.isEmpty()) OWNED.add(AuraAbilities.Ability.valueOf(n));
			} catch (IllegalArgumentException ignored) {
				// unknown to this client
			}
		}
	}

	public static synchronized boolean isOwned(AuraAbilities.Ability ability) {
		return OWNED.contains(ability);
	}

	public static synchronized boolean isDisabled(AuraAbilities.Ability ability) {
		return DISABLED.contains(ability);
	}

	/** slot null = the main cooldown. */
	public static synchronized void clearCooldown(AuraAbilities.Ability ability, AuraAbilities.Slot slot) {
		map(slot).remove(ability);
	}

	/** {start, end} millis, or null if the ability isn't cooling down (slot null = the main cooldown). */
	public static synchronized long[] cooldown(AuraAbilities.Ability ability, AuraAbilities.Slot slot) {
		return map(slot).get(ability);
	}

	/** Screen effects from the server (FxPacket), picked up by the client each tick. */
	private static final java.util.concurrent.ConcurrentLinkedQueue<FxPacket> FX = new java.util.concurrent.ConcurrentLinkedQueue<>();

	/** Shows an arena announcement; client/ArenaTitles puts the real one in (this class stays free of client imports). */
	public static java.util.function.Consumer<ArenaTitlePacket> arenaTitle = p -> {
	};

	public static void pushFx(FxPacket packet) {
		// a client that never polls (no screen yet) mustn't pile them up
		if (FX.size() < 64) FX.add(packet);
	}

	/** The next screen effect, or null. */
	public static FxPacket pollFx() {
		return FX.poll();
	}

	/** Leaving a world or server: forget everything about the last one (entity ids get reused on the next). */
	public static synchronized void resetAll() {
		setAura("");
		clearCloaked();
		setFireBending(false, 0, 0.5f);
		clearBendStates();
		bloodBar = 0;
		bubble = null;
		bubblePull = null;
		bubblePullAt = 0;
		carriedUntil = 0;
		frozenUntil = 0;
		frozenSlot = -1;
		iceShell = null;
		rooted = false;
		mash = false;
		selfHide = false;
		clearPlayerAuras();
		GRASPS.clear();
		OWNED.clear();
		DISABLED.clear();
		FX.clear();
	}

	/** Abilities that turn into something else while gliding with an elytra, on their own cooldown. */
	public static boolean hasFlightVersion(AuraAbilities.Ability ability) {
		return ability == AuraAbilities.Ability.DASH || ability == AuraAbilities.Ability.DOUBLE_JUMP;
	}
}
