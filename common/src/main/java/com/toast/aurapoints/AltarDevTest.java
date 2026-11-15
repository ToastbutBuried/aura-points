package com.toast.aurapoints;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.toast.aurapoints.ability.AuraType;
import com.toast.aurapoints.arena.AltarRitual;
import com.toast.aurapoints.arena.Arena;
import com.toast.aurapoints.arena.ArenaNpc;
import com.toast.aurapoints.arena.NpcKind;
import com.toast.aurapoints.ability.AuraFx;
import com.toast.aurapoints.config.AuraConfig;
import com.toast.aurapoints.data.AuraPlayerData;
import com.toast.aurapoints.data.AuraState;
import com.toast.aurapoints.shim.neoforge.common.util.FakePlayerFactory;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StainedGlassBlock;
import net.minecraft.world.phys.AABB;

import java.util.UUID;

/**
 * DEV ONLY (registered by DevTest): the awakening altar (5.26).
 * <ul>
 * <li>/auradev altartest - the island is built, and a fake player goes through every refusal (quest, Mastery, Aura,
 * place, busy, no Ultimate), the ritual itself (awakened, the cost taken, it runs and ends) and the replay with its
 * skip. Logs [altartest] lines with "(must be ...)".</li>
 * <li>/auradev ritual &lt;aura&gt; - a real player: taken to the altar, the show plays for that aura, nothing checked,
 * nothing taken, nothing awakened (to look at the cutscene).</li>
 * </ul>
 */
public final class AltarDevTest {

