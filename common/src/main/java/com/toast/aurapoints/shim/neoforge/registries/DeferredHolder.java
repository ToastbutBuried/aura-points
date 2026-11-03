package com.toast.aurapoints.shim.neoforge.registries;

import com.mojang.datafixers.util.Either;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderOwner;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;

import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Stream;

/** A registry entry by key; resolves (and caches) the registry's own holder on first use, so it works on any loader. */
public class DeferredHolder<R, T extends R> implements Holder<R>, Supplier<T> {
	protected final ResourceKey<R> key;
	private Holder<R> holder;

	protected DeferredHolder(ResourceKey<R> key) {
		this.key = key;
	}

	public static <R, T extends R> DeferredHolder<R, T> create(ResourceKey<R> key) {
		return new DeferredHolder<>(key);
	}

	@SuppressWarnings("unchecked")
	private Holder<R> holder() {
		if (holder == null) {
			Registry<R> registry = (Registry<R>) BuiltInRegistries.REGISTRY.get(key.registry());
			if (registry == null) throw new IllegalStateException("Unknown registry " + key.registry());
			holder = registry.getHolder(key).orElseThrow(() -> new IllegalStateException("Not registered yet: " + key));
		}
		return holder;
	}

	@Override
	@SuppressWarnings("unchecked")
	public T get() {
		return (T) holder().value();
	}

	@Override
	public R value() {
		return holder().value();
	}

	public ResourceLocation getId() {
		return key.location();
	}

	public ResourceKey<R> getKey() {
		return key;
	}

	public Holder<R> getDelegate() {
		return holder();
	}

	@Override
	public boolean isBound() {
		return holder != null || BuiltInRegistries.REGISTRY.get(key.registry()) != null && holder().isBound();
	}

	@Override
	public boolean is(ResourceLocation id) {
		return key.location().equals(id);
	}

	@Override
	public boolean is(ResourceKey<R> other) {
		return key.equals(other);
	}

	@Override
	public boolean is(Predicate<ResourceKey<R>> predicate) {
		return predicate.test(key);
	}

	@Override
	public boolean is(TagKey<R> tag) {
		return holder().is(tag);
	}

	@Override
	public boolean is(Holder<R> other) {
		return holder().is(other);
	}

	@Override
	public Stream<TagKey<R>> tags() {
		return holder().tags();
	}

	@Override
	public Either<ResourceKey<R>, R> unwrap() {
		return Either.left(key);
	}

	@Override
	public Optional<ResourceKey<R>> unwrapKey() {
		return Optional.of(key);
	}

	@Override
	public Kind kind() {
		return Kind.REFERENCE;
	}

	@Override
	public boolean canSerializeIn(HolderOwner<R> owner) {
		return holder().canSerializeIn(owner);
	}

	@Override
	public String getRegisteredName() {
		return key.location().toString();
	}

	@Override
	public boolean equals(Object o) {
		return this == o || o instanceof Holder<?> h && h.kind() == Kind.REFERENCE && h.unwrapKey().map(key::equals).orElse(false);
	}

	@Override
	public int hashCode() {
		return key.hashCode();
	}

	@Override
	public String toString() {
		return "DeferredHolder{" + key + "}";
	}
}
