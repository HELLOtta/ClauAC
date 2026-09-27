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

The alternatives of uncertain ticks (see below) rely on a snapshot of the player that holds everything a tick
changes. To check that it does, start the server with the system property `clauac.verifyRepeatedTicks=true`, for
example `JAVA_TOOL_OPTIONS=-Dclauac.verifyRepeatedTicks=true ./gradlew runServer`: every tick of a player on foot then
runs a second time from the snapshot, and a second run that ends differently is logged with the field it differs in.

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

A riding player sends a rotation with its ground and collision state every tick instead, and, while it steers the
vehicle (a boat, a saddled horse or camel, a pig or strider with its item on a stick, a happy ghast with a harness, a
saddled nautilus), the vehicle's position, rotation and ground state, its sprinting and the power of a vehicle jump.
The sandbox moves the vehicle with the same vanilla code and keys and compares all of it exactly; a vehicle the player
does not steer moves as the server says, which the sandbox follows like the client. Two things need care:

- A boat turns its rider during the tick, so the rotation the client reports is the one after that turn. The sandbox
  starts the tick with the reported rotation minus the turn the client reported for the boat, and ends it with the
  reported rotation.
- A hotbar key pressed during a tick changes the held item at once, but the client reports the new slot only at the
  start of its next tick. For a vehicle steered by what the player holds, the tick's own packets show the switch: the
  client sends the vehicle's position exactly while it steers. When they contradict the held item, the sandbox selects
  the first hotbar slot that explains them and holds the tick's result until the next tick reports the slot; a switch
  the next tick does not report makes the tick `MISMATCHED`.

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
| `NOT_SIMULATED` | The client did not move its player (loading screen, dead)                                  |

- Every connection is recorded to `plugins/ClauAC/reports/<time>-<player>.csv`, one line per client tick, with the
  predicted and reported values, the offset, whether another entity was within one block, the predicted and reported
  state of a vehicle the player steers, the time the simulation spent on the tick (see below), and notes.
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

### Cost and limits

The connections are simulated on half of the server's processors (the runtime logs how many threads when it starts),
each connection's packets one after another. What that costs is measured all the time:

- The report's `simulationNanos` column holds the time of each client tick: what the simulation spent on the
  connection since the previous tick, the server's packets in between and the tick itself.
- `/clauac status` adds a line for every simulated connection: its share of one simulation thread, the average time
  per client tick with the median and the 99th percentile of the last minute and the longest tick, the packets waiting
  for the simulation and how long the oldest has waited, the server's packets the client has not confirmed yet, and
  the snapshots taken for the alternatives of uncertain ticks. The same line is logged when the player leaves.

Two limits keep one connection from taking the server's memory:

- A simulation that falls behind its connection, with more than 64 MiB of packets waiting for it or a packet waiting
  longer than 30 seconds, is stopped: the waiting packets are dropped, and the reason is logged and shown by
  `/clauac status`. Results that late would help nobody.
- A client that leaves more than 32 MiB or 100 000 of the server's packets unconfirmed, which only a client that hangs
  or does not answer the pings does, gets the older half applied as if it had answered; a vanilla client handles
  everything it received before its next tick anyway. That tick is `MISMATCHED`, and the simulation goes on, so that
  holding back the answers cannot switch it off.

Two more keep one connection from taking the simulation threads:

- A client's ticks are simulated only as fast as a vanilla client can end them. The client's timer ends one tick per
  50 ms, or per tick of the server's lower tick rate while its level runs normally
  (`Minecraft.getTickTargetMillis`), and after a pause it catches up at most 10 ticks at a time and drops the rest, so
  it never gets ahead of real time. The ticks still arrive at the server unevenly, all at once after the connection
  stalled, so the budget refills with the time between their arrivals, up to the ticks of 60 seconds. Paper sends a
  keep-alive every second and disconnects a client whose answer to one is more than 30 seconds late, so the client's
  packets are never much later than that, and the rest of the budget covers the uneven arrivals of the ticks after
  such a stall. It refills 1% faster than the tick rate, far more than the client's clock can drift from the
  server's, so that a client whose clock runs fast never uses it up. A
  tick beyond the budget is not simulated: it is `MISMATCHED` with the reason, the sandbox takes over what the client
  reported, and the next tick within the budget is simulated from there. Ending ticks faster therefore cannot make
  the simulation fall behind and stop.
- The simulation threads work off a connection's packets for at most 10 ms at a time before the other connections
  waiting for a thread go first.

The system properties `clauac.maximumQueuedMebibytes`, `clauac.maximumLagMillis`, `clauac.maximumUnconfirmedMebibytes`,
`clauac.maximumUnconfirmedPackets` and `clauac.maximumTickBurstMillis` change these limits; the runtime logs the ones
in effect when it starts.

