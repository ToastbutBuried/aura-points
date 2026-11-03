package com.toast.aurapoints.fabric;

import com.toast.aurapoints.shim.AuraBus;
import com.toast.aurapoints.shim.neoforge.event.entity.EntityMobGriefingEvent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.projectile.Projectile;

/** Small helpers the Fabric mixins share. */
public final class FabricHooks {
	private FabricHooks() {
	}

	/** The mob whose AI (goals, brain) is running right now on this thread (set in MobMixin). */
	private static final ThreadLocal<Mob> AI_MOB = new ThreadLocal<>();

	public static Mob enterAi(Mob mob) {
		Mob before = AI_MOB.get();
		AI_MOB.set(mob);
		return before;
	}

	public static void leaveAi(Mob before) {
		AI_MOB.set(before);
	}

	/**
	 * NeoForge's EventHooks.canEntityGrief: the mobGriefing rule, unless a listener decides for this entity. Who asks:
	 * a projectile's owner, any other entity itself, a goal or brain behaviour its mob (set while its AI runs).
	 */
	public static boolean canGrief(Object self, boolean vanilla) {
		Entity entity = self instanceof Projectile p ? p.getOwner() : self instanceof Entity e ? e : AI_MOB.get();
		return canGriefEntity(entity, vanilla);
	}

	public static boolean canGriefEntity(Entity entity, boolean vanilla) {
		EntityMobGriefingEvent event = AuraBus.BUS.post(new EntityMobGriefingEvent(entity));
		Boolean decided = event.canGriefOrNull();
		return decided != null ? decided : vanilla;
	}
}
