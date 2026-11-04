package com.toast.aurapoints.arena;

import com.toast.aurapoints.ability.AuraType;
import net.minecraft.util.RandomSource;

import java.util.List;
import java.util.Map;

/**
 * The knockout lines of a duel round, one picked at random from the pool that fits how it ended:
 * %k = the winner, %v = the one knocked out.
 */
final class DuelLines {

	private DuelLines() {
	}

	enum Kind { FIRE, FROST, WATER, WIND, SHADOW, VERDANT, THUNDER, RESONANCE, EARTH, LIGHT, BLOOD, VOID, MELEE, BOW, ENVIRONMENT, SELF, GENERIC }

	private static final Map<Kind, List<String>> LINES = Map.ofEntries(
			Map.entry(Kind.FIRE, List.of(
					"%k toasted %v to death",
					"%v was served well-done by %k",
					"%k turned %v into a campfire story",
					"%v forgot to stop, drop and roll (thanks, %k)",
					"%k roasted %v. Literally.",
					"%v is now extra crispy, courtesy of %k")),
			Map.entry(Kind.FROST, List.of(
					"%k put %v on ice",
					"%v is now a %k-brand popsicle",
					"%k gave %v the cold shoulder. Permanently.",
					"%v chilled a little too hard around %k",
					"%k froze %v mid-thought")),
			Map.entry(Kind.WATER, List.of(
					"%v got a little too hydrated, courtesy of %k",
					"%k washed %v away",
					"%k gave %v a very aggressive bath",
					"%v forgot how to swim around %k",
					"%v was flushed by %k")),
			Map.entry(Kind.WIND, List.of(
					"%v is gone with the wind (%k)",
					"%k blew %v away",
					"%v got yeeted into next week by %k",
					"%k aired out %v",
					"%v caught a breeze. A %k-sized one.")),
			Map.entry(Kind.SHADOW, List.of(
					"%v never saw %k coming. Nobody did.",
					"%k ghosted %v. Forever.",
					"%v got jumped by %k's shadow",
					"%k stabbed %v from the dark",
					"%v was afraid of the dark. Rightly so (%k)")),
			Map.entry(Kind.VERDANT, List.of(
					"%k composted %v",
					"%v became fertilizer for %k's garden",
					"%k planted %v six feet under",
					"%v touched grass. The grass touched back (%k)",
					"%v got weeded out by %k")),
			Map.entry(Kind.THUNDER, List.of(
					"%k gave %v a shocking makeover",
					"%v got thunderstruck by %k",
					"%v is now 100% charged (thanks, %k)",
					"%k sent 1.21 gigawatts through %v",
					"%v's hair will never be the same (%k)")),
			Map.entry(Kind.RESONANCE, List.of(
					"%v was too loud for %k",
					"%k heard %v coming a mile away",
					"%v should have tiptoed past %k",
					"%k turned %v's volume down. To zero.")),
			Map.entry(Kind.EARTH, List.of(
					"%k put %v in the ground",
					"%v got rocked by %k",
					"%k gave %v a rough landing",
					"%v hit rock bottom. %k brought the rock.")),
			Map.entry(Kind.LIGHT, List.of(
					"%k outshone %v",
					"%v saw the light. %k sent it.",
					"%k turned %v's lights out",
					"%v should have brought sunglasses (%k)")),
			Map.entry(Kind.BLOOD, List.of(
					"%k drained %v dry",
					"%v donated a little too much blood to %k",
					"%k took %v's blood type: all of it",
					"%v bled out on %k's watch")),
			Map.entry(Kind.VOID, List.of(
					"%k deleted %v",
					"%v stared into the void. %k stared back.",
					"%k sent %v to the shadow realm. Further, actually.",
					"%v is now nothing, courtesy of %k")),
			Map.entry(Kind.MELEE, List.of(
					"%k punched %v into next week",
					"%v lost a fist fight to %k",
					"%k bonked %v",
					"%v walked straight into %k's knuckles")),
			Map.entry(Kind.BOW, List.of(
					"%k sniped %v",
					"%v caught %k's arrow. With their face.",
					"%k turned %v into a pincushion")),
			Map.entry(Kind.ENVIRONMENT, List.of(
					"%v tripped while fighting %k",
					"%v lost to gravity. %k takes the point anyway",
					"%v found a way to lose without %k even trying")),
			Map.entry(Kind.SELF, List.of(
					"%v defeated themself. Point for %k",
					"%v's own aura turned on them (point for %k)",
					"%v played themself. %k says thanks")),
			Map.entry(Kind.GENERIC, List.of(
					"%k defeated %v",
					"%k sent %v back to the drawing board",
					"%v got outplayed by %k"))
	);

	static Kind ofAura(AuraType aura) {
		if (aura == null) return Kind.GENERIC;
		return switch (aura) {
			case FIRE -> Kind.FIRE;
			case FROST -> Kind.FROST;
			case WATER -> Kind.WATER;
			case WIND -> Kind.WIND;
			case SHADOW -> Kind.SHADOW;
			case VERDANT -> Kind.VERDANT;
			case THUNDER -> Kind.THUNDER;
			case RESONANCE -> Kind.RESONANCE;
			case EARTH -> Kind.EARTH;
			case LIGHT -> Kind.LIGHT;
			case BLOOD -> Kind.BLOOD;
		};
	}

	static String pick(Kind kind, String winner, String loser, RandomSource random) {
		List<String> pool = LINES.getOrDefault(kind, LINES.get(Kind.GENERIC));
		return pool.get(random.nextInt(pool.size())).replace("%k", winner).replace("%v", loser);
	}
}
