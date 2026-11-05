# Changelog

## 1.2.0
- No more chest protection: everyone can open and break every chest, like in vanilla. Old locks become owners.
- The server reads chest contents itself and sends them to the client mod's search, without anyone having to open the chest: to the owner, their group and the players they trust (`/truhe vertrauen`).
- Owner: whoever places a chest, barrel or shulker box. Containers from before the install belong to the first player who opens them (`claim-on-first-open`).
- Contents are re-read when players, hoppers, explosions or breaking change them. The index survives restarts (`containers.json.gz`).
- Replaces the 1.1.0 sync, where the mod of whoever opened a chest sent its contents to friends. `sync.json` and the `sync.*` config are no longer used.
- Mod channel version 2: full sync on join, live updates and removals; messages over 60 000 characters are split into fragments. Mods with version 1 get no sync.
- Language files with the old lock texts are backed up as `<language>.yml.old` and replaced.
- Needs mod 1.23 from branch `truhenschutz`.

## 1.1.0
- Chest sync: whoever opens a chest sends its contents (through the client mod) to friends and group members who may open it themselves. The server checks access and distance, keeps the latest state in `sync.json`, and sends it to players who join later. Breaking the chest forgets it.
- New config: `sync.enabled`, `sync.max-entries`.
- Mod channel: new `sync` message in both directions; messages may now be up to 32 766 bytes.

## 1.0.0
- Lock chests, barrels and shulker boxes; share them with players, friends and groups.
