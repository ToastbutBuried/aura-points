package com.toast.aurapoints.loot;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.toast.aurapoints.AuraPoints;
import com.toast.aurapoints.ability.AuraAbilities;
import com.toast.aurapoints.ability.AuraType;
import com.toast.aurapoints.data.AuraPlayerData;
import com.toast.aurapoints.data.AuraState;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.parameters.LootContextParam;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.minecraft.world.level.storage.loot.predicates.LootItemConditionType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.Optional;
import java.util.Set;

/**
 * Predicate conditions for datapacks, usable anywhere vanilla conditions are
 * (predicate files, loot tables, "execute if predicate"):
 *
 *   {"condition": "aurapoints:has_aura", "aura": "shadow"}
 *   {"condition": "aurapoints:has_aura"}                        - any aura at all
 *   {"condition": "aurapoints:has_ability", "ability": "iron_skin"}
 *
 * Both take an optional "entity" like vanilla's entity_properties: "this"
 * (default - the player running the command), "attacker", "direct_attacker"
 * or "attacking_player". In a boss's loot table use "attacking_player" to
 * check the player who killed it. A non-player entity never matches.
 */
public final class AuraConditions {

	private AuraConditions() {
	}

	public static final DeferredRegister<LootItemConditionType> CONDITIONS =
			DeferredRegister.create(Registries.LOOT_CONDITION_TYPE, AuraPoints.MOD_ID);

	private static final Codec<AuraType> AURA_CODEC = Codec.STRING.comapFlatMap(
			id -> {
				AuraType type = AuraType.byId(id);
				return type != null ? DataResult.success(type) : DataResult.error(() -> "Unknown aura: " + id);
			},
			AuraType::id);

	private static final Codec<AuraAbilities.Ability> ABILITY_CODEC = Codec.STRING.comapFlatMap(
			id -> {
				try {
					return DataResult.success(AuraAbilities.Ability.valueOf(id.toUpperCase(java.util.Locale.ROOT)));
				} catch (IllegalArgumentException e) {
					return DataResult.error(() -> "Unknown ability: " + id);
				}
			},
			ability -> ability.name().toLowerCase(java.util.Locale.ROOT));

	private static final MapCodec<LootContext.EntityTarget> ENTITY_FIELD =
			LootContext.EntityTarget.CODEC.optionalFieldOf("entity", LootContext.EntityTarget.THIS);

	public record HasAura(Optional<AuraType> aura, LootContext.EntityTarget entity) implements LootItemCondition {
		public static final MapCodec<HasAura> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
				AURA_CODEC.optionalFieldOf("aura").forGetter(HasAura::aura),
				ENTITY_FIELD.forGetter(HasAura::entity)
		).apply(i, HasAura::new));

		@Override
		public LootItemConditionType getType() {
			return HAS_AURA.get();
		}

		@Override
		public Set<LootContextParam<?>> getReferencedContextParams() {
			return Set.of(entity.getParam());
		}

		@Override
		public boolean test(LootContext context) {
			AuraPlayerData data = dataOf(context.getParamOrNull(entity.getParam()));
			if (data == null || data.aura == null) return false;
			return aura.isEmpty() || aura.get() == data.aura;
		}
	}

	public record HasAbility(AuraAbilities.Ability ability, LootContext.EntityTarget entity) implements LootItemCondition {
		public static final MapCodec<HasAbility> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
				ABILITY_CODEC.fieldOf("ability").forGetter(HasAbility::ability),
				ENTITY_FIELD.forGetter(HasAbility::entity)
		).apply(i, HasAbility::new));

		@Override
		public LootItemConditionType getType() {
			return HAS_ABILITY.get();
		}

		@Override
		public Set<LootContextParam<?>> getReferencedContextParams() {
			return Set.of(entity.getParam());
		}

		@Override
		public boolean test(LootContext context) {
			AuraPlayerData data = dataOf(context.getParamOrNull(entity.getParam()));
			return data != null && AuraAbilities.hasAbility(data, ability);
		}
	}

	private static AuraPlayerData dataOf(Entity entity) {
		if (!(entity instanceof ServerPlayer player)) return null;
		MinecraftServer server = player.getServer();
		return server == null ? null : AuraState.get(server).getOrCreate(player.getUUID());
	}

	public static final DeferredHolder<LootItemConditionType, LootItemConditionType> HAS_AURA =
			CONDITIONS.register("has_aura", () -> new LootItemConditionType(HasAura.CODEC));

	public static final DeferredHolder<LootItemConditionType, LootItemConditionType> HAS_ABILITY =
			CONDITIONS.register("has_ability", () -> new LootItemConditionType(HasAbility.CODEC));
}
