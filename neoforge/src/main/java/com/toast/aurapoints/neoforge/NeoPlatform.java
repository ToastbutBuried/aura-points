package com.toast.aurapoints.neoforge;

import com.mojang.authlib.GameProfile;
import com.toast.aurapoints.AuraPoints;
import com.toast.aurapoints.shim.Platform;
import com.toast.aurapoints.shim.neoforge.common.ModConfigSpec;
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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ServerLevelAccessor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.common.NeoForgeMod;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.EventHooks;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** common/'s view of NeoForge: everything as it was before the multiloader split. */
public final class NeoPlatform implements Platform {
	private final IEventBus modBus;
	private final ModContainer container;

	/** The accessory slots attachment, same id and format as 5.3.0 and before ("aurapoints:accessories"). */
	static final DeferredRegister<AttachmentType<?>> ATTACHMENTS = DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, AuraPoints.MOD_ID);
	static final Supplier<AttachmentType<ItemStackHandler>> ACCESSORIES = ATTACHMENTS.register("accessories",
			() -> AttachmentType.serializable(() -> new ItemStackHandler(com.toast.aurapoints.AccessorySlots.MAX)).copyOnDeath().build());

	NeoPlatform(IEventBus modBus, ModContainer container) {
		this.modBus = modBus;
		this.container = container;
	}

	@Override
	public String loader() {
		return "neoforge";
	}

	@Override
	public boolean isClient() {
		return FMLEnvironment.dist.isClient();
	}

	@Override
	public boolean isModLoaded(String modId) {
		return ModList.get().isLoaded(modId);
	}

	@Override
	public Path gameDir() {
		return FMLPaths.GAMEDIR.get();
	}

	@Override
	public Path configDir() {
		return FMLPaths.CONFIGDIR.get();
	}

	@Override
	public MinecraftServer currentServer() {
		return ServerLifecycleHooks.getCurrentServer();
	}

	@Override
	public <T> void register(com.toast.aurapoints.shim.neoforge.registries.DeferredRegister<T> register) {
		DeferredRegister<T> real = DeferredRegister.create(register.getRegistryKey(), register.getNamespace());
		for (var entry : register.entries()) real.register(entry.id().getPath(), entry.factory());
		real.register(modBus);
	}

	@Override
	public void registerClientConfig(ModConfigSpec spec, String fileName) {
		net.neoforged.neoforge.common.ModConfigSpec.Builder b = new net.neoforged.neoforge.common.ModConfigSpec.Builder();
		List<Runnable> binds = new ArrayList<>();
		for (ModConfigSpec.Op op : spec.ops()) {
			switch (op) {
				case ModConfigSpec.Push push -> {
					if (push.comment().length > 0) b.comment(push.comment());
					if (push.translation() != null) b.translation(push.translation());
					b.push(push.name());
				}
				case ModConfigSpec.Pop pop -> b.pop();
				case ModConfigSpec.Value v -> binds.add(define(b, v.value()));
			}
		}
		net.neoforged.neoforge.common.ModConfigSpec real = b.build();
		container.registerConfig(ModConfig.Type.CLIENT, real, fileName);
		binds.forEach(Runnable::run);
		spec.bindSaver(real::save);
	}

	/** Defines one value on the real builder; the returned task binds the shim value to it. */
	@SuppressWarnings({"unchecked", "rawtypes"})
	private static Runnable define(net.neoforged.neoforge.common.ModConfigSpec.Builder b, ModConfigSpec.ConfigValue<?> v) {
		if (v.comment().length > 0) b.comment(v.comment());
		if (v.translation() != null) b.translation(v.translation());
		String key = v.getPath().get(v.getPath().size() - 1);
		net.neoforged.neoforge.common.ModConfigSpec.ConfigValue real;
		if (v instanceof ModConfigSpec.BooleanValue bv) real = b.define(key, (boolean) bv.getDefault());
		else if (v instanceof ModConfigSpec.IntValue iv) real = b.defineInRange(key, (int) iv.getDefault(), iv.min, iv.max);
		else if (v instanceof ModConfigSpec.DoubleValue dv) real = b.defineInRange(key, (double) dv.getDefault(), dv.min, dv.max);
		else if (v instanceof ModConfigSpec.EnumValue ev) real = b.defineEnum(key, (Enum) ev.getDefault());
		else real = b.define(key, (Object) v.getDefault(), v::valid);
		net.neoforged.neoforge.common.ModConfigSpec.ConfigValue r = real;
		ModConfigSpec.ConfigValue sv = v;
		return () -> sv.bind(r::get, r::set, r::save);
	}

	@Override
	public ServerPlayer fakePlayer(ServerLevel level, GameProfile profile) {
		return FakePlayerFactory.get(level, profile);
	}

	@Override
	public boolean isFakePlayer(Entity entity) {
		return entity instanceof FakePlayer;
	}

	@Override
	public com.toast.aurapoints.shim.neoforge.items.ItemStackHandler accessories(Player player) {
		ItemStackHandler h = player.getData(ACCESSORIES);
		return new com.toast.aurapoints.shim.neoforge.items.ItemStackHandler() {
			@Override
			public int getSlots() {
				return h.getSlots();
			}

			@Override
			public ItemStack getStackInSlot(int slot) {
				return h.getStackInSlot(slot);
			}

			@Override
			public void setStackInSlot(int slot, ItemStack stack) {
				h.setStackInSlot(slot, stack);
			}
		};
	}

	@Override
	public CompoundTag persistentData(Entity entity) {
		return entity.getPersistentData();
	}

	@Override
	public SpawnGroupData finalizeMobSpawn(Mob mob, ServerLevelAccessor level, DifficultyInstance difficulty, MobSpawnType type, SpawnGroupData data) {
		return EventHooks.finalizeMobSpawn(mob, level, difficulty, type, data);
	}

	@Override
	public boolean canDrownInWater(LivingEntity entity) {
		return entity.canDrownInFluidType(NeoForgeMod.WATER_TYPE.value());
	}

	@Override
	public boolean isAddedToLevel(Entity entity) {
		return entity.isAddedToLevel();
	}

	@Override
	public void refreshDisplayName(ServerPlayer player) {
		player.refreshDisplayName();
	}

	@Override
	public void refreshTabListName(ServerPlayer player) {
		player.refreshTabListName();
	}

	@Override
	public void sendToPlayer(ServerPlayer player, CustomPacketPayload payload, CustomPacketPayload... more) {
		PacketDistributor.sendToPlayer(player, payload, more);
	}

	@Override
	public void sendToTracking(Entity entity, boolean andSelf, CustomPacketPayload payload, CustomPacketPayload... more) {
		if (andSelf) PacketDistributor.sendToPlayersTrackingEntityAndSelf(entity, payload, more);
		else PacketDistributor.sendToPlayersTrackingEntity(entity, payload, more);
	}

	@Override
	public void sendToServer(CustomPacketPayload payload, CustomPacketPayload... more) {
		PacketDistributor.sendToServer(payload, more);
	}
}
