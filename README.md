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
client-side copy of the world, of every entity the client knows and of the player with its items (a *sandbox*), feeds
it the same packets the real client receives, and ticks it whenever the real client ticks. Whatever vanilla does in a
situation, the sandbox does too, including situations nobody wrote a rule for.

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
its own thread, which takes about 6-7 seconds. The packets of a connection that starts before it is ready are kept in
order and handed to the simulation once it is, up to 32 MiB per connection and 256 MiB for all waiting connections
together; a connection beyond either limit is not simulated. The movement code the sandbox runs is the common code the
client runs as well: in 26.3 it is identical between the client jar and the server jar.

The client-only parts the simulation depends on are ported from the client jar and cite their original class:
`ClientLevel` (including its block prediction handling), `ClientChunkCache`, `LocalPlayer`, `KeyboardInput`,
`RemotePlayer` and `AbstractClientPlayer`, `ClientPacketListener` (the parts that change the level, the entities,
the player or its menus), `MultiPlayerGameMode`, the key and mouse handling of `Minecraft` (`handleKeybinds`, `pick`),
`MenuScreens` and the screens that change their menu themselves (merchant, anvil, crafter, stonecutter, enchanting
table, loom, bundles), `ClientRecipeContainer`, `RegistryDataCollector`, `KnownPacksManager` and
`ClientClockManager`. Parts that only render, play sounds or show screens are left out.

### Following the client's timeline

The client processes the server's packets between its ticks, so the sandbox must apply each packet before the same
tick the client applied it before. ClauAC keeps the server's packets pending until a packet from the client proves it
processed them:

- Every relevant play packet goes to the client inside a bundle that ends with a ping. The client handles all packets
  of a bundle in one go, in one task on its main thread (`ClientPacketListener.handleBundlePacket`), and answers the
  ping right there, so none of its ticks can fall between a packet and the ping behind it. A bundle collects
  everything the connection's event loop writes before it gets to end it; vanilla's own bundles become part of it.
  The start of a configuration phase and a disconnect end the bundle before them: the client refuses the former inside
  a bundle and would never handle the latter in a bundle the closed connection cannot end any more.
- A teleport is answered with the teleport acceptance, a rotation packet with a rotation, and the start of a
  configuration phase with its acknowledgement.

When the client's tick end packet arrives, the sandbox runs `Minecraft.tick` for one tick: it replays what the client
did with its keys and mouse during that tick from the packets that produced (hotbar selection, breaking and placing
blocks with the client's own block predictions, using items, attacks, interactions, dropping items), ticks every
entity, and moves the player with the keys, rotation and sprint commands the client sent. It then mirrors
`LocalPlayer.sendPosition` and compares: whether a position had to be sent, the exact position, `onGround`,
horizontal collision, sprinting, flying and the start of gliding. When anything differs, the sandbox continues from the
client's reported state, including a velocity estimated from the tick it just simulated.

Menu clicks happen on screens between the client's ticks and are applied right away. The client sends the slots a
click changed as hashes, so every click is checked: when the sandbox's items turn out to differ from the client's, it
marks them unknown and ClauAC has the server resend the player's inventory (Paper's `Player#updateInventory`), which
the sandbox takes over when it arrives.

### Results

Each client tick gets one outcome:

| Outcome         | Meaning                                                                                    |
|-----------------|--------------------------------------------------------------------------------------------|
| `MATCHED`       | The simulation produced exactly what the client sent                                       |
| `MISMATCHED`    | It did not, and nothing outside the simulation's reach was involved; or the client sent a  |
|                 | packet no vanilla client sends in that situation                                           |
| `UNVERIFIED`    | It did not, but something the simulation cannot know was involved (see the notes)          |
| `NOT_SIMULATED` | The client did not move its player (loading screen, dead, riding)                          |

- Every connection is recorded to `plugins/ClauAC/reports/<time>-<player>.csv`, one line per client tick, with the
  predicted and reported values, the offset, whether another entity was within one block, and notes.
- `MISMATCHED` ticks are logged to the server console, and a summary is logged when the player leaves; so is every
  inventory resend.
