# Auto Dodge Mode

Auto Dodge Mode is a server-side Forge mod prototype for Minecraft 1.20.1 that intercepts incoming player damage and either negates it outright or teleports the player to a nearby safe position while preserving their momentum.

## Implemented behavior

- Hooks player attack handling on the Forge server event bus and cancels incoming damage before vanilla health loss is applied.
- Tracks each player's last known safe standing location.
- Plays the Enderman teleport sound and restores velocity after a dodge teleport.
- Handles special-case dodge resolution for:
  - projectiles
  - melee attackers
  - explosions and fire
  - drowning
  - fatal falls
- Searches for weighted safe terrain in an 80×80 area, preferring grass, then stone, sand, and bedrock, while rejecting floating terrain with less than 10 solid blocks underneath.
- Provides gamerules:
  - `autoDodgeHostOnly`
  - `autoDodgeOperatorsOnly`
  - `autoDodgeTeleport`

## Important compatibility note

A truly universal single binary for **all** Forge Minecraft versions is not realistic because Forge loader APIs, mappings, registries, and damage hooks change between major versions. This repository therefore ships a clean 1.20.1 Forge implementation with the logic isolated in `AutoDodgeSystem` so it can be ported forward or backward more easily.
