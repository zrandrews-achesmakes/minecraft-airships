package io.github.achesmakes.airships.test;

import io.github.achesmakes.airships.ship.ShipEntity;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.world.phys.Vec3;

public class M09TwoPlayerTest implements FabricClientGameTest {
    private static final String PILOT = "M09Pilot";
    private static final String RIDER = "M09Rider";

    @Override
    public void runTest(ClientGameTestContext context) {
        String name = System.getProperty("airships.m09.name");
        if (name == null) return;
        String host = "127.0.0.1:" + Integer.getInteger("airships.m09.port");
        Path signals = Path.of(System.getProperty("airships.m09.signals"));
        boolean pilot = PILOT.equals(name);
        check(pilot || RIDER.equals(name), "Unexpected player name " + name);
        context.runOnClient(
                mc ->
                        ConnectScreen.startConnecting(
                                new TitleScreen(),
                                mc,
                                ServerAddress.parseString(host),
                                new ServerData(
                                        "M09 disposable Fabric server",
                                        host,
                                        ServerData.Type.OTHER),
                                false,
                                null));
        context.waitFor(
                mc ->
                        mc.player != null
                                && name.equals(mc.player.getGameProfile().name())
                                && mc.level != null,
                1800);
        context.waitFor(
                mc -> {
                    ShipEntity ship = ship(mc);
                    if (ship == null || ship.getBlocks().size() != 26) return false;
                    boolean seesPeer =
                            mc.level.players().stream()
                                    .anyMatch(
                                            p ->
                                                    (pilot ? RIDER : PILOT)
                                                            .equals(p.getGameProfile().name()));
                    if (!seesPeer) return false;
                    if (pilot) return mc.player.getVehicle() == ship;
                    return mc.player.getVehicle() == null
                            && ship.isStandingOn(mc.player.getBoundingBox(), ship.position())
                            && mc.player.getX() - ship.getX() > 0.7;
                },
                1800);
        context.runOnClient(mc -> mc.options.setCameraType(CameraType.THIRD_PERSON_BACK));
        context.takeScreenshot("m09-" + name + "-before");
        signal(signals.resolve(name + ".ready"));
        if (pilot) {
            fly(context, signals);
        } else {
            observe(context, signals);
        }
        context.takeScreenshot("m09-" + name + "-after");
        signal(signals.resolve(name + ".passed"));
        context.waitFor(mc -> Files.exists(signals.resolve("both.finished")));
    }

    private static void fly(ClientGameTestContext context, Path signals) {
        context.waitFor(mc -> Files.exists(signals.resolve("rider.observing")));
        Sample start = context.computeOnClient(M09TwoPlayerTest::sample);
        check(start.mounted, "The pilot did not remain at the helm");
        context.getInput().holdKeyFor(options -> options.keyUp, 65);
        Sample end = context.computeOnClient(M09TwoPlayerTest::sample);
        check(end.mounted, "The pilot lost the helm");
        check(end.ship.z - start.ship.z > 8.0, "Pilot input did not move the ship downrange");
        System.out.printf("M09_PILOT_FLIGHT_OK ship_distance=%.2f%n", end.ship.z - start.ship.z);
    }

    private static void observe(ClientGameTestContext context, Path signals) {
        context.waitFor(mc -> Files.exists(signals.resolve(PILOT + ".ready")));
        Sample before = context.computeOnClient(M09TwoPlayerTest::sample);
        Sample previous = before;
        boolean captured = false;
        int forwardSamples = 0;
        signal(signals.resolve("rider.observing"));
        for (int sampleNumber = 0; sampleNumber < 100; sampleNumber++) {
            Sample current = context.computeOnClient(M09TwoPlayerTest::sample);
            check(!current.mounted && current.onDeck, "The rider fell or mounted the ship");
            Vec3 relative = current.player.subtract(current.ship);
            Vec3 initial = before.player.subtract(before.ship);
            check(relative.distanceTo(initial) < 1.6, "The rider drifted relative to the deck");
            double change = current.ship.z - previous.ship.z;
            check(change >= -0.4 && change < 3.0, "The observed ship position jumped");
            if (change > 0.1) forwardSamples++;
            previous = current;
            if (!captured && current.ship.z - before.ship.z > 4.0) {
                context.takeScreenshot("m09-rider-in-flight");
                captured = true;
            }
            if (forwardSamples >= 8 && current.ship.z - before.ship.z > 8.0) break;
            context.waitTicks(1);
        }
        check(
                forwardSamples >= 8 && previous.ship.z - before.ship.z > 8.0,
                "Rider did not observe consistent forward movement");
        double riderTravel = previous.player.z - before.player.z;
        check(riderTravel > 7.0, "The ship moved without carrying the rider");
        System.out.printf(
                "M09_RIDER_FLIGHT_OK ship_distance=%.2f rider_distance=%.2f forward_samples=%d%n",
                previous.ship.z - before.ship.z, riderTravel, forwardSamples);
    }

    private static ShipEntity ship(Minecraft mc) {
        if (mc.level == null) return null;
        for (var entity : mc.level.entitiesForRendering()) {
            if (entity instanceof ShipEntity found) return found;
        }
        return null;
    }

    private static Sample sample(Minecraft mc) {
        ShipEntity ship = ship(mc);
        if (ship == null || mc.player == null) throw new AssertionError("Missing player or ship");
        return new Sample(
                ship.position(),
                mc.player.position(),
                mc.player.getVehicle() == ship,
                ship.isStandingOn(mc.player.getBoundingBox(), ship.position()));
    }

    private static void signal(Path stamp) {
        try {
            Files.createFile(stamp);
        } catch (IOException error) {
            throw new IllegalStateException(
                    "Cannot create fixture signal " + stamp.getFileName(), error);
        }
    }

    private static void check(boolean trueCondition, String message) {
        if (!trueCondition) throw new AssertionError(message);
    }

    private record Sample(Vec3 ship, Vec3 player, boolean mounted, boolean onDeck) {}
}

