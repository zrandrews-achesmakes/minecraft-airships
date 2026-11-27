import hashlib
import json
import os
import re
import shutil
import signal
import socket
import subprocess
import sys
import time
import uuid
from pathlib import Path

from m09_world_reload import LAUNCHER, SHIP, Server, download, stage


PLAYERS = ("M09Pilot", "M09Rider")


def wait_for(predicate, message, clients, timeout):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if predicate():
            return
        for name, child in clients.items():
            if child.poll() is not None:
                raise AssertionError(f"{name} exited early with status {child.returncode}")
        time.sleep(1)
    raise AssertionError(message)


def start_client(repo, name, port, signals, output):
    runtime = output / (name + "-xdg")
    runtime.mkdir(mode=0o700)
    env = {
        **os.environ,
        "XDG_RUNTIME_DIR": str(runtime),
        "M09_PLAYER": name,
        "M09_PORT": str(port),
        "M09_SIGNALS": str(signals),
        "ALSOFT_DRIVERS": "null",
        "LP_NUM_THREADS": "3",
    }
    with (output / f"{name}.log").open("xb") as log:
        return subprocess.Popen(
            ["xvfb-run", "-a", "-s", "-screen 0 1280x720x24 +extension GLX +render -noreset",
             "bash", "./gradlew", "runClientGameTest", "--no-configuration-cache", "--max-workers=2"],
            cwd=repo, env=env, stdout=log, stderr=subprocess.STDOUT, start_new_session=True,
        )


