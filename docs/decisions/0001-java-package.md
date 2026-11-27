# Java package and platform

The approved Fabric profile assigns `io.github.achesmakes.airships` as the Java package root. Move source, tests and Fabric class references together. Preserve registry namespaces, assets and save formats. Keep the existing Fabric API version while testing the rename to avoid mixing a dependency upgrade into the regression comparison.

The build retains the existing Loom 1.19 snapshot because the repository baseline uses it for Minecraft 26.3. Snapshot resolution can change. Record the resolved build input in validation, and evaluate a compatible Loom release across all three mods before adopting it.
