# Changelog

## 1.1.0
- Chest sync: whoever opens a chest sends its contents (through the client mod) to friends and group members who may open it themselves. The server checks access and distance, keeps the latest state in `sync.json`, and sends it to players who join later. Breaking the chest forgets it.
- New config: `sync.enabled`, `sync.max-entries`.
- Mod channel: new `sync` message in both directions; messages may now be up to 32 766 bytes.

## 1.0.0
- Lock chests, barrels and shulker boxes; share them with players, friends and groups.
