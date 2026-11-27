import hashlib
import json
import os
import queue
import re
import shutil
import socket
import subprocess
import sys
import threading
import time
import urllib.request
import zipfile
from pathlib import Path


EXPECTED = {
    "airships": {"airships"},
    "keeper": {"keeper_skills", "keeper_mobs", "keeper_dimension"},
    "survivalutilities": {"survivalutilities"},
}
LAUNCHER = "https://meta.fabricmc.net/v2/versions/loader/26.3/0.20.1/1.1.2/server/jar"
API = "https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api"
SHIP = '@e[type=airships:ship,tag=m09_ship,limit=1]'
COINS = 'Items[{Slot:0b,id:"keeper:ancient_coin",count:17}]'
BED = 'Items[{Slot:1b,id:"privatebed:white_private_bed",count:1}]'
SORTER = 'Items[{Slot:0b,id:"invmanager:sorter",count:1}]'
HOPPER = 'Items[{Slot:0b,id:"minecraft:iron_ingot",count:11}]'


def digest(path):
    with path.open("rb") as f:
        return hashlib.file_digest(f, "sha256").hexdigest()


def download(url, path):
    with urllib.request.urlopen(url, timeout=60) as response, path.open("xb") as output:
        shutil.copyfileobj(response, output)


def stage(inputs, ids, api_version, directory):
    directory.mkdir()
    found = set()
    for path in sorted(inputs.rglob("*.jar")):
        with zipfile.ZipFile(path) as jar:
            if "fabric.mod.json" not in jar.namelist() or path.name.endswith("-sources.jar"):
                continue
            mod = json.loads(jar.read("fabric.mod.json"))["id"]
        if mod in ids:
            if mod in found:
                raise AssertionError(f"Duplicate mod {mod} in {inputs}")
            found.add(mod)
            print(f"ARTIFACT {inputs.name} {mod} {path.name} sha256={digest(path)}", flush=True)
            shutil.copyfile(path, directory / path.name)
    if found != ids:
        raise AssertionError(f"Expected mod IDs {ids}, found {found} in {inputs}")
    package = f"fabric-api-{api_version}.jar"
    download(f"{API}/{api_version}/{package}", directory / package)


class Server:
    def __init__(self, work, path):
        self.lines = queue.Queue()
        self.command_number = 0
        java = Path(os.environ["JAVA_HOME"]) / "bin" / ("java.exe" if os.name == "nt" else "java")
        self.process = subprocess.Popen(
            [str(java), "-Xms512M", "-Xmx2G", "-jar", "fabric.jar", "nogui"],
            cwd=work, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
            text=True, encoding="utf-8", errors="replace", bufsize=1,
        )
        self.reader = threading.Thread(target=self.read, args=(path,), daemon=True)
        self.reader.start()

    def read(self, path):
        with path.open("x", encoding="utf-8") as log, self.process.stdout:
            for line in self.process.stdout:
                log.write(line)
                log.flush()
                self.lines.put(line.rstrip())
        self.lines.put(None)

    def wait(self, pattern, timeout):
        deadline = time.monotonic() + timeout
        output = []
        try:
            while (remaining := deadline - time.monotonic()) > 0:
                line = self.lines.get(timeout=remaining)
                if line is None:
                    break
                output.append(line)
                if re.search(pattern, line):
                    return output
        except queue.Empty:
            pass
        raise AssertionError(f"Missing output {pattern!r}:\n" + "\n".join(output[-50:]))

    def send(self, cmd):
        self.process.stdin.write(cmd + "\n")
        self.process.stdin.flush()

    def command(self, cmd):
        self.command_number += 1
        barrier = f"M09_BARRIER_{self.command_number:04d}"
        self.send(cmd)
        self.send("say " + barrier)
        return self.wait(r"\[Server\] " + barrier + "$", 25)[:-1]

    def require(self, cmd, marker, attempts=1):
        for _ in range(attempts):
            lines = self.command(cmd)
            if any(re.search(marker, line) for line in lines):
                return
            time.sleep(1)
        raise AssertionError(f"{cmd!r} did not produce {marker!r}:\n" + "\n".join(lines))

    def check(self, condition, name, attempts=1):
        self.require(f"execute {condition} run say M09_ASSERT_{name}", rf"\[Server\] M09_ASSERT_{name}$", attempts)
        print(f"PASS {name}", flush=True)

    def stop(self):
        self.require("save-all flush", "Saved the game")
        self.send("stop")
        if self.process.wait(timeout=90) != 0:
            raise AssertionError("Minecraft failed to shut down cleanly")
        self.reader.join(timeout=5)

    def close(self):
        if self.process.poll() is None:
            self.process.terminate()
            try:
                self.process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                self.process.kill()
                self.process.wait(timeout=10)
        self.reader.join(timeout=5)


