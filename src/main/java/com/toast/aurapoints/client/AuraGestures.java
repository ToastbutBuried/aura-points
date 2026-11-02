package com.toast.aurapoints.client;

import com.toast.aurapoints.client.Gestures.B;
import com.toast.aurapoints.client.Gestures.Key;
import com.toast.aurapoints.network.BendingAnimPacket;

import static com.toast.aurapoints.client.Gestures.aim;
import static com.toast.aurapoints.client.Gestures.b;
import static com.toast.aurapoints.client.Gestures.both;
import static com.toast.aurapoints.client.Gestures.brest;
import static com.toast.aurapoints.client.Gestures.k;
import static com.toast.aurapoints.client.Gestures.rest;

/**
 * CLIENT ONLY. The ability moves of the owner-only auras (BendingAnimPacket.GESTURE_EARTH ...), in the same terms as
 * Gestures: {right arm, left arm} keyframes and body keys, each ending at rest.
 */
final class AuraGestures {

	private AuraGestures() {
	}

	static Key[][] arms(int kind, int variant) {
		if (kind == BendingAnimPacket.GESTURE_EARTH) {
			return switch (variant) {
				// Pillar Lift: the right fist drawn back low, then driven straight up as the pillar bursts out
				case 0 -> new Key[][]{
						{rest(0), k(3, 0.5f, 0, 0.25f), k(6, -2.95f, 0.05f, -0.1f), k(12, -2.85f, 0.05f, -0.1f), rest(18)},
						{rest(0), k(3, -0.6f, 0, 0.45f), k(6, 0.25f, 0, 0.3f), rest(18)}};
				// Boulder Toss: from both arms overhead, cocked back an inch, then heaved forward at the aim
				case 1 -> both(k(0, -2.95f, 0, 0.32f), k(2, -3.15f, 0, 0.3f), aim(5, 0.15f, 0, 0.15f), aim(8, 0.3f, 0, 0.15f), rest(14));
				// Quake: both fists swung up high, then hammered down into the ground in front
				case 2 -> both(rest(0), k(4, -3.0f, -0.2f, 0.2f), k(7, -0.55f, -0.2f, 0.12f), k(13, -0.5f, -0.2f, 0.12f), rest(20));
				default -> null;
			};
		}
		if (kind == BendingAnimPacket.GESTURE_LIGHT) {
			return switch (variant) {
				// Radiant Pulse: both hands drawn in to the chest, then flung open wide and up as the light rolls out
				case 0 -> both(rest(0), k(3, -1.3f, -0.85f, 0.1f), k(6, -0.5f, 0.2f, 2.1f), k(12, -0.45f, 0.2f, 2.0f), rest(18));
				// Solar Flash: the right palm thrust at the aim, the left arm thrown up over the eyes
				case 1 -> new Key[][]{
						{rest(0), k(2, -1.3f, -0.35f, 0.2f), aim(4, -0.05f, 0, 0), aim(9, 0, 0, 0), rest(14)},
						{rest(0), k(3, -2.0f, -0.95f, 0.1f), k(9, -2.0f, -0.95f, 0.1f), rest(14)}};
				default -> null;
			};
		}
		if (kind == BendingAnimPacket.GESTURE_BLOOD) {
			return switch (variant) {
				// Hemorrhage: the right hand clawed high and out, then raked down and across through the lunge
				case 0 -> new Key[][]{
						{rest(0), k(2, -2.5f, 0.3f, -0.6f), k(5, -0.6f, -0.9f, 0.1f), k(9, -0.5f, -0.9f, 0.1f), rest(14)},
						{rest(0), k(2, 0.4f, 0, 0.3f), k(5, -0.4f, 0, 0.3f), rest(14)}};
				// Blood Tether: a clawed hand thrust out at the target, held, then dragged back to the chest
				case 1 -> new Key[][]{
						{rest(0), aim(3, -0.05f, 0, 0), aim(8, 0, 0, 0), k(12, -1.2f, -0.7f, 0.1f), k(16, -1.15f, -0.7f, 0.1f), rest(22)},
						{rest(0), k(3, 0.3f, 0, 0.35f), rest(22)}};
				// Crimson Sacrifice: the left hand drawn across the right palm, then the right fist clenched and raised
				case 2 -> new Key[][]{
						{rest(0), k(3, -1.3f, -0.4f, 0.05f), k(7, -1.3f, -0.4f, 0.05f), k(11, -2.6f, 0.1f, -0.2f), k(17, -2.55f, 0.1f, -0.2f), rest(24)},
						{rest(0), k(3, -1.2f, -0.2f, 0.05f), k(5, -1.3f, -0.75f, 0.05f), k(7, -1.0f, -0.9f, 0.05f), rest(14)}};
				default -> null;
			};
		}
		if (kind == BendingAnimPacket.GESTURE_VOID) {
			return switch (variant) {
				// Void Pull: an open hand thrust out at the aim, then clenched and yanked back past the hip
				case 0 -> new Key[][]{
						{rest(0), aim(3, -0.05f, 0, 0), aim(6, 0, 0, 0), k(9, 0.35f, 0.1f, 0.15f), k(13, 0.3f, 0.1f, 0.15f), rest(18)},
						{rest(0), k(3, 0.3f, 0, 0.3f), k(9, -0.5f, 0, 0.25f), rest(18)}};
				// Rift Step: arms crossed in front of the chest, then torn apart and out to the sides
				case 1 -> both(rest(0), k(2, -1.4f, -0.9f, 0.05f), k(5, -0.25f, 0.3f, 1.5f), k(9, -0.2f, 0.25f, 1.4f), rest(14));
				// Null Orb: both hands cupped at the chest round something, then pushed out at the aim
				case 2 -> both(rest(0), k(3, -1.1f, -0.65f, 0.05f), k(6, -1.15f, -0.65f, 0.05f), aim(9, 0, -0.12f, 0), aim(13, 0, -0.1f, 0), rest(18));
				default -> null;
			};
		}
		return null;
	}

