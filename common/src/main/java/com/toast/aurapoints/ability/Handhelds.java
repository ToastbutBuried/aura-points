package com.toast.aurapoints.ability;

import com.toast.aurapoints.AuraPoints;
import com.toast.aurapoints.ability.AuraAbilities.Ability;
import com.toast.aurapoints.config.AuraConfig;
import com.toast.aurapoints.data.AuraPlayerData;
import com.toast.aurapoints.data.AuraState;
import com.toast.aurapoints.item.HandheldItem;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.UUID;

/**
 * The handheld weapons: one per aura, plain hits, all their power in one ability each. Right-click with
 * the weapon (the World Tree Bow: sneak + right-click), or press the Item Use key while holding it. Only a
 * player of the weapon's own aura can use it (HANDHELD_AURA_ONLY); cooldowns, the HUD slot, the aura_cd_*
 * scoreboards and the #aurapoints:ability/* function tags work like any other ability's.
 */
public final class Handhelds {

	private Handhelds() {
	}

	/** The aura a handheld ability belongs to, or null if it isn't one. */
	public static AuraType auraOf(Ability ability) {
		return switch (ability) {
			case MAELSTROM -> AuraType.WATER;
			case SUNFALL -> AuraType.FIRE;
			case EYE_OF_THE_STORM -> AuraType.WIND;
			case UMBRAL_AMBUSH -> AuraType.SHADOW;
			case THORN_SENTINEL -> AuraType.VERDANT;
			case WINTERS_GATE -> AuraType.FROST;
			case CHAIN_STRIKE -> AuraType.THUNDER;
			default -> null;
		};
	}

	/** The ability of the handheld in the main hand, or null. */
	public static Ability held(Player player) {
		Item item = player.getMainHandItem().getItem();
		if (item instanceof HandheldItem h) return h.ability;
		return item instanceof com.toast.aurapoints.item.WorldTreeBowItem ? Ability.THORN_SENTINEL : null;
	}

	/** Not locked, not switched off, and (while HANDHELD_AURA_ONLY) the player's aura matches. */
	public static boolean canUse(AuraPlayerData data, Ability ability) {
		if (data.locked || AuraAbilities.allLocked || AuraAbilities.DISABLED.contains(ability)) return false;
		return !AuraConfig.HANDHELD_AURA_ONLY || data.aura == auraOf(ability);
	}

	/** Right-click or the Item Use key with a handheld. True if the ability went off. */
	public static boolean use(ServerPlayer player, Ability ability) {
		MinecraftServer server = player.getServer();
		if (server == null) return false;
		// only the owner and the worthy wield them
		if (!com.toast.aurapoints.AuraCommand.isWorthy(player)) {
			say(player, "Only the worthy can wield this.", ChatFormatting.GOLD);
			return false;
		}
		AuraPlayerData data = AuraState.get(server).getOrCreate(player.getUUID());
		if (!canUse(data, ability)) {
			if (data.locked || AuraAbilities.allLocked) say(player, "Your aura is switched off.", ChatFormatting.RED);
			else if (AuraAbilities.DISABLED.contains(ability)) say(player, ability.displayName + " is switched off on this server.", ChatFormatting.RED);
			else say(player, "Only a " + auraOf(ability).displayName + " aura can awaken " + ability.displayName + ".", ChatFormatting.GRAY);
			return false;
		}
		// the usual locks: frozen, stunned, winding up, entangled, steering a viper, nulled
		if (IceSpike.isFrozen(player) || Stun.isBusy(player) || Entanglement.isEntangled(player) || VerdantViper.isSteering(player)
				|| VoidAura.isNulled(player)) return false;
		long now = server.getTickCount();
		if (!AuraAbilities.checkCooldown(player, ability, ability.cooldownTicks(), now)) return false;
		boolean fired;
		try {
			fired = switch (ability) {
				case MAELSTROM -> Maelstrom.start(player);
				case SUNFALL -> Sunfall.start(player);
				case EYE_OF_THE_STORM -> StormEye.start(player);
				case UMBRAL_AMBUSH -> UmbralAmbush.start(player);
				case THORN_SENTINEL -> ThornSentinel.start(player);
				case WINTERS_GATE -> WintersGate.start(player);
				case CHAIN_STRIKE -> ChainStrike.start(player);
				default -> false;
			};
		} catch (Exception e) {
			AuraPoints.LOGGER.error("Handheld ability {} failed", ability, e);
			fired = false;
		}
		if (!fired) return false;
		AuraAbilities.markUsed(player, ability, now);
		// the weapon's own hotbar cooldown sweep (not the bow's: it still shoots arrows meanwhile)
		ItemStack stack = player.getMainHandItem();
		int left = AuraAbilities.cooldownTicksLeft(player, ability, now);
		if (left > 0 && stack.getItem() instanceof HandheldItem) player.getCooldowns().addCooldown(stack.getItem(), left);
		return true;
	}

