package com.toast.aurapoints.ability;

import com.toast.aurapoints.network.RootedPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.RelativeMovement;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.Vec3;
import com.toast.aurapoints.shim.neoforge.network.PacketDistributor;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Held in place - entangled, or steering a Verdant Viper: no walking, jumping or being pushed off the
 * spot (it is put back every tick), but the camera stays free. Players' clients are told so their
 * movement keys rest (client/RootedControls). Attribute modifiers are transient, so nothing is saved.
 */
public final class Rooted {

	private Rooted() {
	}

	private static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("aurapoints", "rooted");
	/** rooted entity -> the spot it is held on */
	private static final Map<LivingEntity, Vec3> HELD = new HashMap<>();

	public static boolean isRooted(LivingEntity e) {
		return HELD.containsKey(e);
	}

	public static void lock(LivingEntity e, Vec3 at, boolean mash) {
		if (e.isPassenger()) e.stopRiding(); // sitting: torn off the seat, or everyone else would still see them on it
		HELD.put(e, at);
		var speed = e.getAttribute(Attributes.MOVEMENT_SPEED);
		var jump = e.getAttribute(Attributes.JUMP_STRENGTH);
		if (speed != null && speed.getModifier(ID) == null) speed.addTransientModifier(new AttributeModifier(ID, -1.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
		if (jump != null && jump.getModifier(ID) == null) jump.addTransientModifier(new AttributeModifier(ID, -1.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
		if (e instanceof ServerPlayer p) PacketDistributor.sendToPlayer(p, new RootedPacket(true, mash));
	}

	public static void unlock(LivingEntity e) {
		if (HELD.remove(e) == null) return;
		var speed = e.getAttribute(Attributes.MOVEMENT_SPEED);
		var jump = e.getAttribute(Attributes.JUMP_STRENGTH);
		if (speed != null) speed.removeModifier(ID);
		if (jump != null) jump.removeModifier(ID);
		if (e instanceof ServerPlayer p) PacketDistributor.sendToPlayer(p, new RootedPacket(false, false));
	}

	/** Every server tick: anyone pushed off their spot is put back (a player keeps where they look). */
	public static void tick(MinecraftServer server) {
		if (HELD.isEmpty()) return;
		for (Iterator<Map.Entry<LivingEntity, Vec3>> it = HELD.entrySet().iterator(); it.hasNext(); ) {
			var entry = it.next();
			LivingEntity e = entry.getKey();
			if (!e.isAlive() || e.isRemoved()) {
				it.remove();
				// died or respawned while held: the (new) player's client must hear it, or their keys stay locked
				if (e instanceof ServerPlayer old) {
					ServerPlayer now = server.getPlayerList().getPlayer(old.getUUID());
					if (now != null) PacketDistributor.sendToPlayer(now, new RootedPacket(false, false));
				}
				continue;
			}
			Vec3 at = entry.getValue();
			e.setDeltaMovement(0, Math.min(0, e.getDeltaMovement().y), 0);
			e.fallDistance = 0;
			if (e instanceof Mob mob) mob.getNavigation().stop();
			if (e.position().distanceToSqr(at) > 0.09) {
				if (e instanceof ServerPlayer p) {
					p.connection.teleport(at.x, at.y, at.z, p.getYRot(), p.getXRot(), EnumSet.of(RelativeMovement.X_ROT, RelativeMovement.Y_ROT));
				} else {
					e.setPos(at.x, at.y, at.z);
				}
			}
		}
	}

	public static void clearAll() {
		for (LivingEntity e : new java.util.ArrayList<>(HELD.keySet())) unlock(e);
	}
}
