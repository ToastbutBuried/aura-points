package com.toast.aurapoints.shim.neoforge.registries;

import com.toast.aurapoints.shim.Platform;
import com.toast.aurapoints.shim.bus.api.IEventBus;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Entries to register in one vanilla registry. register(bus) hands them to the loader (Platform.register), which
 * creates and registers each one at the right time; the holders then look their value up in the registry.
 */
public class DeferredRegister<T> {
	public record Entry<T>(ResourceLocation id, Supplier<? extends T> factory) {
	}

	private final ResourceKey<? extends Registry<T>> registryKey;
	private final String namespace;
	private final List<Entry<T>> entries = new ArrayList<>();
	private final List<DeferredHolder<T, ? extends T>> holders = new ArrayList<>();

	protected DeferredRegister(ResourceKey<? extends Registry<T>> registryKey, String namespace) {
		this.registryKey = registryKey;
		this.namespace = namespace;
	}

	public static <T> DeferredRegister<T> create(ResourceKey<? extends Registry<T>> registryKey, String namespace) {
		return new DeferredRegister<>(registryKey, namespace);
	}

	public static Items createItems(String namespace) {
		return new Items(namespace);
	}

	public static Blocks createBlocks(String namespace) {
		return new Blocks(namespace);
	}

	public <I extends T> DeferredHolder<T, I> register(String name, Supplier<? extends I> factory) {
		ResourceLocation id = ResourceLocation.fromNamespaceAndPath(namespace, name);
		entries.add(new Entry<>(id, factory));
		DeferredHolder<T, I> holder = createHolder(ResourceKey.create(registryKey, id));
		holders.add(holder);
		return holder;
	}

	public <I extends T> DeferredHolder<T, I> register(String name, Function<ResourceLocation, ? extends I> factory) {
		ResourceLocation id = ResourceLocation.fromNamespaceAndPath(namespace, name);
		return register(name, () -> factory.apply(id));
	}

	protected <I extends T> DeferredHolder<T, I> createHolder(ResourceKey<T> key) {
		return new DeferredHolder<>(key);
	}

	public void register(IEventBus bus) {
		Platform.get().register(this);
	}

	public ResourceKey<? extends Registry<T>> getRegistryKey() {
		return registryKey;
	}

	public String getNamespace() {
		return namespace;
	}

	/** For the loader layer: every entry, in registration order. */
	public List<Entry<T>> entries() {
		return Collections.unmodifiableList(entries);
	}

	public List<DeferredHolder<T, ? extends T>> getEntries() {
		return Collections.unmodifiableList(holders);
	}

	public static class Items extends DeferredRegister<Item> {
		protected Items(String namespace) {
			super(Registries.ITEM, namespace);
		}

		@Override
		@SuppressWarnings("unchecked")
		protected <I extends Item> DeferredHolder<Item, I> createHolder(ResourceKey<Item> key) {
			return (DeferredHolder<Item, I>) new DeferredItem<>(key);
		}

		@Override
		public <I extends Item> DeferredItem<I> register(String name, Supplier<? extends I> factory) {
			return (DeferredItem<I>) super.register(name, factory);
		}

		public <I extends Item> DeferredItem<I> registerItem(String name, Function<Item.Properties, ? extends I> func, Item.Properties props) {
			return register(name, () -> func.apply(props));
		}

		public <I extends Item> DeferredItem<I> registerItem(String name, Function<Item.Properties, ? extends I> func) {
			return registerItem(name, func, new Item.Properties());
		}

		public DeferredItem<Item> registerSimpleItem(String name, Item.Properties props) {
			return registerItem(name, Item::new, props);
		}

		public DeferredItem<Item> registerSimpleItem(String name) {
			return registerItem(name, Item::new, new Item.Properties());
		}
	}

	public static class Blocks extends DeferredRegister<Block> {
		protected Blocks(String namespace) {
			super(Registries.BLOCK, namespace);
		}

		@Override
		@SuppressWarnings("unchecked")
		protected <I extends Block> DeferredHolder<Block, I> createHolder(ResourceKey<Block> key) {
			return (DeferredHolder<Block, I>) new DeferredBlock<>(key);
		}

		@Override
		public <B extends Block> DeferredBlock<B> register(String name, Supplier<? extends B> factory) {
			return (DeferredBlock<B>) super.register(name, factory);
		}

		public <B extends Block> DeferredBlock<B> registerBlock(String name, Function<BlockBehaviour.Properties, ? extends B> func, BlockBehaviour.Properties props) {
			return register(name, () -> func.apply(props));
		}

		public DeferredBlock<Block> registerSimpleBlock(String name, BlockBehaviour.Properties props) {
			return registerBlock(name, Block::new, props);
		}
	}
}