Measured on the development server in a container with 4 processors (2 simulation threads) with a real 26.3 client,
over the seven test courses (walking, swimming, effects, flight, entities, menus, gliding, riding, pistons):

- A client tick took 1.1 to 1.7 ms on average, depending on the course, with a median of about 1 ms and a 99th
  percentile of 4.5 to 9.5 ms; single ticks took up to about 50 ms. That is about 2.5% of one simulation thread per
  player. A Java Flight Recorder profile showed most of it going into ticking the entities and block entities the
  client knows, as the client itself does; 30 chickens next to the player added 0.2 to 1 ms per tick.
- Joining is the most expensive part: the configuration phase with the registries and the first chunks took about
  1.3 to 1.6 s of simulation time, which left the simulation up to 0.4 s behind for a moment.
- A snapshot of the player's state (about 300 objects) took about 1 ms once warmed up, and up to 3 ms on average
  where only a few were taken. Snapshots are only taken in ticks with alternatives, such as every tick an item is
  used in the main hand, which then took 0.5 to 2 ms more. With `clauac.verifyRepeatedTicks=true`, which repeats
  every tick from a snapshot, a tick took 4 to 6 ms on average.

The memory limits were tried with lowered values. With `clauac.maximumLagMillis=100` the configuration phase of a
joining client left a packet waiting longer than that, and the simulation stopped with the reason in the log and in
`/clauac status`. With `clauac.maximumUnconfirmedPackets=2000` and a proxy dropping the client's pongs next to 30
chickens, the older half was applied about every 25 ticks, each time with a `MISMATCHED` tick, the ticks in between
matched unless the chickens pushed the player, and every tick matched again once the pongs got through.

The tick budget was tried with a proxy between the client and the server. Holding the client's packets for 15 or 25
seconds and then sending them at once, as a stalled connection does, left every tick of that time `MATCHED`. 1500
tick ends injected at once 12 seconds after a hold of 15 seconds were simulated until the budget ran out, 902 of
them, and the other 598 were not; all 1500 were `MISMATCHED`, the simulation stayed within 0.53 s of the
connection, and the client's own ticks matched again right after them.

### What the simulation cannot know

The client's packets do not tell everything it did. Where such a gap decides how the player moves, the sandbox
simulates the alternatives: at the player's place in the tick it saves the player's whole state, runs the tick as
simulated and compares it with what the client reported. When they differ, every alternative and every combination
of them runs from the saved state, and the first that matches what the client reported stays; the tick is then
`MATCHED`, with the alternative in its notes. When none matches, the tick is `MISMATCHED`. The alternatives are:

- A hotbar switch the client reports at the start of a tick may have happened during the previous tick's key handling
  instead, when that tick's player already held the new item. That switch may have stopped the item use of the
  previous tick, which is then held back until the next tick reports the switch; an alternative that matched this way
  and gets no switch reported is `MISMATCHED`. With the attack strength that earlier switch left, an attack may have
  slowed the player down (a knockback attack) where the sandbox's did not, or the other way round: that attack runs
  again with the other strength.
- An attack on an entity the sandbox does not know, which no vanilla client makes, may have slowed the player down or
  not, depending on the entity.

What cannot be tried that way leaves the tick `UNVERIFIED` instead of `MISMATCHED`:

- The alternatives above while the player rides, while blocks move next to it (a piston, a shulker box), which move
  it only after its tick, and in a tick whose actions after the attack changed the player.
- The client's velocity is never reported. After a difference the sandbox estimates it from the reported movement;
  the rounding of that estimate can move later positions by a few units in the last place, and after an `UNVERIFIED`
  tick, a difference that keeps shrinking in the ticks right after it stays `UNVERIFIED`.
- A switch that stopped an item use when the new item changes an attribute that moves the player (speed, gravity,
  scale, step height and the like), which the alternative leaves out.
- Items the sandbox had to mark unknown, until the server's resend arrives, and teleports whose resulting position
  differs from the sandbox's. A rotation that differs is the client's input and is taken over. Interacting with an
  entity the sandbox does not know never moves the player, but may use up or fill the held item, so the sandbox then
  marks its items unknown and has them resent.
- With the experimental minecart movement, a minecart turns its rider only while the client's "rotate with minecart"
  option is on, which the server never learns. Placing a block or swinging at what the crosshair points at in such a
  minecart depends on the rotation the client had.

Known limits:

- An `UNVERIFIED` tick accepts any difference; only what the alternatives cannot cover (see above) is left to it.
- The rotation a boat's rider starts a tick with is exact up to the rounding of the float rotations it is computed
  from; the client's own boat adds such rounding at every frame (`AbstractBoat.clampRotation`), and no packet reports
  it. Only a rotation-dependent action within that rounding of a boundary could differ.
