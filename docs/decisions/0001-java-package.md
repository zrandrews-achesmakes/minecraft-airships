# Java package and platform

The approved Fabric profile assigns `io.github.achesmakes.airships` as the Java package root. Move source, tests and Fabric class references together. Preserve registry namespaces, assets and save formats. Verify the package migration against the baseline dependency versions before testing the shared Fabric API version.

Pin Loom 1.19.6 and Fabric API 0.166.2+26.3 across the active Minecraft mods. The previously resolved Loom snapshot marker already pointed to the 1.19.6 release, and the compared JARs and dependency metadata matched. Pinning the release keeps future builds from following a moving snapshot.
