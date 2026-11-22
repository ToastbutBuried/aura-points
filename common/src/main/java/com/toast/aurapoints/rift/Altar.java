package com.toast.aurapoints.rift;

import com.mojang.math.Transformation;
import com.toast.aurapoints.ability.AuraFx;
import com.toast.aurapoints.ability.Relics;
import com.toast.aurapoints.item.RelicShardItem;
import com.toast.aurapoints.mixin.DisplayAccessor;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Brightness;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import java.util.List;

/**
 * The altar on a rift realm's hub (5.46.0, Relic Trials). Each of its four pedestals takes one Relic Shard of the run
 * (right-click it holding the shard); the shard then floats above it. With all four set, the ritual: the shards rise,
 * circle the altar, spiral in and fuse into the relic above the focus stone - and the realm's guardian tears out of
 * the altar and takes it. Defeat it and the relic is yours (Rifts.end).
 */
final class Altar {

	static final int RITUAL_TICKS = 150;

	final Rifts.Run run;
	final Display.ItemDisplay[] shards = new Display.ItemDisplay[4];
	int placed;
	/** Ticks into the ritual, -1 before it. */
	int ritual = -1;
	Display.ItemDisplay relic;

	Altar(Rifts.Run run) {
		this.run = run;
	}

	boolean done() {
		return ritual >= RITUAL_TICKS;
	}

	/** Which pedestal a block belongs to (its column), -1 if none. */
	int pedestalAt(BlockPos pos) {
		for (int i = 0; i < 4; i++) {
			BlockPos p = run.pedestal(i);
			if (p.getX() == pos.getX() && p.getZ() == pos.getZ() && pos.getY() >= Rifts.FLOOR && pos.getY() <= Rifts.FLOOR + 3) return i;
		}
		return -1;
	}

	/** A right-click on a pedestal. True = it was one. */
	boolean use(ServerLevel level, ServerPlayer p, BlockPos pos) {
		int i = pedestalAt(pos);
		if (i < 0) return false;
		if (shards[i] != null || ritual >= 0) {
			p.displayClientMessage(Component.literal(ritual >= 0 ? "The ritual has begun." : "A shard already rests here.").withStyle(ChatFormatting.GRAY), true);
			return true;
		}
		ItemStack hand = p.getMainHandItem();
		if (!(hand.getItem() instanceof RelicShardItem) || RelicShardItem.run(hand) != run.id) {
			p.displayClientMessage(Component.literal("The pedestal waits for a shard of this rift (" + placed + "/4).").withStyle(ChatFormatting.GRAY), true);
			return true;
		}
		int trial = RelicShardItem.trial(hand);
		if (trial >= 0 && trial < run.trials.length && !run.trials[trial].solved) {
			p.displayClientMessage(Component.literal("The shard is still unstable - finish its trial first.").withStyle(ChatFormatting.GOLD), true);
			return true;
		}
		hand.shrink(1);
		if (trial >= 0 && trial < run.trials.length) {
			run.trials[trial].shardOut = false;
			run.placedTrials.add(trial);
		}
		shards[i] = display(level, top(i), RelicShardItem.make(run.aura, run.id, trial), 0.9f);
		placed++;
		Vec3 t = top(i);
		level.playSound(null, t.x, t.y, t.z, SoundEvents.END_PORTAL_FRAME_FILL, SoundSource.BLOCKS, 1.4f, 0.8f + placed * 0.15f);
		level.sendParticles(AuraFx.dust(run.aura.rgb, 2f), t.x, t.y, t.z, 30, 0.2, 0.6, 0.2, 0);
		for (ServerPlayer o : Rifts.online(level.getServer(), run)) {
			o.displayClientMessage(Component.literal("Shard set on the altar (" + placed + "/4)").withStyle(s -> s.withColor(TextColor.fromRgb(run.aura.rgb))), true);
		}
		if (placed >= 4) start(level);
		return true;
	}

	Vec3 top(int i) {
		return Vec3.atBottomCenterOf(run.pedestal(i)).add(0, 3.4, 0);
	}

	Vec3 focus() {
		return Vec3.atBottomCenterOf(run.hub()).add(0, 2.2, 0);
	}

	private void start(ServerLevel level) {
		ritual = 0;
		for (ServerPlayer o : Rifts.online(level.getServer(), run)) {
			AuraFx.title(o, Component.literal("The Forging").withStyle(s -> s.withColor(TextColor.fromRgb(run.aura.rgb)).withBold(true)),
					Component.literal("the shards remember what they were").withStyle(ChatFormatting.GRAY));
		}
		Vec3 f = focus();
		level.playSound(null, f.x, f.y, f.z, SoundEvents.BEACON_ACTIVATE, SoundSource.BLOCKS, 2f, 0.6f);
	}