- Not verified in game yet: riding in a boat another player steers.
- The client opens its own inventory screen without telling the server, and its creative inventory screen ignores
  cursor updates and keeps its own menu when the game mode changes. The sandbox cannot follow those; the differences
  show up in the next checked click and are resolved by the inventory resend.
- A relative rotation packet whose answer the client computed from a rotation the sandbox has not seen yet is applied
  at the next pong instead.
- A connection that was already playing when ClauAC started watching it (after a plugin reload) is not simulated:
  the simulation has to see a connection from its first configuration packet on.
- The tick budget (see "Cost and limits") bounds what the simulation costs; it is not a timer check. A client that
  ends fewer ticks than real time allows for a while may end that many more later, up to the ticks of 60 seconds at
  once, and 1% more than real time allows all along. Those ticks are simulated and checked like any other, so each of
  them still has to move the player as vanilla would.
- Every bundle ClauAC sends ends with a ping that the client answers, so the client sends more packets than without
  ClauAC: in the test world, a player standing still answered about 90 pings per second next to its 20 tick ends.
  Paper disconnects a client that sends more than 500 packets per second over 7 seconds (`packet-limiter` in
  `paper-global.yml`); the packets a connection stall of 25 seconds held back went over that once they arrived,
  where without the pongs they would have stayed far below it.

### Verified so far

With a real 26.3 client on Paper 26.3, every tick of the following produced `MATCHED` with an offset of exactly 0:
walking, jumping, sneaking, sprinting and sprint jumping, stairs up and down, sinking, swimming and leaving water, a
50 block fall into water, speed and jump boost effects, knockback from damage, a TNT explosion and a wind charge, teleports,
creative flight, gliding with an elytra and boosting with fireworks, cows and another player pushing the player,
walking into a boat and stepping onto it, a team whose collision rule stops those pushes, eating, blocking with a
shield and drawing a bow while walking, placing a block and walking into it, breaking blocks, and a sprint hit on a
boat and on another player. Riding matched as well, the vehicle included: steering a boat on water through turns and
leaving it, a horse walking, sprinting and making a charged jump, a camel walking and dashing, a pig steered with a
carrot on a stick and boosted, a strider on lava, a happy ghast flying up, forward and down, a nautilus swimming and
dashing, a minecart on powered rails, and a panicking pig the server moved, which the player took over with a hotbar
key and handed back the same way.

Pistons matched too: a piston pushing the player sideways, into a wall, and back while the player walked towards it,
lifting the block the player stood on, pushing the jumping player down, and lifting the player against a ceiling,
which left it in the lifted block until the client moved it out sideways; a sticky piston lifting the player and
pulling it down again, a honey block carrying it, a slime block launching it up and throwing it sideways, pistons
powered in pulses from one tick up while the player walked backwards into them, and a piston and a slime block
pushing a boat the player rode. Paper's own movement check logged some of these as `moved wrongly` and teleported the
player back to where the server's pistons had put it; the sandbox followed those teleports as the client did.

Equipment effects matched as well. Without boots, the player sank into powder snow, moved and jumped slowly in it,
and the frost slowed it down until it wore off. With leather boots it walked over powder snow, sneaked down through
it, climbed out of it, and landed on it from a fall: a falling player is caught 0.9 blocks up and then sinks, with the
boots down onto the next block and without them to the bottom. Boots put on from the hand at the bottom of the snow
let the player climb out, and boots the server took away while the player stood on the snow let it sink in. Depth
strider on a pool's bottom and while swimming up, with the boots taken away mid-walk, soul speed walking, jumping and
sprinting over soul sand and soul soil, swift sneak, frost walker over water and the lunge of a spear matched too.
The client works out powder snow from the boots it wears; everything else here comes from the server, which the
sandbox applied at the same point as the client: the attributes (the equipment's modifiers, the enchantments' effects
and the frost), the ice frost walker makes and the push of the lunge.

Menu clicks matched the client's hashes in chests, the player's inventory (crafting included), furnaces, stonecutters,
anvils (renaming included), villager trades and horse inventories, including shift clicks, number keys and dragging.
A switch from the creative inventory screen straight to survival made the client click in a menu the sandbox did not
have; the next click showed the difference and the inventory resend brought both back in line. Switching the hotbar
slot while eating matched through the alternative that the switch stopped the item use a tick before the client
reported it, which the next tick confirmed; a sprint attack right after a hotbar switch matched through the attack
strength that switch left, and an attack on an entity id the sandbox did not know, injected into the connection,
matched without slowing the player down. While the player was dead the sandbox, like the client, did not move it
(`NOT_SIMULATED`), and matching resumed after the respawn.

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
