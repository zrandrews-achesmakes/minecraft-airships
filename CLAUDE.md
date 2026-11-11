# Airships mod — notes for Claude

Fabric mod for **Minecraft Java 26.3** (Fabric Loader 0.20.1, Fabric API 0.165.2+26.3, Loom 1.19, **JDK 25**).
Owner: zrandrews-achesmakes. Issues on GitHub are the task tracker, and the owner watches the
project board: https://github.com/users/zrandrews-achesmakes/projects/4/views/1

## Issue workflow (the owner's rule — always follow it)
Board columns are driven by labels via `.github/workflows/project-board.yml`:
1. Every test issue starts in **Backlog** (no label).
2. When you pick the issues to work on next, add the **`ready`** label → card moves to **Ready**.
3. When you start testing/working one, swap `ready` for **`in progress`** → **In progress**.
4. When it's done, comment the result and **close** it → **Done**.
Do the label change *before* starting the work so the board shows what Claude is doing.

## Watching tests
`run-watch-test.bat` (local only, not in git) pulls the latest code and runs
`gradlew runClientGameTest -Pwatch`: a visible game window, third-person camera, each step
announced on screen. Double-click it, or Claude can start it through computer use.

## Layout
- `src/main/java/.../ship/` — `ShipAssembler` (blocks → ship, returns a `Result`), `ShipEntity`
  (movement, fuel, `turn()`, `land()`, test `setAutopilot()`), `ShipCollision` + `mixin/EntityCollisionMixin`
  (lets entities stand on ship blocks), `ShipTracker`, `FuelHelper`.
- `src/client/java/...` — `ShipRenderer` (draws blocks via `MovingBlockRenderState`), `AirshipsClient`
  (keys: X descend, Z/C turn, G land; sends pilot input packets; carries the local player on deck).
- `src/gametest/` — automated tests (see below).
- Config: `config/airships.json` (`AirshipsConfig`).

## Build & test
- `./gradlew build` — compile + jar.
- `./gradlew runGameTest` — 19 headless server game tests (`AirshipsGameTest`). All passing.
- `./gradlew runClientGameTest` — real client driven by code (`AirshipsClientGameTest`); on Linux CI it
  runs under `xvfb-run`. Screenshots land in a `screenshots/` folder.
- CI: `.github/workflows/build.yml` runs both on every push and uploads the jar + screenshots.

## 26.3 API gotchas found so far
- Blocks no longer have `codec()`/`simpleCodec`.
- Fuel: `DataComponents.COOKING_FUEL` + `ResolvableInt.getFromItem(...)` (no `level.fuelValues()`).
- `#minecraft:dirt` no longer includes grass; use `BlockTags.SUBSTRATE_OVERWORLD`.
- Entity types are on `EntityTypes`, not `EntityType`. `Entity.getBoundingBoxForCulling()` is gone
  (override `EntityRenderer.getBoundingBoxForCulling(T, float)`). `InputConstants.Type.KEYBOARD`.
- Smooth client movement: override `createInterpolationHandler()` → `LinearInterpolationHandler.create(this, 3)`.

## Where things stand
See issue #30 for the automated-testing status and the open issues for manual tests still to do.
