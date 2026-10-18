# Aura Points

Releases of the Aura Points mod (NeoForge 1.21.1) for our server, and a small updater that keeps
everyone's copy current. `latest.json` always names the newest release.

## Auto-update setup (once per player, Prism Launcher)

1. Download [`aura-updater.jar`](https://github.com/ToastbutBuried/aura-points/raw/main/aura-updater.jar).
2. In Prism, right-click the instance → **Folder**, and put `aura-updater.jar` in that folder
   (the instance folder itself, next to `instance.cfg` - not in `minecraft/` or `mods/`).
3. Right-click the instance → **Edit** → **Settings** → **Custom commands**, tick the box, and paste
   this into **Pre-launch command**:

   ```
   "$INST_JAVA" -jar "$INST_DIR/aura-updater.jar" "$INST_MC_DIR/mods"
   ```

That's it. On every launch it checks this repo; if there is a newer Aura Points it downloads it,
verifies it, and replaces the old jar before the game starts. Without internet it just starts the
game with the jar you already have. Its messages show up at the top of Prism's log as `[aura-updater]`.
