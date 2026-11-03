package com.toast.aurapoints.arena;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * A clickable NPC in the arena (NpcKind says which): stands still on its spot, can't be hurt or
 * pushed, turns its head to whoever is nearest, and wears its kind's colors. Its name floats above
 * it as a hologram (Holograms), not as a name tag.
 */
public class ArenaNpc extends Mob {

	private static final EntityDataAccessor<Integer> KIND = SynchedEntityData.defineId(ArenaNpc.class, EntityDataSerializers.INT);

	private Vec3 home;
	private float homeYaw;

	public ArenaNpc(EntityType<? extends Mob> type, Level level) {
		super(type, level);
		setNoAi(true);
		setPersistenceRequired();
		setInvulnerable(true);
		setNoGravity(true);
	}

	public static AttributeSupplier.Builder createAttributes() {
		return Mob.createMobAttributes();
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		super.defineSynchedData(builder);
		builder.define(KIND, NpcKind.DUELS.ordinal());
	}

	public NpcKind kind() {
		return NpcKind.byId(entityData.get(KIND));
	}

	/** Puts an NPC of {@code kind} on {@code pos}, facing {@code yaw}, dressed in its colors. */
	static void spawn(ServerLevel level, NpcKind kind, Vec3 pos, float yaw) {
		ArenaNpc npc = com.toast.aurapoints.AuraPoints.ARENA_NPC.get().create(level);
		if (npc == null) return;
		npc.entityData.set(KIND, kind.ordinal());
		npc.moveTo(pos.x, pos.y, pos.z, yaw, 0f);
		npc.setYHeadRot(yaw);
		npc.setYBodyRot(yaw);
		npc.home = pos;
		npc.homeYaw = yaw;
		var armor = new net.minecraft.world.item.Item[]{Items.LEATHER_HELMET, Items.LEATHER_CHESTPLATE, Items.LEATHER_LEGGINGS, Items.LEATHER_BOOTS};
		var slots = new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
		for (int i = 0; i < slots.length; i++) {
			// no helmet: the face (and its skin) shows
			if (slots[i] == EquipmentSlot.HEAD) continue;
			ItemStack piece = new ItemStack(armor[i]);
			piece.set(DataComponents.DYED_COLOR, new DyedItemColor(kind.dye, false));
			npc.setItemSlot(slots[i], piece);
			npc.setDropChance(slots[i], 0f);
		}
		npc.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(kind.held));
		npc.setDropChance(EquipmentSlot.MAINHAND, 0f);
		ArenaBuilder.claim(npc);
		level.addFreshEntity(npc);
	}

	@Override
	public void tick() {
		super.tick();
		if (level().isClientSide) return;
		if (home == null) {
			home = position();
			homeYaw = getYRot();
		}
		if (position().distanceToSqr(home) > 1.0e-4) setPos(home.x, home.y, home.z);
		setDeltaMovement(Vec3.ZERO);
		setYRot(homeYaw);
		setYBodyRot(homeYaw);
		// looks at whoever is nearest (head only, within reason)
		if (tickCount % 2 == 0) {
			ServerPlayer near = (ServerPlayer) level().getNearestPlayer(this, 8);
			float target = homeYaw;
			if (near != null && !near.isSpectator()) {
				double dx = near.getX() - getX(), dz = near.getZ() - getZ();
				float look = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90f;
				float off = net.minecraft.util.Mth.wrapDegrees(look - homeYaw);
				target = homeYaw + net.minecraft.util.Mth.clamp(off, -70f, 70f);
				double dy = near.getEyeY() - getEyeY();
				setXRot((float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz))));
			} else {
				setXRot(0f);
			}
			setYHeadRot(target);
		}
	}

	@Override
	public boolean isInvulnerableTo(DamageSource source) {
		return !source.is(net.minecraft.tags.DamageTypeTags.BYPASSES_INVULNERABILITY);
	}

	@Override
	public void knockback(double strength, double x, double z) {
	}

	@Override
	public boolean isPushable() {
		return false;
	}

	@Override
	protected void doPush(Entity entity) {
	}

	@Override
	public boolean removeWhenFarAway(double distance) {
		return false;
	}

	@Override
	public boolean shouldShowName() {
		return false;
	}

	@Override
	public void addAdditionalSaveData(CompoundTag tag) {
		super.addAdditionalSaveData(tag);
		tag.putInt("Kind", entityData.get(KIND));
		if (home != null) {
			tag.putDouble("HomeX", home.x);
			tag.putDouble("HomeY", home.y);
			tag.putDouble("HomeZ", home.z);
			tag.putFloat("HomeYaw", homeYaw);
		}
	}

	@Override
	public void readAdditionalSaveData(CompoundTag tag) {
		super.readAdditionalSaveData(tag);
		entityData.set(KIND, tag.getInt("Kind"));
		if (tag.contains("HomeX")) {
			home = new Vec3(tag.getDouble("HomeX"), tag.getDouble("HomeY"), tag.getDouble("HomeZ"));
			homeYaw = tag.getFloat("HomeYaw");
		}
	}
}
