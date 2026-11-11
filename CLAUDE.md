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

**Never edit the project board directly** (no `gh project ...`): only change issue labels/state and the
sync Action moves the card within ~30 seconds. Commands (GitHub CLI, logged in with `gh auth login`):
```
gh issue edit 15 --add-label ready                                  # -> Ready
gh issue edit 15 --remove-label ready --add-label "in progress"     # -> In progress
gh issue comment 15 --body "Result: ..."                            # report what happened
gh issue close 15                                                    # -> Done
```
If `gh` says it isn't logged in, ask the owner to run `gh auth login` (it can't be done for them).

## Watching tests
`run-watch-test.bat` (local only, not in git) pulls the latest code and runs
`gradlew runClientGameTest -Pwatch`: a visible game window, third-person camera, each step
announced on screen. Double-click it, or run it yourself (Claude Code on the owner's PC):
`./gradlew runClientGameTest -Pwatch` — the game window opens on the owner's screen so they can watch.
Use a long command timeout (10 minutes) or run it in the background; a run takes about 3 minutes plus
Gradle startup. Afterwards read `build/run/clientGameTest/logs/latest.log` (lines tagged
`[Airships test]`) and the PNGs in `build/run/clientGameTest/screenshots/`.

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
- A rider can never target their own vehicle with the crosshair (`ProjectileUtil.getEntityHitResult` skips the
  root vehicle), so the seated pilot can't right-click the helm. Pilot actions go through keys or `UseItemCallback`.

## Where things stand (2026-11-10)
- All 19 server game tests pass. The client test now has 8 steps: launch, fly+climb, descend, turn right,
  turn left, refuel at the wheel (#15), save & reload mid-flight (#18), land. All 8 pass locally (fast mode).
- Done: #1–#14, #16, #17, #19, #22, #23 (judder fixed and confirmed by the owner).
- **In progress:** #15 and #18 (steps 6 and 7 of the client test), waiting for the owner to watch the
  watched test and confirm before closing. #15 found a bug: the seated pilot couldn't refuel (see gotcha
  above); fixed with a `UseItemCallback`.
- Backlog: #20 survival crafting, #21 multiplayer, features #24–#29, and #30 (automated testing, ongoing).
- Known CI problem: the headless client test job hung on GitHub once (it has a 25-minute timeout now).
  The same test passes locally on Windows, so the issue is specific to the Linux/xvfb runner.

## Tooling notes
- The repo is public, so issues can be read without auth. Board moves happen only through labels
  (the sync Action uses the `PROJECT_TOKEN` secret, a classic token with the `project` scope).