def seed(server, name):
    server.require("forceload add 0 0", "Marked chunk")
    if name == "airships":
        server.require("setblock 2 80 2 airships:helm", "Changed the block", 12)
        ship_data = (
            '{Tags:["m09_ship"],fuel:42,ship_blocks:['
            '{pos:[I;0,0,0],state:{id:"airships:helm"}},'
            '{pos:[I;1,0,0],state:{id:"minecraft:chest"},'
            'block_entity:{id:"minecraft:chest",x:3,y:80,z:2,'
            'Items:[{Slot:0b,id:"minecraft:diamond",count:7}]}}]}'
        )
        server.require(f"summon airships:ship 4 85 4 {ship_data}", "Summoned new")
    elif name == "keeper":
        server.require("execute in keeper:underground run forceload add 0 0", "Marked chunk")
        server.require("execute in keeper:underground run setblock 2 120 2 minecraft:chest", "Changed the block", 12)
        server.require("execute in keeper:underground run item replace block 2 120 2 container.0 with keeper:ancient_coin 17", "Replaced")
        server.require('summon keeper:gloam 3 85 3 {Tags:["m09_gloam"],NoAI:1b,PersistenceRequired:1b}', "Summoned new")
    else:
        server.require("setblock 2 80 2 survivalutilities:magnet_hopper", "Changed the block", 12)
        server.require("item replace block 2 80 2 container.0 with minecraft:iron_ingot 11", "Replaced")
        server.require("setblock 4 80 2 minecraft:chest", "Changed the block")
        server.require("item replace block 4 80 2 container.0 with invmanager:sorter", "Replaced")
        server.require("item replace block 4 80 2 container.1 with privatebed:white_private_bed", "Replaced")


def check_fixture(server, name):
    if name == "airships":
        server.check("if block 2 80 2 airships:helm", "ship_helm", 12)
        server.check(f"if entity {SHIP}", "ship_entity", 12)
        server.check(f'if data entity {SHIP} {{fuel:42}}', "ship_fuel")
        ship_data = server.command(f"data get entity {SHIP} ship_blocks")
        print("\n".join(ship_data), flush=True)
        cargo = 'ship_blocks[{state:{id:"minecraft:chest"}}].block_entity.Items[{id:"minecraft:diamond",count:7}]'
        server.check(f"if data entity {SHIP} {cargo}", "ship_cargo")
    elif name == "keeper":
        server.check(f"in keeper:underground if data block 2 120 2 {COINS}", "underground_coins", 12)
        server.check("if entity @e[type=keeper:gloam,tag=m09_gloam,limit=1]", "keeper_mob", 12)
    else:
        server.check("if block 2 80 2 survivalutilities:magnet_hopper", "magnet_hopper", 12)
        server.check(f"if data block 2 80 2 {HOPPER}", "magnet_hopper_inventory")
        server.check(f"if data block 4 80 2 {SORTER}", "nested_inventory_manager")
        server.check(f"if data block 4 80 2 {BED}", "nested_private_beds")


def main():
    name, before, after, base_api, fixture = sys.argv[1:]
    work = Path(fixture).resolve()
    work.mkdir()
    logs = work / "evidence"
    logs.mkdir()
    stage(Path(before).resolve(), EXPECTED[name], base_api, work / "baseline-mods")
    stage(Path(after).resolve(), EXPECTED[name], "0.166.2+26.3", work / "candidate-mods")
    download(LAUNCHER, work / "fabric.jar")
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        port = s.getsockname()[1]
    (work / "eula.txt").write_text("eula=true\n", encoding="ascii")
    (work / "server.properties").write_text(
        "server-ip=127.0.0.1\n"
        f"server-port={port}\n"
        "level-name=m09-world\nlevel-type=minecraft:flat\nlevel-seed=976723\n"
        "enable-query=false\nenable-rcon=false\nview-distance=3\nsimulation-distance=3\n"
        "spawn-protection=0\ngenerate-structures=false\n",
        encoding="ascii",
    )
    shutil.copytree(work / "baseline-mods", work / "mods")
    world = work / "m09-world"
    assert not world.exists(), world
    first = Server(work, logs / "baseline.log")
    try:
        first.wait(r'Done \(.+\)! For help', 150)
        first.command("gamerule minecraft:do_mob_spawning false")
        seed(first, name)
        check_fixture(first, name)
        first.stop()
    finally:
        first.close()
    assert (world / "level.dat").stat().st_size > 0
    parents = sorted(world.rglob("*.mca"))
    assert parents, "The baseline did not produce any world region files"
    saved = {str(path.relative_to(world)): digest(path) for path in [world / "level.dat", *parents]}
    (logs / "baseline-world-sha256.json").write_text(json.dumps(saved, indent=2) + "\n", encoding="utf-8")
    (work / "mods").rename(work / "previous-runtime-mods")
    shutil.copytree(work / "candidate-mods", work / "mods")
    assert all(digest(world / file) == value for file, value in saved.items())
    second = Server(work, logs / "candidate.log")
    try:
        second.wait(r'Done \(.+\)! For help', 150)
        # The candidate receives only read-only assertions against the saved fixture.
        check_fixture(second, name)
        second.stop()
    finally:
        second.close()
    print(f"M09_PRIOR_BUILD_WORLD_OK repo={name} preserved_world_files={len(saved)}", flush=True)


if __name__ == "__main__":
    main()

