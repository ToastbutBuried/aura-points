package com.toast.aurapoints;

import com.mojang.math.Transformation;
import com.toast.aurapoints.mixin.BlockDisplayAccessor;
import com.toast.aurapoints.mixin.DisplayAccessor;
import com.toast.aurapoints.mixin.ItemDisplayAccessor;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomModelData;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.List;

/**
 * DEV ONLY (system property aurapoints.modeltest=true, set by gradle runClient -Pmodeltest): on login, lays out every
 * effect material as a block display, the item models as item displays, a training dummy and an arena NPC in front
 * of the player, so the client can be screenshotted.
 */
@EventBusSubscriber(modid = AuraPoints.MOD_ID)
final class DevModelTest {

	private DevModelTest() {
	}

	private static final List<Block> MATERIALS = List.of(Blocks.ICE, Blocks.PACKED_ICE, Blocks.BLUE_ICE, Blocks.MAGMA_BLOCK,
			Blocks.SHROOMLIGHT, Blocks.PRISMARINE, Blocks.PRISMARINE_BRICKS, Blocks.DARK_PRISMARINE, Blocks.BLACK_CONCRETE,
			Blocks.BLACK_WOOL, Blocks.PURPLE_CONCRETE, Blocks.CRYING_OBSIDIAN, Blocks.PURPLE_STAINED_GLASS, Blocks.WHITE_CONCRETE,
			Blocks.YELLOW_STAINED_GLASS, Blocks.MANGROVE_ROOTS, Blocks.ROOTED_DIRT, Blocks.OAK_LOG, Blocks.DARK_OAK_LOG,
			Blocks.MOSS_BLOCK, Blocks.MOSS_CARPET, Blocks.AZALEA_LEAVES, Blocks.FLOWERING_AZALEA_LEAVES, Blocks.BAMBOO_BLOCK,
			Blocks.BONE_BLOCK, Blocks.RED_TERRACOTTA, Blocks.BLACKSTONE, Blocks.COBBLESTONE, Blocks.MOSSY_COBBLESTONE,
			Blocks.COBBLED_DEEPSLATE);

	@SubscribeEvent
	public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
		if (!Boolean.getBoolean("aurapoints.modeltest") || !(event.getEntity() instanceof ServerPlayer player)) return;
		com.toast.aurapoints.ability.AuraFx.later(player.getServer(), 40, () -> build(player));
	}

	private static void build(ServerPlayer player) {
		ServerLevel level = player.serverLevel();
		level.setDayTime(6000);
		player.setGameMode(GameType.SPECTATOR);
		int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, 0, 0) + 1;
		player.teleportTo(level, 0.5, y + 1.2, 0.5, 0f, 12f); // facing +z
		// two rows of materials, 15 each, 6 blocks ahead
		for (int i = 0; i < MATERIALS.size(); i++) {
			double x = 7.0 - (i % 15) * 1.0, z = 8.5, yy = y + 2.4 - (i / 15) * 1.1;
			Display.BlockDisplay d = new Display.BlockDisplay(EntityType.BLOCK_DISPLAY, level);
			d.setPos(x, yy, z);
			((BlockDisplayAccessor) d).aurapoints$setBlockState(MATERIALS.get(i).defaultBlockState());
			((DisplayAccessor) d).aurapoints$setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(0.9f), null));
			level.addFreshEntity(d);
		}
		// item models: the mod items, the handhelds and a few ability icons
		List<ItemStack> items = new java.util.ArrayList<>(List.of(new ItemStack(AuraPoints.MJOLNIR_ITEM.get()),
				new ItemStack(AuraPoints.BOSS_SIGIL_ITEM.get()), new ItemStack(AuraPoints.ROKAKA_FRUIT_ITEM.get()),
				new ItemStack(AuraPoints.ZEUS_GAUNTLETS.get()), new ItemStack(AuraPoints.THORN_SEED.get()),
				new ItemStack(AuraPoints.ICE_SPIKE_SHARD.get()), new ItemStack(AuraPoints.AURA_BOOK.get()),
				new ItemStack(AuraPoints.BALANCE_TOME.get())));
		for (var h : AuraPoints.handhelds()) items.add(new ItemStack(h.get()));
		for (int n : new int[] {1, 7, 24, 34, 43}) {
			ItemStack icon = new ItemStack(AuraPoints.ABILITY_ICON.get());
			icon.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(n));
			items.add(icon);
		}
		for (int i = 0; i < items.size(); i++) {
			Display.ItemDisplay d = new Display.ItemDisplay(EntityType.ITEM_DISPLAY, level);
			d.setPos(6.5 - (i % 9) * 1.4, y + 3.9 + (i / 9) * 1.2, 7.0);
			((ItemDisplayAccessor) d).aurapoints$setItemStack(items.get(i));
			((ItemDisplayAccessor) d).aurapoints$setItemTransform(ItemDisplayContext.GUI);
			((DisplayAccessor) d).aurapoints$setTransformation(new Transformation(new Vector3f(), new Quaternionf().rotateY((float) Math.PI),
					new Vector3f(1.1f), null));
			level.addFreshEntity(d);
		}
		var dummy = AuraPoints.TRAINING_DUMMY.get().create(level);
		if (dummy != null) {
			dummy.moveTo(-2.5, y, 4.5, 180f, 0f);
			dummy.setNoAi(true);
			level.addFreshEntity(dummy);
		}
		var npc = AuraPoints.ARENA_NPC.get().create(level);
		if (npc != null) {
			npc.moveTo(3.5, y, 4.5, 180f, 0f);
			npc.setNoAi(true);
			level.addFreshEntity(npc);
		}
		AuraPoints.LOGGER.info("[modeltest] scene built at y={} ({} materials, {} items)", y, MATERIALS.size(), items.size());
	}
}
