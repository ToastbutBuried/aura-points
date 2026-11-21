package com.toast.aurapoints;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.toast.aurapoints.ability.AuraType;
import com.toast.aurapoints.ability.ShadowCloak;
import com.toast.aurapoints.data.AuraPlayerData;
import com.toast.aurapoints.data.AuraState;
import com.toast.aurapoints.shim.neoforge.common.util.FakePlayerFactory;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;

import java.util.UUID;

/**
 * DEV ONLY (registered by DevTest): /auradev cloaktest - a cloaked (invisible) Shadow player is never a mob's target (5.42.1):
 * a zombie can't take them even right after being hit, a piglin's brain forgets them, an angry iron golem calms down; once
 * the invisibility ends, the zombie can target them again.
 */
public final class CloakDevTest {

	private CloakDevTest() {
	}

	private static void log(String s) {
		AuraPoints.LOGGER.info("[cloaktest] {}", s);
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("auradev").then(Commands.literal("cloaktest").executes(ctx -> {
			MinecraftServer server = ctx.getSource().getServer();
			ServerLevel level = server.overworld();
			ServerPlayer p = FakePlayerFactory.get(level, new GameProfile(UUID.fromString("5d1e3c1a-0000-4000-8000-00000000c710"), "CloakShadow"));
			AuraPlayerData data = AuraState.get(server).getOrCreate(p.getUUID());
			AuraType before = data.aura;
			data.aura = AuraType.SHADOW;
			// the mobs go next to wherever the fake player really stands
			var spawn = level.getSharedSpawnPos(); // always loaded, so the mobs really join the world
			p.moveTo(spawn.getX() + 0.5, spawn.getY() + 40, spawn.getZ() + 0.5, 0, 0);
			int x = p.getBlockX(), y = p.getBlockY(), z = p.getBlockZ();
			level.setChunkForced(x >> 4, z >> 4, true);
			Mob zombie = EntityType.ZOMBIE.create(level), piglin = EntityType.PIGLIN.create(level);
			var golem = EntityType.IRON_GOLEM.create(level);
			try {
				for (Mob m : new Mob[]{zombie, piglin, golem}) {
					m.moveTo(x + 2.5, y, z + 0.5, 0, 0);
					m.setNoAi(true);
					level.addFreshEntity(m);
				}
				long now = server.getTickCount();
				log("setup: player at " + p.blockPosition().toShortString() + " in overworld " + (p.serverLevel() == level) + ", zombie in level " + (level.getEntity(zombie.getId()) == zombie)
						+ " at " + zombie.blockPosition().toShortString() + ", found near player " + level.getEntitiesOfClass(Mob.class, p.getBoundingBox().inflate(48)).size());
				zombie.setTarget(p);
				log("visible: zombie target " + (zombie.getTarget() == p) + " (must be true)");
				piglin.getBrain().setMemory(MemoryModuleType.ATTACK_TARGET, p);
				golem.setPersistentAngerTarget(p.getUUID());
				golem.setRemainingPersistentAngerTime(400);

				p.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, 200, 0, false, false, false));
				ShadowCloak.tick(p, data, now);
				log("cloaked " + ShadowCloak.isCloaked(p) + " (must be true): zombie lost them " + (zombie.getTarget() == null) + " (must be true), piglin forgot "
						+ !piglin.getBrain().hasMemoryValue(MemoryModuleType.ATTACK_TARGET) + " (must be true), golem calm " + (golem.getPersistentAngerTarget() == null) + " (must be true)");
				zombie.hurt(level.damageSources().playerAttack(p), 1f);
				zombie.setTarget(null);
				zombie.setTarget(p);
				zombie.setLastHurtByMob(p);
				log("hit while cloaked: zombie target " + (zombie.getTarget() == p) + " (must be false)");

				p.removeEffect(MobEffects.INVISIBILITY);
				ShadowCloak.tick(p, data, now + 1);
				zombie.setTarget(p);
				log("cloak over " + !ShadowCloak.isCloaked(p) + " (must be true): zombie target " + (zombie.getTarget() == p) + " (must be true)");
			} finally {
				for (Mob m : new Mob[]{zombie, piglin, golem}) if (m != null) m.discard();
				p.removeEffect(MobEffects.INVISIBILITY);
				ShadowCloak.onLogout(p);
				data.aura = before;
				level.setChunkForced(x >> 4, z >> 4, false);
			}
			log("cloaktest done");
			return 1;
		})));
	}
}
