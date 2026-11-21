package com.toast.aurapoints.data;

import com.toast.aurapoints.ability.AuraAbilities.Ability;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * One loadout preset of an aura (5.38.0, ability/Loadouts): a name, the abilities on Ability 1/2/3 in the
 * player's order (empty = the aura's own order), and the abilities whose tap and sneak casts are swapped.
 */
public final class LoadoutPreset {

	public String name;
	public final List<Ability> keys = new ArrayList<>();
	public final EnumSet<Ability> castSwap = EnumSet.noneOf(Ability.class);

	public LoadoutPreset(String name) {
		this.name = name;
	}

	public LoadoutPreset copy(String newName) {
		LoadoutPreset p = new LoadoutPreset(newName);
		p.keys.addAll(keys);
		p.castSwap.addAll(castSwap);
		return p;
	}

	CompoundTag write() {
		CompoundTag tag = new CompoundTag();
		tag.putString("name", name);
		ListTag k = new ListTag();
		for (Ability a : keys) k.add(StringTag.valueOf(a.name()));
		tag.put("keys", k);
		ListTag s = new ListTag();
		for (Ability a : castSwap) s.add(StringTag.valueOf(a.name()));
		tag.put("castSwap", s);
		return tag;
	}

	static LoadoutPreset read(CompoundTag tag) {
		LoadoutPreset p = new LoadoutPreset(tag.getString("name"));
		ListTag k = tag.getList("keys", Tag.TAG_STRING);
		for (int i = 0; i < k.size(); i++) {
			Ability a = parse(k.getString(i));
			if (a != null) p.keys.add(a);
		}
		ListTag s = tag.getList("castSwap", Tag.TAG_STRING);
		for (int i = 0; i < s.size(); i++) {
			Ability a = parse(s.getString(i));
			if (a != null) p.castSwap.add(a);
		}
		return p;
	}

	private static Ability parse(String name) {
		try {
			return Ability.valueOf(name);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}
}
