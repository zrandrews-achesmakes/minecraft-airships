# Airships (Fabric, Minecraft Java 26.3)

Build a ship out of any blocks, add **Lift Blocks** and a **Ship Helm**, and fly it.

## How to play

1. **Craft**
   - **Lift Block**: iron / phantom membrane / iron, feather / glowstone dust / feather, iron / phantom membrane / iron
   - **Ship Helm**: stick _ stick, stick compass stick, log log log
2. **Build** your ship. Every block touching the Helm (face to face) becomes part of the ship.
   Natural ground (dirt, stone, sand, grass...) is ignored, so you can build right on the ground.
3. **Lift**: each Lift Block carries 48 blocks. A 300-block ship needs 7 Lift Blocks.
4. **Fuel**: right-click the Helm with anything that burns in a furnace (coal, charcoal, blaze rods, lava buckets...).
5. **Launch**: right-click the Helm with an empty hand (or any non-fuel item). You'll take the wheel.

### Controls (while at the wheel)

| Key | Action |
| --- | --- |
| W A S D | Move (relative to where you're looking) |
| Space | Climb |
| X | Descend |
| Z / C | Turn 90° left / right (ship must be stopped) |
| G | Land (blocks go back into the world) |
| Shift | Leave the wheel (ship hovers in place) |

Right-click the Helm of a flying ship to refuel it or to take the wheel again.
Out of fuel? You can still descend and land.

All keys can be changed in Options → Controls → Airships.

## Settings

`config/airships.json` (created on first launch). Restart after editing.

| Setting | Default | Meaning |
| --- | --- | --- |
| `maxShipBlocks` | 2048 | Biggest ship allowed (same as Create's `maxBlocksMoved`) |
| `liftPerLiftBlock` | 48 | Blocks each Lift Block can carry |
| `horizontalSpeed` | 0.35 | Blocks per tick (x20 for blocks/second) |
| `verticalSpeed` | 0.2 | Blocks per tick |
| `fuelPerMovingTick` | 1.0 | Fuel burned per tick while moving (coal = 1600) |
| `fuelPerBlockPerMovingTick` | 0.0039 | Extra fuel per block per tick while moving |
| `maxFuel` | 102400 | Fuel capacity (64 coal) |
| `ignoreTerrainBlocks` | true | Don't grab natural ground when launching |

## Building

Needs **JDK 25**.

```
gradlew build        # mod jar ends up in build/libs/airships-0.1.0.jar
gradlew runClient    # launch a test game with the mod
```

## Known limits (v0.1)

- Ships stay level and turn in 90° steps (no tilting or physics).
- Chests, signs, beds and other block-entity models are invisible while flying (contents are kept and come back on landing).
- You can't open chests or use blocks while the ship is flying.
- Ships pass through each other.
