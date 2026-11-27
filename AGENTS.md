# Airships

Follow the approved [Development Framework rollout](https://github.com/zrandrews-achesmakes/development-framework/issues/1) and use the [shared project](https://github.com/users/zrandrews-achesmakes/projects/4/views/1) for issue status. Use short-lived issue-linked branches and independent review. Keep legacy label-to-status automation disabled.

This repository uses the Minecraft Fabric single-module profile. Java sources use `io.github.achesmakes.airships`. Preserve the mod IDs and all persisted, network, resource, and configuration identifiers. Use disposable test worlds. Review the [architecture](docs/ARCHITECTURE.md) and [compatibility](docs/COMPATIBILITY.md) before changing those contracts. See [maintainer notes](docs/MAINTAINER_NOTES.md) for implementation details.

Run `./gradlew build runGameTest --no-configuration-cache` and, for client behavior, `./gradlew runClientGameTest --no-configuration-cache`. Use `gradlew.bat` on Windows. Pass `-Pwatch` to the client GameTest to follow its steps in an interactive desktop session.

Fabric metadata declares MIT. The baseline has no repository license text. Record the owner-approved copyright and license notice before distribution.

Use `python3 tools/format_java.py` to format Java sources. CI checks them with `--check`.
