package io.github.zrandrews.airships.ship;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.world.level.Level;

/**
 * Keeps a list of the ships alive in each level, so collision checks can find
 * every ship whose blocks might be near an entity (the ship entity's own hitbox
 * only covers the Helm, so normal entity searches would miss most of the ship).
 */
public final class ShipTracker {
	private static final Map<Level, Set<ShipEntity>> SHIPS = new ConcurrentHashMap<>();

	private ShipTracker() {
	}

	static void track(ShipEntity ship) {
		SHIPS.computeIfAbsent(ship.level(), l -> Collections.newSetFromMap(new ConcurrentHashMap<>())).add(ship);
	}

	public static List<ShipEntity> ships(Level level) {
		Set<ShipEntity> set = SHIPS.get(level);
		if (set == null || set.isEmpty()) return List.of();

		List<ShipEntity> result = new ArrayList<>(set.size());
		for (ShipEntity ship : set) {
			if (ship.isRemoved() || ship.level() != level) {
				set.remove(ship);
			} else {
				result.add(ship);
			}
		}

		if (set.isEmpty()) SHIPS.remove(level);
		return result;
	}
}
