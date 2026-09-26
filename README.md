# ClauAC

A predictive (simulation-based) anticheat plugin for [Paper](https://papermc.io/), built on
[PacketEvents](https://github.com/retrooper/packetevents).

> **Status:** prototype. ClauAC simulates every client tick of a player's movement with Minecraft's own code and
> compares the result with what the client sent, but it only reports the results (CSV files, server log, action bar);
> it does not flag, set back or punish anyone yet.

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

The first build downloads the official vanilla 26.3 server from Mojang (the version JSON pinned in
[`gradle.properties`](gradle.properties)), verifies it against Mojang's hashes and extracts it to compile the
`simulation` module against; nothing of it is bundled.

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

## How the simulation works

Instead of re-implementing movement rules, ClauAC runs the vanilla game code itself: for every player it keeps a
client-side copy of the world and of the player (a *sandbox*), feeds it the same packets the real client receives, and
ticks it whenever the real client ticks. Whatever vanilla does in a situation, the sandbox does too, including
situations nobody wrote a rule for.

### Modules

| Module           | Runs in                               | Contents                                                              |
|------------------|---------------------------------------|-----------------------------------------------------------------------|
| `simulation-api` | the plugin's class loader             | The small interface between the plugin and the simulation             |
| `simulation`     | an isolated class loader (see below)  | The sandbox, compiled against the vanilla server                      |
| root project     | Paper                                 | The plugin: runtime loader, PacketEvents bridge, reports, `/clauac`   |

### The isolated vanilla runtime

Paper's own Minecraft classes cannot be used for the sandbox: Paper changes a lot of the game code (for example its
collision code) and ties levels to the Bukkit API. ClauAC therefore loads the **unmodified vanilla server jar** in its
own class loader, whose parent is the JDK's platform class loader, so none of Paper's classes are visible to it. Only
the `simulation-api` package and the logging APIs (SLF4J, Log4j) are shared with the plugin.

On startup ClauAC takes the vanilla server bundler from Paperclip's cache (`cache/mojang_26.3.jar` in the server
directory), or downloads it from Mojang when it is missing, verifies it against the hashes recorded at build time and
extracts the server jar and its libraries to `plugins/ClauAC/runtime/`. The runtime then runs Minecraft's bootstrap on
its own thread, which takes about 6-7 seconds; connections that start before it is ready are not simulated. The movement
code the sandbox runs is the common code the client runs as well: in 26.3 it is identical between the client jar and
the server jar.

The client-only parts the movement depends on are ported from the client jar and cite their original class:
`ClientLevel`, `ClientChunkCache`, `LocalPlayer`, `KeyboardInput`, `ClientPacketListener` (the parts that change the
level or the player), `RegistryDataCollector`, `KnownPacksManager` and `ClientClockManager`. Parts that only render,
play sounds or show screens are left out.

### Following the client's timeline

The client processes the server's packets between its ticks, so the sandbox must apply each packet before the same
tick the client applied it before. ClauAC keeps the server's packets pending until a packet from the client proves it
processed them:

- ClauAC sends a ping at the end of every server tick; the client answers each ping with a pong when it processes it.
- A teleport is answered with the teleport acceptance, a rotation packet with a rotation, and the start of a
  configuration phase with its acknowledgement.

When the client's tick end packet arrives, the sandbox runs `Minecraft.tick` for one tick with the keys, rotation and
sprint commands the client sent for that tick, then mirrors `LocalPlayer.sendPosition` and compares: whether a
position had to be sent, the exact position, `onGround`, horizontal collision, sprinting, flying and the start of
gliding. When anything differs, the sandbox continues from the client's reported state, including a velocity
correction derived from the tick it just simulated.

### Results

Each client tick gets one outcome:

| Outcome         | Meaning                                                                                    |
|-----------------|--------------------------------------------------------------------------------------------|
| `MATCHED`       | The simulation produced exactly what the client sent                                       |
| `MISMATCHED`    | It did not, and nothing outside the simulation's reach was involved                        |
| `UNVERIFIED`    | It did not, but something the simulation does not reproduce was involved (see the notes)   |
| `NOT_SIMULATED` | The client did not move its player (loading screen, dead, riding)                          |

- Every connection is recorded to `plugins/ClauAC/reports/<time>-<player>.csv`, one line per client tick, with the
  predicted and reported values, the offset, whether another entity was within one block, and notes.
- `MISMATCHED` ticks are logged to the server console, and a summary is logged when the player leaves.
- `/clauac debug` shows the outcome of every tick in your action bar, `/clauac status` summarises all connections
  (permission `clauac.admin`, operators by default).

### Not simulated yet

Some influences on the client's movement are outside the sandbox. Those ClauAC can recognise from the client's own
packets turn a differing tick into `UNVERIFIED` instead of `MISMATCHED`; the others still show up as `MISMATCHED`.

- Recognised: item use (the inventory is not tracked), starting to glide (the equipment is not tracked), the
  client's own block predictions until the server acknowledges them, attacks and entity interactions, and teleports
  whose result differs from the sandbox's. Riding is reported as `NOT_SIMULATED`.
- Not recognised: pushes and collisions from other entities, which are not part of the sandbox (the CSV's
  `entityNearby` column helps to tell these apart), equipment effects that do not reach the client as attributes (for
  example leather boots on powder snow), and blocks moved by pistons.

### Verified so far

With a real 26.3 client on Paper 26.3 (build 45): walking, jumping, sneaking, sprinting and sprint jumping, stairs
up and down, sinking, swimming and leaving water, a 50 block fall into water, speed and jump boost effects, knockback
from damage, teleports and creative flight all produced `MATCHED` on every tick, with an offset of exactly 0. While
the player was dead the sandbox, like the client, did not move it (`NOT_SIMULATED`), and matching resumed after the
respawn. Walking into a cow produced `MISMATCHED` only on the ticks the cow pushed the player.

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
