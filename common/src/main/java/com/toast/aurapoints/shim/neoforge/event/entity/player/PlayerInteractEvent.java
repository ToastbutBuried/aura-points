package com.toast.aurapoints.shim.neoforge.event.entity.player;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

public abstract class PlayerInteractEvent extends com.toast.aurapoints.shim.bus.api.Event implements com.toast.aurapoints.shim.bus.api.ICancellableEvent {
	private final Player entity;
	private final InteractionHand hand;
	private final BlockPos pos;

	protected PlayerInteractEvent(Player entity, InteractionHand hand, BlockPos pos) {
		this.entity = entity;
		this.hand = hand;
		this.pos = pos;
	}

	public Player getEntity() {
		return entity;
	}

	public InteractionHand getHand() {
		return hand;
	}

	public BlockPos getPos() {
		return pos;
	}

	public Level getLevel() {
		return entity.level();
	}

	public static class EntityInteract extends PlayerInteractEvent {
		private final Entity target;

		public EntityInteract(Player player, InteractionHand hand, Entity target) {
			super(player, hand, target.blockPosition());
			this.target = target;
		}

		public Entity getTarget() {
			return target;
		}
	}

	public static class RightClickBlock extends PlayerInteractEvent {
		public RightClickBlock(Player player, InteractionHand hand, BlockPos pos) {
			super(player, hand, pos);
		}
	}

	public static class RightClickItem extends PlayerInteractEvent {
		public RightClickItem(Player player, InteractionHand hand) {
			super(player, hand, player.blockPosition());
		}
	}

	/** Client only: a left click at nothing. */
	public static class LeftClickEmpty extends PlayerInteractEvent {
		public LeftClickEmpty(Player player) {
			super(player, InteractionHand.MAIN_HAND, player.blockPosition());
		}
	}

	public static class LeftClickBlock extends PlayerInteractEvent {
		public enum Action {
			START,
			STOP,
			ABORT,
			CLIENT_HOLD
		}

		private final Direction face;
		private final Action action;

		public LeftClickBlock(Player player, BlockPos pos, Direction face, Action action) {
			super(player, InteractionHand.MAIN_HAND, pos);
			this.face = face;
			this.action = action;
		}

		public Direction getFace() {
			return face;
		}

		public Action getAction() {
			return action;
		}
	}
}
