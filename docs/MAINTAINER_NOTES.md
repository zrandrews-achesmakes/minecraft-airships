# Airships maintainer notes

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
- `./gradlew runGameTest` runs the server game tests.
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
- The open screen is `mc.gui.screen()` / `mc.gui.setScreen(...)` (no `Minecraft.screen` field). The gametest
  source set has no LWJGL: look keys up with `InputConstants.getKey("key.keyboard.k")`.
- A rider can never target their own vehicle with the crosshair (`ProjectileUtil.getEntityHitResult` skips the
  root vehicle), so the seated pilot can't right-click the helm. Pilot actions go through keys or `UseItemCallback`.