	private AltarDevTest() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("auradev").then(Commands.literal("ritual")
				.then(Commands.argument("aura", StringArgumentType.word()).executes(ctx -> {
					ServerPlayer player = ctx.getSource().getPlayerOrException();
					AuraType aura = AuraType.byId(StringArgumentType.getString(ctx, "aura"));
					ServerLevel arena = Arena.level(player.getServer());
					if (aura == null || arena == null) return 0;
					if (player.level() != arena) player.teleportTo(arena, Arena.ALTAR.x, Arena.ALTAR.y, Arena.ALTAR.z, 90f, 0f);
					AuraFx.later(player.getServer(), 20, () -> AltarRitual.start(player, aura, true));
					return 1;
				}))));

		dispatcher.register(Commands.literal("auradev").then(Commands.literal("altartest").executes(ctx -> {
			MinecraftServer server = ctx.getSource().getServer();
			ServerLevel arena = Arena.level(server);
			if (arena == null) {
				log("no arena dimension (must not happen)");
				return 0;
			}
			AuraState state = AuraState.get(server);
			// the map
			int ax = Arena.ALTAR_X, fl = Arena.FLOOR;
			int glass = 0;
			for (BlockPos pos : BlockPos.betweenClosed(ax - Arena.ALTAR_R, fl + 5, -Arena.ALTAR_R, ax + Arena.ALTAR_R, fl + 5, Arena.ALTAR_R)) {
				if (arena.getBlockState(pos).getBlock() instanceof StainedGlassBlock) glass++;
			}
			// entities only load with their chunk: hold the keeper's while the test runs
			arena.setChunkForced((ax - Arena.ALTAR_R + 2) >> 4, -1, true);
			arena.setChunkForced(ax >> 4, 0, true); // the dais and half the pillars: where the props go
			log("map version " + state.arenaMapVersion + " (must be 9), dais heart " + arena.getBlockState(new BlockPos(ax, fl + 1, 0)).is(Blocks.CRYING_OBSIDIAN)
					+ " (must be true), bridge " + arena.getBlockState(new BlockPos(29, fl, 0)).is(Blocks.QUARTZ_BRICKS) + " (must be true), rail gap "
					+ arena.getBlockState(new BlockPos(26, fl + 1, 0)).isAir() + " (must be true)");
			log("aura pillars " + glass + " (must be " + AltarRitual.auras().size() + " = 10)");

			ServerPlayer fake = FakePlayerFactory.get(arena, new GameProfile(UUID.fromString("5d1e3c1a-0000-4000-8000-00000000c301"), "AltarTest"));
			fake.moveTo(Arena.ALTAR.x - 3, Arena.ALTAR.y - 1, Arena.ALTAR.z, 0, 0);
			AltarRitual.testPlayer = fake;
			AuraPlayerData data = state.getOrCreate(fake.getUUID());
			data.aura = AuraType.FIRE;
			data.rolled.add(AuraType.FIRE);
			data.awakened.clear();
			data.altarUnlocked.clear();
			data.arenaRealAura = null;
			data.mastery.put(AuraType.FIRE, 3);
			AuraCurrency.set(fake, 500);
			log("no quest: '" + AltarRitual.begin(fake, false) + "' (must name the Awakening quest)");
			log("unlock " + AltarRitual.unlock(fake, AuraType.FIRE, true) + " again " + AltarRitual.unlock(fake, AuraType.FIRE, true) + " (must be true false)");
			log("Mastery III: '" + AltarRitual.begin(fake, false) + "' (must ask for Mastery " + AuraConfig.ULT_ALTAR_MASTERY + ")");
			data.mastery.put(AuraType.FIRE, 5);
			log("500 Aura: '" + AltarRitual.begin(fake, false) + "' (must name the cost)");
			AuraCurrency.set(fake, 1000);
			fake.moveTo(Arena.ALTAR.x - 60, Arena.ALTAR.y - 1, Arena.ALTAR.z, 0, 0);
			log("60 blocks away: '" + AltarRitual.begin(fake, false) + "' (must say come to the altar)");
			fake.moveTo(Arena.ALTAR.x - 3, Arena.ALTAR.y - 1, Arena.ALTAR.z, 0, 0);
			log("replay before awakening: '" + AltarRitual.begin(fake, true) + "' (must say not awakened)");
			String began = AltarRitual.begin(fake, false);
			log("ritual: refusal " + began + " awakened " + data.awakened.contains(AuraType.FIRE) + " balance " + AuraCurrency.get(fake) + " running " + AltarRitual.running()
					+ " (must be null true " + (1000 - AuraConfig.ULT_ALTAR_COST) + " true)");
			log("a second one meanwhile: '" + AltarRitual.begin(fake, true) + "' (must say under way), skip a first ritual " + AltarRitual.skip(fake) + " (must be false)");
			AuraFx.later(server, 30, () -> {
				log("30 ticks in: running " + AltarRitual.running() + " in ritual " + AltarRitual.inRitual(fake) + " (must be true true)");
				long keepers = arena.getEntitiesOfClass(ArenaNpc.class, new AABB(ax - 10, fl - 2, -10, ax + 10, fl + 8, 10), n -> n.kind() == NpcKind.ALTAR).size();
				log("altar keepers " + keepers + " (must be 1)");
				int props = arena.getEntitiesOfClass(net.minecraft.world.entity.Display.ItemDisplay.class, new AABB(ax - 10, fl - 2, -10, ax + 10, fl + 16, 10), d -> d.getTags().contains("aurapoints_altar_prop")).size();
				log("ritual props " + AltarRitual.liveProps() + " (in loaded chunks " + props + ") all-up " + (AltarRitual.liveProps() == 2 + AltarRitual.auras().size()) + " (must be true)");
				arena.setChunkForced((ax - Arena.ALTAR_R + 2) >> 4, -1, false);
				AuraFx.later(server, AltarRitual.END, () -> {
					log("after its length: running " + AltarRitual.running() + " (must be false), no gravity " + fake.isNoGravity() + " (must be false)");
					log("again: '" + AltarRitual.begin(fake, false) + "' (must say awakened already)");
					String replay = AltarRitual.begin(fake, true);
					log("replay: refusal " + replay + " balance " + AuraCurrency.get(fake) + " running " + AltarRitual.running() + " (must be null " + (1000 - AuraConfig.ULT_ALTAR_COST) + " true)");
					log("skip the replay " + AltarRitual.skip(fake) + " running " + AltarRitual.running() + " (must be true false)");
					arena.setChunkForced(ax >> 4, 0, false);
					log("props left after the skip " + (AltarRitual.liveProps() == 0 && arena.getEntitiesOfClass(net.minecraft.world.entity.Display.ItemDisplay.class, new AABB(ax - 10, fl - 2, -10, ax + 10, fl + 16, 10), d -> d.getTags().contains("aurapoints_altar_prop")).size() == 0) + " (must be true)");
					data.aura = AuraType.RESONANCE;
					log("Resonance: '" + AltarRitual.begin(fake, false) + "' (must say no Ultimate)");
					data.aura = AuraType.FIRE;
					data.awakened.clear();
					data.altarUnlocked.clear();
					AltarRitual.testPlayer = null;
					log("altartest done");
				});
			});
			return 1;
		})));
	}

	private static void log(String what) {
		AuraPoints.LOGGER.info("[altartest] {}", what);
	}
}
