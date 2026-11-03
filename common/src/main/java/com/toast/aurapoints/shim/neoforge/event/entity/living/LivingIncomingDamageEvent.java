package com.toast.aurapoints.shim.neoforge.event.entity.living;

import com.toast.aurapoints.shim.neoforge.common.damagesource.DamageContainer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;

import java.util.ArrayList;
import java.util.List;

/** Before armor and enchantments: change the amount, cancel the hit, or override a reduction. */
public class LivingIncomingDamageEvent extends com.toast.aurapoints.shim.bus.api.Event implements com.toast.aurapoints.shim.bus.api.ICancellableEvent {
	public record ReductionModifier(DamageContainer.Reduction type, DamageContainer.IReductionFunction function) {
	}

	private final LivingEntity entity;
	private final DamageSource source;
	private float amount;
	private final List<ReductionModifier> reductionModifiers = new ArrayList<>();

	public LivingIncomingDamageEvent(LivingEntity entity, DamageSource source, float amount) {
		this.entity = entity;
		this.source = source;
		this.amount = amount;
	}

	public LivingEntity getEntity() {
		return entity;
	}

	public DamageSource getSource() {
		return source;
	}

	public float getAmount() {
		return amount;
	}

	public void setAmount(float amount) {
		this.amount = amount;
	}

	public void addReductionModifier(DamageContainer.Reduction type, DamageContainer.IReductionFunction function) {
		reductionModifiers.add(new ReductionModifier(type, function));
	}

	/** For the loader layer. */
	public List<ReductionModifier> reductionModifiers() {
		return reductionModifiers;
	}
}
