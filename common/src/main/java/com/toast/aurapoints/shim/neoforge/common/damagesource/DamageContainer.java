package com.toast.aurapoints.shim.neoforge.common.damagesource;

/** The damage reductions a listener can override (only the kinds Aura Points uses). */
public final class DamageContainer {
	private DamageContainer() {
	}

	public enum Reduction {
		ARMOR,
		ENCHANTMENTS,
		MOB_EFFECTS,
		ABSORPTION
	}

	/** New reduction from the one vanilla worked out; the container is always null in the shim. */
	@FunctionalInterface
	public interface IReductionFunction {
		float modify(Object container, float reductionIn);
	}
}
