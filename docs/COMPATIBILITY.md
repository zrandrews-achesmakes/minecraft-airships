# Airships compatibility

Use Minecraft Java 26.3, JDK 25, Fabric Loader 0.20.1, Fabric API `0.165.2+26.3`, and Gradle Wrapper 9.8.2. The build currently uses Fabric Loom `1.19-SNAPSHOT`. Keep these inputs while validating the package migration and retest the portfolio before changing the shared baseline.

Preserve the `airships` mod and registry namespace, ship entity and content IDs, payload IDs and `config/airships.json`. Test airship location, deck blocks and cargo across a saved-world reload.

Java consumers importing `io.github.zrandrews.airships` or using its Maven group need to update their references and rebuild. In-game identifiers remain the compatibility contract. Test saved state using a disposable copy.
