package com.toast.aurapoints.client;

import com.toast.aurapoints.ability.AuraAbilities;
import com.toast.aurapoints.ability.AuraAbilities.Ability;
import com.toast.aurapoints.ability.AuraSounds;
import com.toast.aurapoints.ability.AuraType;
import com.toast.aurapoints.config.AuraClientConfig;
import com.toast.aurapoints.network.ClientAuraState;
import com.toast.aurapoints.network.IceShellPacket;
import net.minecraft.client.Minecraft;
import com.toast.aurapoints.shim.api.distmarker.Dist;
import com.toast.aurapoints.shim.bus.api.SubscribeEvent;
import com.toast.aurapoints.shim.fml.common.EventBusSubscriber;
import com.toast.aurapoints.shim.neoforge.client.event.ClientTickEvent;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * CLIENT ONLY. Watches the cooldowns every client tick - not only while the HUD draws them, so a
 * cooldown that ends while its slot isn't shown (gliding sets, F1, another screen) chimes when it
 * really ends, not the next time the slot shows up. Also keeps the moments the HUD animates:
 * when a slot came off cooldown, was pressed, or was pressed too early.
 */
@EventBusSubscriber(modid = "aurapoints", value = Dist.CLIENT)
public final class ClientCooldowns {

	private ClientCooldowns() {
	}

	/** When each ability last came off cooldown (millis), main and flight sets. */
	private static final Map<Ability, Long> READY_AT = new EnumMap<>(Ability.class);
	private static final Map<Ability, Long> READY_AT_FLIGHT = new EnumMap<>(Ability.class);
	/** Pressed (it went off) / pressed while still cooling down. */
	private static final Map<Ability, Long> PRESSED_AT = new EnumMap<>(Ability.class);
	private static final Map<Ability, Long> DENIED_AT = new EnumMap<>(Ability.class);
	/** Abilities whose last-second tick already played this cooldown. */
	private static final java.util.Set<Ability> TICKED = java.util.EnumSet.noneOf(Ability.class);

	/** Ice Shell: whether it was whole last tick, and when it became whole. */
	private static boolean shellWasWhole = true;
	private static long shellWholeAt = 0;

	public static Long readyAt(Ability ability, boolean flight) {
		return (flight ? READY_AT_FLIGHT : READY_AT).get(ability);
	}

	public static Long pressedAt(Ability ability) {
		return PRESSED_AT.get(ability);
	}

	public static Long deniedAt(Ability ability) {
		return DENIED_AT.get(ability);
	}

	public static long shellWholeAt() {
		return shellWholeAt;
	}

	public static void clear() {
		READY_AT.clear();
		READY_AT_FLIGHT.clear();
		PRESSED_AT.clear();
		DENIED_AT.clear();
		TICKED.clear();
		shellWasWhole = true;
	}

	@SubscribeEvent
	public static void onClientTick(ClientTickEvent.Post event) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return;
		long now = System.currentTimeMillis();
		AuraType aura = ClientAuraState.aura;
		check(null, READY_AT, aura, now);
		check(AuraAbilities.Slot.FLIGHT, READY_AT_FLIGHT, aura, now);
		// the Wind launch bar just runs out, no chime of its own
		for (Ability a : Ability.values()) {
			long[] cd = ClientAuraState.cooldown(a, AuraAbilities.Slot.LAUNCH);
			if (cd != null && now >= cd[1]) ClientAuraState.clearCooldown(a, AuraAbilities.Slot.LAUNCH);
		}

		IceShellPacket shell = ClientAuraState.iceShell;
		boolean whole = shell == null || (shell.layers() >= Math.max(1, shell.maxLayers()) && shell.top() >= 0.999f);
		if (whole && !shellWasWhole) {
			shellWholeAt = now;
			if (aura != null && aura.has(Ability.ICE_SHELL, ClientAuraState.branch)) ClientSounds.readyChime(aura);
		}
		shellWasWhole = whole;
	}

	private static void check(AuraAbilities.Slot slot, Map<Ability, Long> readyAt, AuraType aura, long now) {
		List<Ability> done = new ArrayList<>();
		for (Ability a : Ability.values()) {
			long[] cd = ClientAuraState.cooldown(a, slot);
			if (cd == null) continue;
			if (now >= cd[1]) {
				done.add(a);
			} else if (slot == null && cd[1] - now <= 1000 && cd[1] - cd[0] > 2000 && TICKED.add(a)
					&& AuraClientConfig.ALMOST_READY_TICK.get()) {
				ClientSounds.ui(AuraSounds.UI_ALMOST_READY.value(), 1.0f, 1.0f);
			}
		}
		for (Ability a : done) {
			ClientAuraState.clearCooldown(a, slot);
			readyAt.put(a, now);
			if (slot == null) TICKED.remove(a);
			// only for abilities this aura shows; leftovers from an old aura stay quiet
			if (aura != null && aura.has(a, ClientAuraState.branch)) ClientSounds.readyChime(aura, slotPitch(aura, a));
		}
	}

	/** A rising step per HUD slot (slotChimes), so each ability's chime is its own; 1 with the option off. */
	private static final float[] SLOT_PITCH = {1.0f, 1.12f, 1.26f, 1.41f, 1.5f, 1.68f};

	private static float slotPitch(AuraType aura, Ability a) {
		if (!AuraClientConfig.SLOT_CHIMES.get()) return 1.0f;
		int i = com.toast.aurapoints.network.ClientAuraHud.shownAbilities(aura).indexOf(a);
		return i < 0 ? 1.0f : SLOT_PITCH[Math.min(i, SLOT_PITCH.length - 1)];
	}

	/** An Ability key went down (ClientKeyHandler): punch the slot, or shake it if it's still cooling down. */
	/** When a Mage spell was last refused for lack of mana (the mana bar flashes). */
	private static Long lastManaDenied = null;

	public static Long lastDenied() {
		return lastManaDenied;
	}

	public static void onKeyPressed(int index) {
		AuraType aura = ClientAuraState.aura;
		Minecraft mc = Minecraft.getInstance();
		if (aura == null || mc.player == null) return;
		Ability ability = aura.slot(index, ClientAuraState.branch);
		if (ability == null || !ClientAuraState.isOwned(ability) || ClientAuraState.isDisabled(ability)) return;
		boolean flight = mc.player.isFallFlying() && ClientAuraState.hasFlightVersion(ability);
		long[] cd = ClientAuraState.cooldown(ability, flight ? AuraAbilities.Slot.FLIGHT : null);
		long now = System.currentTimeMillis();
		// a Mage spell they can't pay for (the server says so too)
		boolean starved = ClientAuraState.mana >= 0 && ClientAuraState.manaCost(ability) > ClientAuraState.mana + 1.0e-3f;
		if (cd != null && now < cd[1] || starved) {
			DENIED_AT.put(ability, now);
			if (starved) lastManaDenied = now;
			if (AuraClientConfig.NOT_READY_SOUND.get()) ClientSounds.ui(AuraSounds.UI_NOT_READY.value(), 1.0f, 1.0f);
		} else {
			PRESSED_AT.put(ability, now);
		}
	}
}
