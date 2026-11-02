package com.toast.aurapoints.ability;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

/**
 * An aura's styled name, "❄ Frost ❄": its icon in an accent color, the name in a gradient of the
 * aura's colors. Shared by the label over player names (client/AuraNametag) and /aura info.
 */
public final class AuraLabel {

	private AuraLabel() {
	}

	private record Look(String icon, int from, int to, int iconRgb) {
	}

	private static Look look(AuraType aura) {
		return switch (aura) {
			case SHADOW -> new Look("☾", 0x9B4FE0, 0xD9A6FF, 0xC77DFF);
			case WATER -> new Look("≈", 0x2E9BFF, 0x9FE6FF, 0x5FC8FF);
			case FIRE -> new Look("✹", 0xFF4A12, 0xFFD040, 0xFF8A2A);
			case WIND -> new Look("☁", 0xBFEAFF, 0xFFFFFF, 0xE8F8FF);
			case FROST -> new Look("❄", 0x7FD0FF, 0xF4FBFF, 0xBDEBFF);
			case VERDANT -> new Look("☘", 0x3FA34D, 0xB6F27A, 0x7CD957);
			case THUNDER -> new Look("⚡", 0x5AB8FF, 0xFFF27A, 0xFFE347);
			case RESONANCE -> new Look("♫", 0x5A0C8C, 0xD02CF0, 0xA020D0);
			case EARTH -> new Look("▲", 0x7A5230, 0xD9B27A, 0xB08850);
			case LIGHT -> new Look("☀", 0xFFC24A, 0xFFFBE0, 0xFFD966);
			case BLOOD -> new Look("♥", 0x6A0410, 0xFF3A44, 0xD01828);
			case VOID -> new Look("◉", 0x2A0A4A, 0xA060F0, 0x7A2BD6);
		};
	}

	/** The accent color of an aura (its icon color). */
	public static int accent(AuraType aura) {
		return look(aura).iconRgb();
	}

	/** "❄ Frost ❄" with {@code text} as the name (the aura's own name if null). */
	public static MutableComponent of(AuraType aura, String text) {
		Look l = look(aura);
		String name = text == null ? aura.displayName : text;
		Style icon = Style.EMPTY.withColor(TextColor.fromRgb(l.iconRgb()));
		MutableComponent out = Component.empty();
		out.append(Component.literal(l.icon() + " ").withStyle(icon));
		out.append(gradient(name, l.from(), l.to(), true));
		out.append(Component.literal(" " + l.icon()).withStyle(icon));
		return out;
	}

	public static MutableComponent of(AuraType aura) {
		return of(aura, null);
	}

	/** {@code text}, each letter a step further from {@code from} to {@code to}. */
	public static MutableComponent gradient(String text, int from, int to, boolean bold) {
		MutableComponent out = Component.empty();
		for (int i = 0; i < text.length(); i++) {
			float t = text.length() == 1 ? 0 : i / (float) (text.length() - 1);
			out.append(Component.literal(String.valueOf(text.charAt(i)))
					.withStyle(Style.EMPTY.withColor(TextColor.fromRgb(lerp(from, to, t))).withBold(bold)));
		}
		return out;
	}

	private static int lerp(int a, int b, float t) {
		int r = (int) (((a >> 16) & 0xFF) + (((b >> 16) & 0xFF) - ((a >> 16) & 0xFF)) * t);
		int g = (int) (((a >> 8) & 0xFF) + (((b >> 8) & 0xFF) - ((a >> 8) & 0xFF)) * t);
		int bl = (int) ((a & 0xFF) + ((b & 0xFF) - (a & 0xFF)) * t);
		return (r << 16) | (g << 8) | bl;
	}
}
