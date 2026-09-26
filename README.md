# ClauAC

A predictive (simulation-based) anticheat plugin for [Paper](https://papermc.io/), built on
[PacketEvents](https://github.com/retrooper/packetevents).

> **Status:** early development. The repository currently contains the project setup and the PacketEvents
> lifecycle only; no checks are implemented yet.

## Target platform

| Component    | Version                                                                             |
|--------------|-------------------------------------------------------------------------------------|
| Server       | Paper 26.3 (as of 2026-09, Paper publishes 26.3 builds on its `ALPHA` channel)      |
| Client       | Minecraft 26.3 only                                                                 |
| Java         | 25                                                                                  |
| PacketEvents | 2.14.0 (bundled and relocated, see below)                                           |

All versions are declared in [`gradle/libs.versions.toml`](gradle/libs.versions.toml). The `minecraft` entry drives
both the `api-version` in `plugin.yml` and the version of the development server; `paper-api` pins an exact Paper API
build so that alpha API changes never slip into a build unnoticed.

## Building

```sh
./gradlew build
```

The plugin jar is written to `build/libs/ClauAC-<version>.jar`. The `-plain` jar next to it does not contain
PacketEvents and cannot run on a server.

A JDK 25 is not required on the machine running Gradle: the build declares a Java 25 toolchain and Gradle downloads
one automatically when none is installed.

## Development server

```sh
./gradlew runServer
```

[run-paper](https://github.com/jpenilla/run-paper) downloads the matching Paper build, installs the freshly built
plugin and starts the server in `run/` (ignored by Git). On the first start the server stops and asks you to accept
the Minecraft EULA in `run/eula.txt`.

## How PacketEvents is bundled

PacketEvents is shaded into the plugin jar and relocated below `io.github.hellotta.clauac.libs`, keeping the original
package name as the suffix:

| Original package                     | Relocated to                                                        |
|--------------------------------------|---------------------------------------------------------------------|
| `com.github.retrooper.packetevents`  | `io.github.hellotta.clauac.libs.com.github.retrooper.packetevents`  |
| `io.github.retrooper.packetevents`   | `io.github.hellotta.clauac.libs.io.github.retrooper.packetevents`   |
| `net.kyori` (adventure, examination) | `io.github.hellotta.clauac.libs.net.kyori`                          |

Because PacketEvents is bundled, ClauAC creates, loads, initialises and terminates its own PacketEvents instance
(see `ClauACPlugin`).

### Why adventure is bundled instead of using Paper's

PacketEvents 2.14.0 is compiled against adventure 4.26.1, while Paper 26.3 ships adventure 5.2.0, which is not binary
compatible with it: for example `ClickEvent.Action` is no longer an enum, `TranslatableComponent.args()` and
`ClickEvent.value()` were removed, and `Buildable.Builder` no longer exists. If PacketEvents used Paper's adventure,
serialising chat components (JSON and NBT, used by many play packets) would fail at runtime with
`NoClassDefFoundError` / `NoSuchMethodError`. The build therefore bundles the complete adventure copy PacketEvents
depends on and relocates it, so PacketEvents never touches Paper's adventure.

### What is intentionally not bundled

- **Netty**: part of the server's network stack, which PacketEvents injects into.
- **Gson**: PacketEvents references Gson but does not ship it, so the references must reach the server's Gson. For
  that reason `com.google.gson` is **not** relocated, even though the PacketEvents bundling guide lists it.
- **JetBrains annotations**: annotation-only, never needed at runtime.

### Rule for ClauAC's own code: never hand adventure objects to Paper

Relocation rewrites every `net.kyori` reference in the jar, including the ones in ClauAC's own classes. Code that
passes adventure types to or receives them from the Paper/Bukkit API therefore compiles (against Paper's adventure 5)
but fails at runtime with `NoSuchMethodError`, because after relocation it refers to the bundled copy instead of
Paper's. Examples that must not be used:

- `Player#sendMessage(Component)`, `Player#kick(Component)`, `CommandSender#sendMessage(Component)`
- `AsyncChatEvent#message()` and any other Paper API that takes or returns an adventure type

Use the `String` based Bukkit/Paper methods (for example `CommandSender#sendMessage(String)` or
`Player#sendPlainMessage(String)`), or build components for PacketEvents and send them through its `User` API.
Components built for PacketEvents run against the bundled adventure 4.26.1 even though the compiler sees Paper's
adventure 5.2.0, so only use adventure API that also exists in 4.26.1 there.

## Licensing

No license has been chosen for ClauAC yet. Note that release jars bundle PacketEvents (GPL-3.0) as well as
adventure and examination (MIT), so any distributed build has to comply with those licenses.