	/** Every server tick during the run. Returns true on the tick the guardian must appear. */
	boolean tick(ServerLevel level) {
		if (ritual < 0 || ritual >= RITUAL_TICKS) return false;
		ritual++;
		Vec3 f = focus();
		int t = ritual;
		if (t % 2 == 0) {
			for (int i = 0; i < 4; i++) {
				if (shards[i] == null) continue;
				Vec3 from = top(i);
				Vec3 to;
				if (t < 40) {
					// rise
					to = from.add(0, t / 40.0 * 2.5, 0);
				} else if (t < 110) {
					// circle the altar, spiralling in and up
					double k = (t - 40) / 70.0;
					double a0 = Math.atan2(from.z - f.z, from.x - f.x);
					double a = a0 + k * Math.PI * 3;
					double rad = (from.subtract(f).horizontalDistance()) * (1 - k * 0.85);
					to = new Vec3(f.x + Math.cos(a) * rad, f.y + 3.5 + k * 1.5, f.z + Math.sin(a) * rad);
				} else {
					double k = Math.min(1, (t - 110) / 16.0);
					double a0 = Math.atan2(from.z - f.z, from.x - f.x) + Math.PI * 3;
					double rad = from.subtract(f).horizontalDistance() * 0.15 * (1 - k);
					to = new Vec3(f.x + Math.cos(a0) * rad, f.y + 5 - k * 0.5, f.z + Math.sin(a0) * rad);
				}
				shards[i].moveTo(to.x, to.y, to.z, shards[i].getYRot() + 12f, 0);
				level.sendParticles(AuraFx.dust(run.aura.rgb, 1.2f), to.x, to.y, to.z, 2, 0.05, 0.05, 0.05, 0);
			}
		}
		if (t % 20 == 0 && t < 110) level.playSound(null, f.x, f.y, f.z, SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.BLOCKS, 1.6f, 0.5f + t / 110f);
		if (t > 40 && t < 126 && t % 3 == 0) {
			level.sendParticles(ParticleTypes.END_ROD, f.x, f.y, f.z, 3, 0.1, 2.5, 0.1, 0.02);
		}
		if (t == 126) {
			// fused
			for (int i = 0; i < 4; i++) {
				if (shards[i] != null) shards[i].discard();
				shards[i] = null;
			}
			relic = display(level, f.add(0, 4.5, 0), Relics.stack(run.aura), 1.6f);
			level.sendParticles(ParticleTypes.FLASH, f.x, f.y + 4.5, f.z, 1, 0, 0, 0, 0);
			level.sendParticles(AuraFx.dust(run.aura.rgb, 3f), f.x, f.y + 4.5, f.z, 80, 1.2, 1.2, 1.2, 0);
			level.playSound(null, f.x, f.y, f.z, SoundEvents.TOTEM_USE, SoundSource.BLOCKS, 1.4f, 0.8f);
			for (ServerPlayer o : Rifts.online(level.getServer(), run)) {
				AuraFx.title(o, Component.literal(Relics.name(run.aura)).withStyle(s -> s.withColor(TextColor.fromRgb(run.aura.rgb)).withBold(true)),
						Component.literal("...something stirs beneath the altar").withStyle(ChatFormatting.DARK_RED));
			}
		}
		if (t > 126 && t % 4 == 0) {
			// the ground shakes
			level.playSound(null, f.x, f.y, f.z, SoundEvents.WARDEN_DIG, SoundSource.HOSTILE, 1.8f, 0.6f);
			level.sendParticles(new net.minecraft.core.particles.BlockParticleOption(ParticleTypes.BLOCK, RealmBuilder.palette(run.aura).accent().defaultBlockState()),
					f.x, f.y - 1.2, f.z, 30, 2.5, 0.2, 2.5, 0.2);
		}
		if (t >= RITUAL_TICKS) {
			if (relic != null) relic.discard();
			relic = null;
			level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, f.x, f.y, f.z, 1, 0, 0, 0, 0);
			level.playSound(null, f.x, f.y, f.z, SoundEvents.WARDEN_EMERGE, SoundSource.HOSTILE, 2.5f, 0.7f);
			return true;
		}
		return false;
	}

	private Display.ItemDisplay display(ServerLevel level, Vec3 at, ItemStack stack, float scale) {
		Display.ItemDisplay d = EntityType.ITEM_DISPLAY.create(level);
		if (d == null) return null;
		d.getSlot(0).set(stack);
		DisplayAccessor acc = (DisplayAccessor) d;
		acc.aurapoints$setTransformation(new Transformation(new Vector3f(), null, new Vector3f(scale, scale, scale), null));
		acc.aurapoints$setBrightnessOverride(new Brightness(15, 15));
		acc.aurapoints$setPosRotInterpolationDuration(2);
		acc.aurapoints$setViewRange(2f);
		d.moveTo(at.x, at.y, at.z, 0, 0);
		d.addTag(Trials.DISPLAY_TAG);
		level.addFreshEntity(d);
		return d;
	}

	void remove() {
		for (int i = 0; i < 4; i++) {
			if (shards[i] != null) shards[i].discard();
			shards[i] = null;
		}
		if (relic != null) relic.discard();
		relic = null;
	}

	List<Display.ItemDisplay> all() {
		java.util.ArrayList<Display.ItemDisplay> out = new java.util.ArrayList<>();
		for (Display.ItemDisplay d : shards) if (d != null) out.add(d);
		if (relic != null) out.add(relic);
		return out;
	}
}