def prepare_project(checkout):
    command = ["bash", "./gradlew", "configureClientLaunch", "gametestClasses",
               "--no-configuration-cache", "--max-workers=2"]
    for delay in (25, 50, 0):
        result = subprocess.run(command, cwd=checkout, timeout=240, text=True,
                                stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        print(result.stdout, flush=True)
        if result.returncode == 0:
            return
        transient = "429" in result.stdout or "Unexpected file length" in result.stdout
        if not transient or not delay:
            raise subprocess.CalledProcessError(result.returncode, command)
        print(f"Waiting {delay}s before retrying dependency download", flush=True)
        time.sleep(delay)


def make_ship(server):
    blocks = []
    for x in range(-2, 3):
        for z in range(-2, 3):
            name = "airships:helm" if x == z == 0 else "minecraft:oak_planks"
            blocks.append(f'{{pos:[I;{x},0,{z}],state:"{name}"}}')
    blocks.append('{pos:[I;0,-1,0],state:"airships:lift_block"}')
    ship = '{Tags:["m09_ship"],fuel:100000,ship_blocks:[' + ",".join(blocks) + ']}'
    server.require("forceload add 0 0 48 48", "Marked")
    server.check("if loaded 4 85 4", "flight_chunk_ready", 25)
    server.require(f"summon airships:ship 4.5 85 4.5 {ship}", "Summoned new")
    server.check(f"if data entity {SHIP} ship_blocks[25]", "flight_fixture_ship", 10)


def z_position(server, selector):
    for line in server.command(f"data get entity {selector} Pos[2]"):
        value = re.search(r"entity data: (-?[\d.]+(?:[eE][+-]?\d+)?)d$", line)
        if value:
            return float(value[1])
    raise AssertionError(f"Cannot read position for {selector}")


def main():
    inputs, fixture = map(lambda p: Path(p).resolve(), sys.argv[1:])
    root = Path.cwd()
    fixture.mkdir()
    output = fixture / "evidence"
    output.mkdir()
    logs = output / "clients"
    logs.mkdir()
    signals = fixture / "signals"
    signals.mkdir()
    stage(inputs, {"airships"}, "0.166.2+26.3", fixture / "mods")
    download(LAUNCHER, fixture / "fabric.jar")
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        port = s.getsockname()[1]
    (fixture / "eula.txt").write_text("eula=true\n", encoding="ascii")
    (fixture / "server.properties").write_text(
        "server-ip=127.0.0.1\n"
        f"server-port={port}\n"
        "level-name=m09-flight\nlevel-type=minecraft:flat\nlevel-seed=976723\n"
        "online-mode=false\nenforce-secure-profile=false\nmax-players=2\nwhite-list=true\n"
        "enforce-whitelist=true\nenable-query=false\nenable-rcon=false\n"
        "view-distance=5\nsimulation-distance=4\nspawn-protection=0\n"
        "gamemode=creative\nforce-gamemode=true\ngenerate-structures=false\n",
        encoding="ascii",
    )
    whitelist = [
        {"name": name, "uuid": str(uuid.UUID(bytes=hashlib.md5(
            f"OfflinePlayer:{name}".encode(), usedforsecurity=False).digest(), version=3))}
        for name in PLAYERS
    ]
    (fixture / "whitelist.json").write_text(json.dumps(whitelist), encoding="ascii")
    repos = {}
    for name in PLAYERS:
        dest = fixture / name
        subprocess.run(["git", "worktree", "add", "--detach", str(dest), "HEAD"], cwd=root, check=True)
        repos[name] = dest
        # Loom shares downloads across the two worktrees. Populate them before either client starts.
        prepare_project(dest)
    server = Server(fixture, output / "server.log")
    clients = {}
    try:
        server.wait(r'Done \(.+\)! For help', 150)
        server.command("gamerule minecraft:do_mob_spawning false")
        make_ship(server)
        for name in PLAYERS:
            clients[name] = start_client(repos[name], name, port, signals, logs)
        wait_for(lambda: any("M09Pilot" in line and "M09Rider" in line for line in server.command("list")),
                 "The two Fabric clients did not join the fixture simultaneously", clients, 360)
        print("PASS simultaneous Fabric connections: " + ", ".join(PLAYERS), flush=True)
        server.require("tp M09Pilot 4.5 86 4.5 0 0", "Teleported")
        server.require(f"ride M09Pilot mount {SHIP}", "riding")
        server.require("tp M09Rider 5.5 86 4.5 0 18", "Teleported")
        wait_for(lambda: all((signals / f"{p}.passed").is_file() for p in PLAYERS),
                 "Two-player flight assertions did not finish", clients, 120)
        ship_z, pilot_z, rider_z = (z_position(server, target) for target in (SHIP, "M09Pilot", "M09Rider"))
        if ship_z < 12.5 or rider_z < 11.5 or abs(ship_z - rider_z) > 2.5 or abs(ship_z - pilot_z) > 2.5:
            raise AssertionError(f"Authoritative positions disagree: ship={ship_z}, pilot={pilot_z}, rider={rider_z}")
        (signals / "both.finished").touch()
        for name, child in clients.items():
            if child.wait(timeout=50):
                raise AssertionError(f"{name} did not exit successfully")
            evidence = (logs / f"{name}.log").read_text(encoding="utf-8", errors="replace")
            marker = "M09_PILOT_FLIGHT_OK" if name == PLAYERS[0] else "M09_RIDER_FLIGHT_OK"
            if marker not in evidence:
                raise AssertionError(f"Missing {marker}")
            for line in evidence.splitlines():
                if marker in line:
                    print(line, flush=True)
        server.stop()
        print(f"M09_AIRSHIPS_TWO_PLAYER_OK ship_z={ship_z:.2f} pilot_z={pilot_z:.2f} rider_z={rider_z:.2f}", flush=True)
    finally:
        for name, child in clients.items():
            if child.poll() is None:
                os.killpg(child.pid, signal.SIGTERM)
                try:
                    child.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    os.killpg(child.pid, signal.SIGKILL)
                    child.wait(timeout=10)
            for path in repos[name].rglob("m09-*.png"):
                shutil.copyfile(path, output / f"{name}-{path.name}")
            log = logs / f"{name}.log"
            if log.exists():
                lines = log.read_text(encoding="utf-8", errors="replace").splitlines()
                diagnostics = [x for x in lines if re.search(
                    r"M09_|Connecting to|Disconnect|AssertionError|M09TwoPlayerTest.java|Timed out", x)]
                print(f"\n{name} client diagnostics:\n" + "\n".join(
                    diagnostics[-35:] + lines[-18:]), flush=True)
        server.close()
        log = output / "server.log"
        if log.exists():
            lines = log.read_text(encoding="utf-8", errors="replace").splitlines()
            print("\nServer log tail:\n" + "\n".join(
                [x for x in lines if "M09_BARRIER_" not in x and "players online" not in x][-42:]),
                flush=True)


if __name__ == "__main__":
    main()