	/** The Item Use key went down. */
	public static void onItemKey(ServerPlayer player) {
		Ability ability = held(player);
		if (ability != null) use(player, ability);
	}

	private static void say(ServerPlayer player, String text, ChatFormatting color) {
		player.displayClientMessage(Component.literal(text).withStyle(color), true);
	}

	// ---------------------------------------------------------------
	// Shared by the abilities
	// ---------------------------------------------------------------

	/** A mob or player the owner's ability may hit (not themselves, their mount or pets, creative players or armor stands). */
	static boolean enemy(ServerPlayer owner, Entity e) {
		if (!(e instanceof LivingEntity) || e == owner || !e.isAlive() || e.isSpectator() || e instanceof ArmorStand) return false;
		if (e instanceof Player p && p.getAbilities().invulnerable) return false;
		if (e instanceof LivingEntity l && VerdantViper.isSteering(l)) return false;
		if (AuraConfig.COMBO_PARTY_PROTECTION && e instanceof ServerPlayer p && Allies.party(owner, p)) return false;
		// the same side of an arena match (Team Battle, Wave Survival): never a target
		if (e instanceof ServerPlayer mate && com.toast.aurapoints.arena.Match.teammates(owner, mate)) return false;
		return e.getRootVehicle() != owner.getRootVehicle()
				&& !(e instanceof OwnableEntity pet && owner.getUUID().equals(pet.getOwnerUUID()));
	}

	/** Enemies whose middle is within {@code r} of {@code c}. */
	static List<LivingEntity> enemies(ServerPlayer owner, ServerLevel level, Vec3 c, double r) {
		return level.getEntitiesOfClass(LivingEntity.class, new AABB(c, c).inflate(r),
				e -> enemy(owner, e) && AuraFx.chest(e).distanceToSqr(c) <= r * r);
	}

	/** Ability damage (through vsMob, so it counts as an ability hit). */
	static void hurt(ServerPlayer owner, LivingEntity target, float damage) {
		if (damage <= 0 || !target.isAlive()) return;
		target.hurt(owner.serverLevel().damageSources().playerAttack(owner), AuraAbilities.vsMob(target, damage));
	}

	/** Where the crosshair hits a block or entity, up to {@code range} (the far end if nothing). */
	static Vec3 aim(ServerPlayer player, double range) {
		Vec3 eye = player.getEyePosition();
		Vec3 end = eye.add(player.getLookAngle().scale(range));
		var hit = player.level().clip(new net.minecraft.world.level.ClipContext(eye, end,
				net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, player));
		Vec3 to = hit.getLocation();
		var entityHit = net.minecraft.world.entity.projectile.ProjectileUtil.getEntityHitResult(player.level(), player, eye, to,
				new AABB(eye, to).inflate(1.0), e -> enemy(player, e), 0.3f);
		return entityHit != null ? entityHit.getLocation() : to;
	}

	/** DEV ONLY: the fake player /auradev handheld casts with (it isn't in the player list). */
	static ServerPlayer devPlayer;

	/** The online player with that id (or the dev fake player), null if gone. */
	static ServerPlayer player(MinecraftServer server, UUID id) {
		ServerPlayer p = server.getPlayerList().getPlayer(id);
		return p != null ? p : devPlayer != null && devPlayer.getUUID().equals(id) ? devPlayer : null;
	}

	/** DEV ONLY: fires a handheld ability for {@code player} without the aura, cooldown or lock checks. */
	public static boolean devFire(ServerPlayer player, Ability ability) {
		devPlayer = player;
		return switch (ability) {
			case MAELSTROM -> Maelstrom.start(player);
			case SUNFALL -> Sunfall.start(player);
			case EYE_OF_THE_STORM -> StormEye.start(player);
			case UMBRAL_AMBUSH -> UmbralAmbush.start(player);
			case THORN_SENTINEL -> ThornSentinel.start(player);
			case WINTERS_GATE -> WintersGate.start(player);
			case CHAIN_STRIKE -> ChainStrike.start(player);
			default -> false;
		};
	}

	public static void tickAll(MinecraftServer server) {
		Maelstrom.tickAll(server);
		Sunfall.tickAll(server);
		StormEye.tickAll(server);
		UmbralAmbush.tickAll(server);
		ThornSentinel.tickAll(server);
		WintersGate.tickAll(server);
		ChainStrike.tickAll(server);
	}

	public static void clearAll() {
		Maelstrom.clearAll();
		Sunfall.clearAll();
		StormEye.clearAll();
		UmbralAmbush.clearAll();
		ThornSentinel.clearAll();
		WintersGate.clearAll();
		ChainStrike.clearAll();
	}

	public static void onLogin(ServerPlayer player) {
		ChainStrike.onLogin(player);
	}

	public static void onLogout(ServerPlayer player) {
		UmbralAmbush.onLogout(player);
		ChainStrike.onLogout(player);
	}
}
