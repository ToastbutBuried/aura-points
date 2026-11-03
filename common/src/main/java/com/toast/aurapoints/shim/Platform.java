package com.toast.aurapoints.shim;

import com.mojang.authlib.GameProfile;
import com.toast.aurapoints.shim.neoforge.common.ModConfigSpec;
import com.toast.aurapoints.shim.neoforge.items.ItemStackHandler;
import com.toast.aurapoints.shim.neoforge.registries.DeferredRegister;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ServerLevelAccessor;

import java.nio.file.Path;

/**
 * What common/ needs from the mod loader. neoforge/ and fabric/ each ship one implementation
 * (the loader's entry point hands it over with set()).
 */
public interface Platform {
	static Platform get() {
		Platform p = Holder.instance;
		if (p == null) throw new IllegalStateException("Aura Points: no Platform set (the loader entry point sets it first)");
		return p;
	}

	/** Called once, first thing, by the loader entry point. */
	static void set(Platform platform) {
		Holder.instance = platform;
	}

	final class Holder {
		private Holder() {
		}

		private static volatile Platform instance;
	}

	/** "neoforge" or "fabric". */
	String loader();

	/** True on the physical client (singleplayer and LAN hosts included). */
	boolean isClient();

	boolean isModLoaded(String modId);

	Path gameDir();

	Path configDir();

	/** The running server, null when there is none. */
	MinecraftServer currentServer();

	// --- registries, config ---

	<T> void register(DeferredRegister<T> register);

	/** The client settings file (only on the client). */
	void registerClientConfig(ModConfigSpec spec, String fileName);

	// --- players and entities ---

	ServerPlayer fakePlayer(ServerLevel level, GameProfile profile);

	boolean isFakePlayer(Entity entity);

	/** The accessory slots, saved with the player and kept through death. */
	ItemStackHandler accessories(Player player);

	/** A per-entity tag the mod may write into; saved with the entity. */
	CompoundTag persistentData(Entity entity);

	SpawnGroupData finalizeMobSpawn(Mob mob, ServerLevelAccessor level, DifficultyInstance difficulty, MobSpawnType type, SpawnGroupData data);

	/** Can this entity drown in water (NeoForge: fluid types; vanilla: can't breathe underwater)? */
	boolean canDrownInWater(LivingEntity entity);

	/** Has the entity finished joining its level (NeoForge's isAddedToLevel)? */
	boolean isAddedToLevel(Entity entity);

	/** Re-asks for the player's chat name / tab list name (the NameFormat / TabListNameFormat events). */
	void refreshDisplayName(ServerPlayer player);

	void refreshTabListName(ServerPlayer player);

	// --- network ---

	void sendToPlayer(ServerPlayer player, CustomPacketPayload payload, CustomPacketPayload... more);

	void sendToTracking(Entity entity, boolean andSelf, CustomPacketPayload payload, CustomPacketPayload... more);

	void sendToServer(CustomPacketPayload payload, CustomPacketPayload... more);
}
