package com.toast.aurapoints.shim.neoforge.event;

import com.toast.aurapoints.shim.Platform;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.level.ServerLevelAccessor;

public final class EventHooks {
	private EventHooks() {
	}

	/** mob.finalizeSpawn the way the loader does it (NeoForge: through FinalizeSpawnEvent, so other mods hear of it). */
	public static SpawnGroupData finalizeMobSpawn(Mob mob, ServerLevelAccessor level, DifficultyInstance difficulty, MobSpawnType type,
			SpawnGroupData data) {
		return Platform.get().finalizeMobSpawn(mob, level, difficulty, type, data);
	}
}
