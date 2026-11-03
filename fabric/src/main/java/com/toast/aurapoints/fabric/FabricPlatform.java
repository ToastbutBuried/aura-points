package com.toast.aurapoints.fabric;

import com.mojang.authlib.GameProfile;
import com.toast.aurapoints.shim.Platform;
import com.toast.aurapoints.shim.neoforge.common.ModConfigSpec;
import com.toast.aurapoints.shim.neoforge.items.ItemStackHandler;
import com.toast.aurapoints.shim.neoforge.registries.DeferredRegister;
import net.fabricmc.api.EnvType;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
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
import java.util.EnumSet;

/** common/'s view of Fabric. */
public final class FabricPlatform implements Platform {
	/** Set by the server life-cycle callbacks (AuraFabric). */
	static volatile MinecraftServer server;

	@Override
	public String loader() {
		return "fabric";
	}

	@Override
	public boolean isClient() {
		return FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT;
	}

	@Override
	public boolean isModLoaded(String modId) {
		return FabricLoader.getInstance().isModLoaded(modId);
	}

	@Override
	public Path gameDir() {
		return FabricLoader.getInstance().getGameDir();
	}

	@Override
	public Path configDir() {
		return FabricLoader.getInstance().getConfigDir();
	}

	@Override
	public MinecraftServer currentServer() {
		return server;
	}

	@Override
	@SuppressWarnings("unchecked")
	public <T> void register(DeferredRegister<T> register) {
		Registry<T> registry = (Registry<T>) BuiltInRegistries.REGISTRY.get(register.getRegistryKey().location());
		if (registry == null) throw new IllegalStateException("Unknown registry " + register.getRegistryKey());
		for (var entry : register.entries()) Registry.register(registry, entry.id(), entry.factory().get());
	}

	@Override
	public void registerClientConfig(ModConfigSpec spec, String fileName) {
		// like NeoForge: client settings only exist on the client
		if (isClient()) FabricConfig.load(spec, configDir().resolve(fileName));
	}

	@Override
	public ServerPlayer fakePlayer(ServerLevel level, GameProfile profile) {
		return FakePlayer.get(level, profile);
	}

	@Override
	public boolean isFakePlayer(Entity entity) {
		return entity instanceof FakePlayer;
	}

	@Override
	public ItemStackHandler accessories(Player player) {
		return FabricAttachments.accessories(player);
	}

	@Override
	public CompoundTag persistentData(Entity entity) {
		return FabricAttachments.persistentData(entity);
	}

	@Override
	public SpawnGroupData finalizeMobSpawn(Mob mob, ServerLevelAccessor level, DifficultyInstance difficulty, MobSpawnType type, SpawnGroupData data) {
		// FinalizeSpawnEvent fires inside (mixin/MobMixin), as it does through NeoForge's EventHooks
		return mob.finalizeSpawn(level, difficulty, type, data);
	}

	@Override
	public boolean canDrownInWater(LivingEntity entity) {
		return !entity.canBreatheUnderwater();
	}

	@Override
	public boolean isAddedToLevel(Entity entity) {
		return entity.level() instanceof ServerLevel level ? level.getEntity(entity.getUUID()) == entity : !entity.isRemoved();
	}

	@Override
	public void refreshDisplayName(ServerPlayer player) {
		// the chat name is worked out fresh every time (mixin/PlayerMixin)
	}

	@Override
	public void refreshTabListName(ServerPlayer player) {
		if (player.getServer() == null) return;
		player.getServer().getPlayerList().broadcastAll(new ClientboundPlayerInfoUpdatePacket(
				EnumSet.of(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME), java.util.List.of(player)));
	}

	@Override
	public void sendToPlayer(ServerPlayer player, CustomPacketPayload payload, CustomPacketPayload... more) {
		ServerPlayNetworking.send(player, payload);
		for (CustomPacketPayload p : more) ServerPlayNetworking.send(player, p);
	}

	@Override
	public void sendToTracking(Entity entity, boolean andSelf, CustomPacketPayload payload, CustomPacketPayload... more) {
		for (ServerPlayer p : PlayerLookup.tracking(entity)) {
			if (p == entity) continue;
			sendToPlayer(p, payload, more);
		}
		if (andSelf && entity instanceof ServerPlayer self) sendToPlayer(self, payload, more);
	}

	@Override
	public void sendToServer(CustomPacketPayload payload, CustomPacketPayload... more) {
		com.toast.aurapoints.fabric.client.ClientNet.send(payload, more);
	}
}