	static B[] body(int kind, int variant) {
		if (kind == BendingAnimPacket.GESTURE_EARTH) {
			return switch (variant) {
				case 0 -> new B[]{brest(0), b(3, 0.3f, -0.2f, 0.1f, 0.3f, 0.15f), b(6, -0.15f, 0.15f, 0, 0.2f, 0.15f), b(12, -0.1f, 0.1f, 0, 0.2f, 0.15f), brest(18)};
				case 1 -> new B[]{b(0, -0.08f, 0, 0.05f, 0, 0.32f), b(2, -0.2f, 0, 0.03f, 0, 0.3f), b(5, 0.35f, 0, 0.06f, 0.35f, 0.2f),
						b(8, 0.3f, 0, 0.05f, 0.35f, 0.2f), brest(14)};
				case 2 -> new B[]{brest(0), b(4, -0.15f, 0, 0, 0, 0.3f), b(7, 0.55f, 0, 0.25f, 0, 0.42f), b(13, 0.5f, 0, 0.22f, 0, 0.42f), brest(20)};
				default -> null;
			};
		}
		if (kind == BendingAnimPacket.GESTURE_LIGHT) {
			return switch (variant) {
				case 0 -> new B[]{brest(0), b(3, 0.15f, 0, 0.05f, 0, 0.1f), b(6, -0.2f, 0, 0, 0, 0.25f), b(12, -0.15f, 0, 0, 0, 0.25f), brest(18)};
				case 1 -> new B[]{brest(0), b(2, -0.05f, -0.2f, 0, 0, 0), b(4, 0.15f, 0.2f, 0.02f, 0.3f, 0), b(9, 0.12f, 0.15f, 0.02f, 0.3f, 0), brest(14)};
				default -> null;
			};
		}
		if (kind == BendingAnimPacket.GESTURE_BLOOD) {
			return switch (variant) {
				case 0 -> new B[]{brest(0), b(2, -0.05f, -0.3f, 0, 0, 0), b(5, 0.4f, 0.35f, 0.08f, 0.45f, 0), b(9, 0.35f, 0.3f, 0.06f, 0.45f, 0), brest(14)};
				case 1 -> new B[]{brest(0), b(3, 0.15f, 0.1f, 0.02f, 0.3f, 0), b(8, 0.15f, 0.1f, 0.02f, 0.3f, 0), b(12, -0.1f, -0.1f, 0, 0.2f, 0), brest(22)};
				case 2 -> new B[]{brest(0), b(3, 0.15f, 0, 0.03f, 0, 0.1f), b(7, 0.2f, 0, 0.04f, 0, 0.1f), b(11, -0.15f, 0, 0, 0, 0.25f), b(17, -0.12f, 0, 0, 0, 0.25f), brest(24)};
				default -> null;
			};
		}
		if (kind == BendingAnimPacket.GESTURE_VOID) {
			return switch (variant) {
				case 0 -> new B[]{brest(0), b(3, 0.15f, 0.1f, 0.02f, 0.3f, 0), b(9, -0.15f, -0.2f, 0, 0.2f, 0), brest(18)};
				case 1 -> new B[]{brest(0), b(2, 0.2f, 0, 0.05f, 0, 0.1f), b(5, -0.1f, 0, 0, 0, 0.3f), brest(14)};
				case 2 -> new B[]{brest(0), b(3, -0.05f, 0, 0, 0, 0.1f), b(9, 0.2f, 0, 0.03f, 0.3f, 0), b(13, 0.18f, 0, 0.03f, 0.3f, 0), brest(18)};
				default -> null;
			};
		}
		return null;
	}
}
