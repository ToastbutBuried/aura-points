package com.toast.aurapoints.shim.neoforge.event.level;

// Generated shim event (genevents.py): fields in, results read back by the loader layer.
public abstract class BlockEvent extends com.toast.aurapoints.shim.bus.api.Event {

	public static class BreakEvent extends BlockEvent implements com.toast.aurapoints.shim.bus.api.ICancellableEvent {
		private net.minecraft.world.level.LevelAccessor level;
		private net.minecraft.core.BlockPos pos;
		private net.minecraft.world.level.block.state.BlockState state;
		private net.minecraft.world.entity.player.Player player;

		public BreakEvent(net.minecraft.world.level.LevelAccessor level, net.minecraft.core.BlockPos pos, net.minecraft.world.level.block.state.BlockState state, net.minecraft.world.entity.player.Player player) {
			this.level = level;
			this.pos = pos;
			this.state = state;
			this.player = player;
		}

		public net.minecraft.world.level.LevelAccessor getLevel() {
			return level;
		}

		public net.minecraft.core.BlockPos getPos() {
			return pos;
		}

		public net.minecraft.world.level.block.state.BlockState getState() {
			return state;
		}

		public net.minecraft.world.entity.player.Player getPlayer() {
			return player;
		}
	}

	public static class EntityPlaceEvent extends BlockEvent implements com.toast.aurapoints.shim.bus.api.ICancellableEvent {
		private net.minecraft.world.level.LevelAccessor level;
		private net.minecraft.core.BlockPos pos;
		private net.minecraft.world.level.block.state.BlockState placedBlock;
		private net.minecraft.world.entity.Entity entity;

		public EntityPlaceEvent(net.minecraft.world.level.LevelAccessor level, net.minecraft.core.BlockPos pos, net.minecraft.world.level.block.state.BlockState placedBlock, net.minecraft.world.entity.Entity entity) {
			this.level = level;
			this.pos = pos;
			this.placedBlock = placedBlock;
			this.entity = entity;
		}

		public net.minecraft.world.level.LevelAccessor getLevel() {
			return level;
		}

		public net.minecraft.core.BlockPos getPos() {
			return pos;
		}

		public net.minecraft.world.level.block.state.BlockState getPlacedBlock() {
			return placedBlock;
		}

		public net.minecraft.world.entity.Entity getEntity() {
			return entity;
		}
	}
}