- `/clauac debug` shows the outcome of every tick in your action bar, `/clauac status` summarises all connections
  (permission `clauac.admin`, operators by default).

A packet from the client that the sandbox cannot decode or apply, or that no vanilla client sends in the situation
the sandbox is in (a pong or a teleport acceptance for something the server never sent, a tick outside the play phase,
a hotbar slot that does not exist), is rejected: its tick is `MISMATCHED` with the reason in the notes, and the
simulation goes on, so that no packet can switch it off. An accepted teleport the server never sent tells nothing about
where the client is and is not taken over. A failure of the simulation itself during a tick makes that tick
`MISMATCHED` as well. Each distinct problem is logged once with its stack trace. A server packet the sandbox cannot
apply would make the real client fail too, since the sandbox runs the client's own handlers; it stops the simulation
of the connection, and `/clauac status` shows why.

### What the simulation cannot know

The client's packets do not tell everything it did. Where the difference that follows can come from such a gap,
the tick is `UNVERIFIED` instead of `MISMATCHED`:

- A hotbar switch the client reports at the start of a tick may have happened during the previous tick's key handling
  instead, when that tick's player already held the new item. A tick whose difference can come from the item use that
  switch stopped is reported one tick late, as `UNVERIFIED`; an attack whose knockback depends on when the attack
  strength was reset that way is `UNVERIFIED` as well.
- The client's velocity is never reported. After a difference the sandbox estimates it from the reported movement;
  the rounding of that estimate can move later positions by a few units in the last place, and after an `UNVERIFIED`
  tick, a difference that keeps shrinking in the ticks right after it stays `UNVERIFIED`.
- Items the sandbox had to mark unknown, the first tick after riding, attacks on or interactions with entities the
  sandbox does not know, and teleports whose result differs from the sandbox's.

Known limits:

- An `UNVERIFIED` tick accepts any difference; the alternatives are not simulated yet, so a tick that is uncertain is
  not bounded either.
- Riding is `NOT_SIMULATED`: vehicles are placed where the client reports them, not compared.
- The client opens its own inventory screen without telling the server, and its creative inventory screen ignores
  cursor updates and keeps its own menu when the game mode changes. The sandbox cannot follow those; the differences
  show up in the next checked click and are resolved by the inventory resend.
- A relative rotation packet whose answer the client computed from a rotation the sandbox has not seen yet is applied
  at the next pong instead.
- A connection that was already playing when ClauAC started watching it (after a plugin reload) is not simulated:
  the simulation has to see a connection from its first configuration packet on.
- Not verified yet: pistons moving blocks and entities, and equipment effects such as leather boots on powder snow.

### Verified so far

With a real 26.3 client on Paper 26.3, every tick of the following produced `MATCHED` with an offset of exactly 0:
walking, jumping, sneaking, sprinting and sprint jumping, stairs up and down, sinking, swimming and leaving water, a
50 block fall into water, speed and jump boost effects, knockback from damage, a TNT explosion and a wind charge, teleports,
creative flight, gliding with an elytra and boosting with fireworks, cows and another player pushing the player,
walking into a boat and stepping onto it, a team whose collision rule stops those pushes, eating, blocking with a
shield and drawing a bow while walking, placing a block and walking into it, breaking blocks, and a sprint hit on a
boat and on another player. Riding a boat and a horse was `NOT_SIMULATED`, and the first
tick after dismounting matched the client's dismount position.

Menu clicks matched the client's hashes in chests, the player's inventory (crafting included), furnaces, stonecutters,
anvils (renaming included), villager trades and horse inventories, including shift clicks, number keys and dragging.
A switch from the creative inventory screen straight to survival made the client click in a menu the sandbox did not
have; the next click showed the difference and the inventory resend brought both back in line. Switching the hotbar
slot while eating produced `UNVERIFIED` for the tick the switch was ambiguous for, as intended. While the player was
dead the sandbox, like the client, did not move it (`NOT_SIMULATED`), and matching resumed after the respawn.

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
