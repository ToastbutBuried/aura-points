package com.toast.aurapoints.shim.neoforge.event.entity.player;

// Generated shim event (genevents.py): fields in, results read back by the loader layer.
public abstract class PlayerEvent extends com.toast.aurapoints.shim.bus.api.Event {

	public static class NameFormat extends PlayerEvent {
		private net.minecraft.world.entity.player.Player entity;
		private net.minecraft.network.chat.Component username;
		private net.minecraft.network.chat.Component displayname;

		public NameFormat(net.minecraft.world.entity.player.Player entity, net.minecraft.network.chat.Component username, net.minecraft.network.chat.Component displayname) {
			this.entity = entity;
			this.username = username;
			this.displayname = displayname;
		}

		public net.minecraft.world.entity.player.Player getEntity() {
			return entity;
		}

		public net.minecraft.network.chat.Component getUsername() {
			return username;
		}

		public net.minecraft.network.chat.Component getDisplayname() {
			return displayname;
		}

		public void setDisplayname(net.minecraft.network.chat.Component value) {
			this.displayname = value;
		}
	}

	public static class TabListNameFormat extends PlayerEvent {
		private net.minecraft.world.entity.player.Player entity;
		private net.minecraft.network.chat.Component displayName;

		public TabListNameFormat(net.minecraft.world.entity.player.Player entity, net.minecraft.network.chat.Component displayName) {
			this.entity = entity;
			this.displayName = displayName;
		}

		public net.minecraft.world.entity.player.Player getEntity() {
			return entity;
		}

		public net.minecraft.network.chat.Component getDisplayName() {
			return displayName;
		}

		public void setDisplayName(net.minecraft.network.chat.Component value) {
			this.displayName = value;
		}
	}

	public static class PlayerChangedDimensionEvent extends PlayerEvent {
		private net.minecraft.world.entity.player.Player entity;
		private net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> from;
		private net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> to;

		public PlayerChangedDimensionEvent(net.minecraft.world.entity.player.Player entity, net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> from, net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> to) {
			this.entity = entity;
			this.from = from;
			this.to = to;
		}

		public net.minecraft.world.entity.player.Player getEntity() {
			return entity;
		}

		public net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> getFrom() {
			return from;
		}

		public net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> getTo() {
			return to;
		}
	}

	public static class PlayerLoggedInEvent extends PlayerEvent {
		private net.minecraft.world.entity.player.Player entity;

		public PlayerLoggedInEvent(net.minecraft.world.entity.player.Player entity) {
			this.entity = entity;
		}

		public net.minecraft.world.entity.player.Player getEntity() {
			return entity;
		}
	}

	public static class PlayerLoggedOutEvent extends PlayerEvent {
		private net.minecraft.world.entity.player.Player entity;

		public PlayerLoggedOutEvent(net.minecraft.world.entity.player.Player entity) {
			this.entity = entity;
		}

		public net.minecraft.world.entity.player.Player getEntity() {
			return entity;
		}
	}

	public static class PlayerRespawnEvent extends PlayerEvent {
		private net.minecraft.world.entity.player.Player entity;
		private boolean endConquered;

		public PlayerRespawnEvent(net.minecraft.world.entity.player.Player entity, boolean endConquered) {
			this.entity = entity;
			this.endConquered = endConquered;
		}

		public net.minecraft.world.entity.player.Player getEntity() {
			return entity;
		}

		public boolean isEndConquered() {
			return endConquered;
		}
	}

	public static class StartTracking extends PlayerEvent {
		private net.minecraft.world.entity.player.Player entity;
		private net.minecraft.world.entity.Entity target;

		public StartTracking(net.minecraft.world.entity.player.Player entity, net.minecraft.world.entity.Entity target) {
			this.entity = entity;
			this.target = target;
		}

		public net.minecraft.world.entity.player.Player getEntity() {
			return entity;
		}

		public net.minecraft.world.entity.Entity getTarget() {
			return target;
		}
	}
}
