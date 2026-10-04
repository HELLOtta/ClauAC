# ClauAC

A predictive (simulation-based) anticheat plugin for [Paper](https://papermc.io/), built on
[PacketEvents](https://github.com/retrooper/packetevents).

> **Status:** prototype. ClauAC simulates every client tick of a player's movement with Minecraft's own code and
> compares the result with what the client sent, including what the client's key handling did: its attacks and
> interactions with entities, the blocks it broke and placed and the items it used. It names the checks a tick
> failed, keeps the movement of a failed tick from the server and sets the player back, keeps actions no vanilla
> client makes from the server and has the client take back what it predicted of them, alerts the players who watch
> for failed checks and calls an event other plugins can act on (see "Responses"). It does not kick or punish anyone.

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
| root project     | Paper                                 | The plugin: runtime loader, PacketEvents bridge, reports, responses,  |
|                  |                                       | `/clauac`                                                             |

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
  The start of a configuration phase, a disconnect and a keep-alive end the bundle before them: the client refuses the
  first inside a bundle and would never handle the second in a bundle the closed connection cannot end any more. It
  answers a keep-alive right away on its network thread (`ClientCommonPacketListenerImpl.handleKeepAlive`), but one
  inside a bundle only once its main thread handles the bundle. After a stall of the connection, the keep-alives of
  those seconds reach the client together: in a test with long stalls, one in a bundle was answered after the later
  ones outside of bundles, and Paper disconnected the player for answering out of order
  (`ServerCommonPacketListenerImpl.handleKeepAlive`).
- The pongs that answer these pings go to the simulation and no further. The server never sent those pings and ignores
  pongs anyway, but Paper's packet limiter counts every packet the server decodes, and the pongs make up most of what
  a client sends: 55 to 95 per second for a player standing still among the animals of the test world, and 150 per
  second with 150 chickens walking around it, 87% of all it sent. The pings' ids start at a random place among the
  2^30 lowest ints, away from the small counters other plugins use for their own pings, whose pongs pass through. A
  connection stall of 25 seconds, whose 3052 held packets arrived at once, stays below Paper's limit of 500 packets
  per second over 7 seconds (`packet-limiter` in `paper-global.yml`) that way.
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
does not steer moves as the server says, which the sandbox follows like the client. Three things need care:

- A boat turns its rider during the tick, so the rotation the client reports is the one after that turn. The sandbox
  starts the tick with the reported rotation minus the turn the client reported for the boat, and ends it with the
  reported rotation.
- A hotbar key pressed during a tick changes the held item at once, but the client reports the new slot only at the
  start of its next tick. For a vehicle steered by what the player holds, the tick's own packets show the switch: the
  client sends the vehicle's position exactly while it steers. When they contradict the held item, the sandbox selects
  the first hotbar slot that explains them and holds the tick's result until the next tick reports the slot; a switch
  the next tick does not report makes the tick `MISMATCHED`.
- The client answers the server's correction of the vehicle it steers right away with the vehicle's new position
  (`ClientPacketListener.handleMoveVehicle`), while it handles the server's packets and so before its next tick sends
  a position of its own. The sandbox checks that answer against its own vehicle after the correction and keeps it
  apart from the tick's positions; otherwise it would pass for steering in a tick in which the player lets go of the
  vehicle. That happened at a round trip of 2 s: the first position of a pig the player took over with a hotbar key
  reaches the server before the new slot, Paper corrected the pig (`was expected to be controlling vehicle`, then
  `moved wrongly`), and the last correction arrived in the tick the player handed the pig back.

Menu clicks happen on screens between the client's ticks and are applied right away. The client sends the slots a
click changed as hashes, so every click is checked: when the sandbox's items turn out to differ from the client's, it
marks them unknown and ClauAC has the server resend the player's inventory (Paper's `Player#updateInventory`), which
the sandbox takes over when it arrives. Until then the ticks note that the items may differ, but their movement and
actions are checked as ever, since a client can make any of its clicks differ (see "Disablers").

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
  state of a vehicle the player steers, the time the simulation spent on the tick (see below), the checks the tick
  failed (see "Responses") and notes.
- `MISMATCHED` ticks are logged to the server console with the checks they failed, and a summary is logged when the
  player leaves; so is every inventory resend.
- `/clauac debug` shows the outcome of every tick in your action bar with the checks it failed, `/clauac status`
  summarises all connections (see "Responses" for all commands).

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
over the eight test courses (walking, swimming, effects, flight, entities, menus, gliding, riding, pistons,
equipment):

- A client tick took 1.2 to 1.5 ms on average, depending on the course, and 2.2 ms in the first course after the
  server started, with a median of about 1 ms and a 99th percentile of 5 to 9.5 ms; single ticks took up to about 70
  ms. That is about 2.5% of one simulation thread per player. A Java Flight Recorder profile showed most of it going
  into ticking the entities and block entities the client knows, as the client itself does; 30 chickens next to the
  player added 0.2 to 1 ms per tick.
- Joining is the most expensive part: the configuration phase with the registries and the first chunks took about
  1.3 to 1.6 s of simulation time, which left the simulation up to 0.4 s behind for a moment.
- Snapshots of the player's state (about 300 objects, up to 420) are only taken in ticks with alternatives, such as
  every tick an item is used in the main hand, which then took 0.5 to 1.5 ms more. Taking one and restoring it took
  1.1 ms on average over the 70 of one connection, and 3 ms over the first 45 after the server started; taken every
  tick, as with `clauac.verifyRepeatedTicks=true`, 0.4 to 0.6 ms. That option, which repeats every tick from a
  snapshot, made a tick take 2.1 to 4 ms on average, and 5.5 ms in the first course after the server started.

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
  been a knockback attack, one that pushes its target and slows the player down, where the sandbox's was none, or the
  other way round. The sandbox's attack is then the one that is none, and the knockback attack is the alternative: at
  the player's tick it pushes the target and slows down the velocity the attack left, and then adds the pushes the
  player took from the entities that ticked ahead of it (`Entity.push`), which gives the client's velocity exactly,
  whatever else the tick's key handling did after the attack. In game no entity ticked ahead of the player: the client
  adds each entity behind its own player (`ClientLevel.tickEntities` goes in the order of adding), and anew when the
  server sends the entities again after a respawn. A target that ticked ahead of the player would already have moved
  without the attack's push, which would only matter to one that flies on with its velocity, a shulker bullet: the
  others the client hurts ignore pushes (item frames and the like), never move (end crystals) or drop their velocity in
  their tick (a vehicle the client does not steer).
- A start of breaking reports no hotbar slot (`MultiPlayerGameMode.startDestroyBlock`), and the slot a hotbar key
  selected in the same key handling reaches the server only at the start of the next tick, or never when another key
  selected the old slot again first. Where another hotbar item would have done something else with the block (shears
  break leaves at once where a stick starts breaking them; a sword breaks nothing in creative mode), the client may be
  in another world than the sandbox's: with other blocks, which it keeps predicted until the server acknowledges the
  start, and another mining state. The sandbox keeps each such world beside its own (`UncertainStart`) until the
  client shows which it is in: an action that only another world explains puts the sandbox in that world, and so does
  a tick whose movement matches only with that world's blocks, which the player's tick tries as an alternative. The
  server's acknowledgement and block updates reach the other worlds as they reach the client, and a world left with
  nothing that differs from the sandbox's goes away. An action that breaks blocks or changes the mining while worlds
  are still open, and would go on differently in each, takes the sandbox's world: the one of the item the server knows
  to be held.
- An attack on an entity the sandbox does not know fails `Hitbox`, since no vanilla client makes it (see "Attacks and
  interactions"). Where it is the last thing of the tick that changes the player, the attack slowing the player down
  is tried as well; otherwise the sandbox takes it for an attack that did nothing to the player.
- A riptide trident begins its use and throws its user only in water or rain (`TridentItem.use`, `releaseUsing`), and
  whether rain falls on the player depends on the sky light at its feet and at the top of its box
  (`Level.precipitationAt`, `Entity.isInRain`). The sandbox keeps no light, but the client's sky light is full
  exactly at and above each column's lowest sky light source (`SkyLightEngine`), which the chunks keep up to date with
  their blocks, so the sandbox answers from those (`SandboxLevel.canSeeSky`). The client, though, catches its light up
  with its blocks only once a frame (`ClientLevel.update`), and at least every ten ticks, and a chunk's light data and
  the server's light updates through a queue that each frame runs a tenth of (`ClientLevel.pollLightUpdates`): for up
  to 330 ticks after a column's lowest source moved or its chunk arrived, the client may still see the column as it
  was. Where a riptide trident's start depends on rain in such a column, the client's use may not have begun where the
  sandbox's did, or the other way round; every tick of the use tries the other state until a tick matches only with
  it, or the client's next item use, release or hotbar switch shows its state. Where the release depends on it, the
  release with the other outcome of rain is tried.

What cannot be tried that way leaves the tick `UNVERIFIED` instead of `MISMATCHED`:

- The alternatives above while the player rides, while blocks move next to it (a piston, a shulker box), which move it
  only after its tick, in a tick whose actions after the trident's release changed the player, and for an attack after
  which something other than a push changed the player's velocity or sprint before its tick: nothing in the client's key
  handling does, but an entity ticking ahead of the player could. The other worlds of a start of breaking stand for no
  uncertainty: where they cannot be tried, the tick is judged in the sandbox's world (see the known limits below).
- The client's velocity is never reported. After a difference the sandbox estimates it from the reported movement;
  the rounding of that estimate can move later positions by a few units in the last place, and after an `UNVERIFIED`
  tick, a difference that keeps shrinking in the ticks right after it stays `UNVERIFIED`.
- A switch that stopped an item use when the new item changes an attribute that moves the player (speed, gravity,
  scale, step height and the like), which the alternative leaves out.
- Teleports whose resulting position differs from the sandbox's in a coordinate the teleport gives relative to the
  player's own; one it sets outright has to be the teleport's (see "Setbacks"). A rotation that differs is the client's
  input and is taken over.
- With the experimental minecart movement, a minecart turns its rider only while the client's "rotate with minecart"
  option is on, which the server never learns. Placing a block or swinging at what the crosshair points at in such a
  minecart depends on the rotation the client had.

Known limits:

- An `UNVERIFIED` tick accepts any difference, and its movement reaches the server like that of a `MATCHED` one; only
  what the alternatives cannot cover (see above) is left to it.
- The rotation a boat's rider starts a tick with is exact up to the rounding of the float rotations it is computed
  from; the client's own boat adds such rounding at every frame (`AbstractBoat.clampRotation`), and no packet reports
  it. Only what the crosshair pointed at within that rounding of a boundary could differ; the rotation of an item
  use is checked through the boat's turn instead (see "Blocks and items").
- Not verified in game yet: riding in a boat another player steers.
- The client opens its own inventory screen without telling the server, and its creative inventory screen ignores
  cursor updates and keeps its own menu when the game mode changes. The sandbox cannot follow those; the differences
  show up in the next checked click and are resolved by the inventory resend.
- The `Inventory` check knows of the player's own inventory screen only once the client does something in it, never of
  the screens the client opens on its own, and after some screens of the server not whether a screen is open at all
  (see "Screens").
- A relative rotation packet whose answer the client computed from a rotation the sandbox has not seen yet is applied
  at the next pong instead.
- The other worlds of a start of breaking with a hotbar item the sandbox does not know (see above) are not tried while
  the player rides or blocks move next to it, and an action that breaks blocks or changes the mining while they are
  open takes the sandbox's world. A vanilla client in another world then fails: one that broke a block with another
  item in the same frame as the item's hotbar key and rode into the gap before the server's acknowledgement gave the
  block back. The data of a block entity the server sends reaches only the sandbox's world.
- A connection that was already playing when ClauAC started watching it (after a plugin reload) is not simulated:
  the simulation has to see a connection from its first configuration packet on.
- The tick budget (see "Cost and limits") only bounds what the simulation costs; the `Timer` check (see "Responses")
  finds a client that ends its ticks faster than its timer allows. A client whose timer runs up to 1% fast passes
  both, since clocks drift that far apart in neither's view.

### Disablers

An uncertainty explains any difference in the movement, so what leaves a tick `UNVERIFIED` has to be the client's
situation, never something a client can send at will: a client that could would keep its movement unchecked for as long
as it went on sending it, as the cheats called disablers do. Items the sandbox had to mark unknown after a click that
differed (see "Following the client's timeline") are therefore only noted in the ticks until the server's resend
arrives, and an attack or interaction on an entity the sandbox does not know fails `Hitbox` (see "Attacks and
interactions") and leaves the tick's movement checked; the alternative of such an attack stands for no uncertainty where
it cannot be tried. Before, both left the tick `UNVERIFIED`, and while the items were unknown the checks of the actions
only noted what they found. What still leaves a tick `UNVERIFIED` (see above) takes more than a packet: the player
riding or blocks moving next to it while an alternative is open, an entity ticking ahead of the player that moved it
after an attack, a relative teleport of the server, or a minecart with the experimental movement. Two more situations
did until the sandbox tried their outcomes: an attack whose strength depends on when a hotbar switch happened and after
which the tick's key handling went on, and a start of breaking, which never shows the item it was made with, where
another hotbar item would have broken the block otherwise (see above). A client could have sent either behind every tick
end.

In game, a proxy reported the positions of a walking player 2 blocks higher, as in the tests of the setbacks, and sent
something no vanilla client sends behind every tick end of the client, from a second before the shift on; it left the
client's answers to the corrections alone. With the old build, an interaction with an entity id no entity had left 88
of 89 ticks `UNVERIFIED`, and the server's position of the player rose to 102.0 and went on from 98.7 to 113.6 blocks
east. A click in the player's inventory that claimed an item on its empty cursor left 53 of 92 ticks `UNVERIFIED`, and
a charged attack on the unknown entity id with its swing and an item use after it, behind every sixth tick end while
the player sprinted, 13 of 96, which moved the server's position of the player up to 102.44. With the new build none
of these ticks was `UNVERIFIED`: the shifted ones failed `Simulation`, the interactions and attacks `Hitbox`, and the
server's position of the player stayed where it was. A single charged attack on the unknown entity id with its swing
failed `Hitbox` alone, and the movement of its tick matched as simulated.

Such a click also shows that the player's inventory screen is open (see "Screens"), so that with the `Inventory` check
every tick in which the walking player kept its keys after the first click fails `Inventory` as well, until the client
closes that screen. Left open, it made every tick of the test's later steps fail `Inventory`, which would also hide a
tick the sandbox was unsure of behind `MISMATCHED`; the test therefore has the proxy close the inventory once the
clicks stop, as a cheat whose later ticks are to pass would.

The test of the two situations reported the positions 2 blocks higher in the same way and left the client's answers to
the corrections alone. On every pass of the player sprinting under a boat, the proxy reported a switch from a stick to a
feather right behind a tick end and, five ticks later, an attack on the boat with its swing and an item use, whose
strength depended on whether the switch came a tick earlier; only the attack's tick was reported higher. With the old
build all 6 such ticks were `UNVERIFIED`, and their positions reached the server. While the player walked into oak
leaves with shears in its hotbar, the proxy sent a start of breaking the leaves behind every tick end; with the old
build the movement of 87 of 91 ticks went unchecked, only the starts failed `Hitbox`, and the server's position of the
player rose to 102.0. With the new build none of the 1891 ticks of the test's connection was `UNVERIFIED`: the 6
attack ticks and the 93 ticks at the leaves with shifted positions failed `Simulation`, the passes without a shift
matched, and the server's position of the player stayed at 100.0.

### Verified so far

With a real 26.3 client on Paper 26.3, every tick of the following produced `MATCHED` with an offset of exactly 0:
walking, jumping, sneaking, sprinting and sprint jumping, stairs up and down, sinking, swimming and leaving water, a 50
block fall into water, speed and jump boost effects, knockback from damage, a TNT explosion and a wind charge,
teleports, creative flight, gliding with an elytra and boosting with fireworks, cows and another player pushing the
player, walking into a boat and stepping onto it, a team whose collision rule stops those pushes, eating, blocking with
a shield and drawing a bow while walking, placing a block and walking into it, breaking blocks, and a sprint hit on a
boat and on another player. Riding matched as well, the vehicle included: steering a boat on water through turns and
leaving it, a horse walking, sprinting and making a charged jump, a camel walking and dashing, a pig steered with a
carrot on a stick and boosted, a strider on lava, a happy ghast flying up, forward and down, a nautilus swimming and
dashing, a minecart on powered rails, and a panicking pig the server moved, which the player took over with a hotbar key
and handed back the same way.

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

Climbing, blocks that slow the player and ice matched too: climbing a ladder and over the wall it hangs on,
stopping on it by sneaking and sliding down, jumping onto it and climbing with jump held, climbing vines and sliding
down them, climbing a scaffolding tower with jump held, jumping on its top and sneaking down through it, walking
through scaffolding on the floor, crawling through a tunnel one block high after the server closed a trapdoor above
the player's head, walking through sweet berry bushes, walking and jumping through a cobweb and falling into one,
sprinting over packed and blue ice and sliding on into a wall, sprint jumping on blue ice, and turning on packed ice.

So did fluids and the world around the player: rising in a bubble column over soul sand and out of its top, sinking
in one over magma and swimming against it, standing in, walking against and with flowing water, walking into a lava
pool and swimming out of it, walking through flowing lava, a riptide trident launching the player from water and in
the rain, ender pearls thrown standing and walking, walking and sprint jumping into the world border, whose collision
the client rounds out to whole blocks (`WorldBorder.getCollisionShape`), and a world border shrinking past a player
that walked into it. The riptide trident in the rain first failed: the sandbox, keeping no light, never saw rain on
the player, while the client launched. It now answers from the chunks' sky light sources (see "What the simulation
cannot know"). With a roof above a walking player taken away in the rain while it held the use button, the client's
use began a frame after the sandbox's in one of three runs, and the alternative of its use not having begun matched.

A round trip through a nether portal matched, with the player walking out of the portal it arrived in and back; the
ticks while the client loaded the terrain after each change of dimension are `NOT_SIMULATED`, as the client does not
tick its player then. With the client's auto-jump option on, the player walked onto a single block, up a staircase of
three steps and against a wall two blocks high without jumping at it, jumped onto a block while sprinting, and not while
sneaking: the client decides an auto-jump on its own (`LocalPlayer.updateAutoJump`) and reports only the jump in its
input. Levitation and slow falling are part of the fourth test course, over a connection with latency. With these four
courses added, the thirteen test courses matched in all of their 19 727 simulated ticks; the other 67 were the loading
of the terrain after the portal's changes of dimension. With the fixes for the worse connections below, the thirteen
courses matched again in all of their 19 802 simulated ticks, and in all 19 841 of another run in which every tick of
the player on foot ran a second time from its snapshot (`clauac.verifyRepeatedTicks=true`) and ended the same.

Menu clicks matched the client's hashes in chests, the player's inventory (crafting included), furnaces, stonecutters,
anvils (renaming included), villager trades and horse inventories, including shift clicks, number keys and dragging. A
switch from the creative inventory screen straight to survival made the client click in a menu the sandbox did not have;
the next click showed the difference and the inventory resend brought both back in line. The creative inventory keeps
what it picks up on the client's cursor alone, and that screen turns into the survival inventory with the cursor still
full: in two runs, such a stack put down by a click in the survival inventory left the sandbox's items differing from
the client's for three and two ticks, which ClauAC checks as ever since the fix of the disablers (see "Disablers");
these ticks and the walking and jumping after them matched. Switching the hotbar slot while eating matched through the
alternative that the switch stopped the item use a tick before the client reported it, which the next tick confirmed; a
sprint attack right after a hotbar switch matched through the attack strength that switch left, and an attack on an
entity id the sandbox did not know, injected into the connection, matched without slowing the player down; such an
attack fails `Hitbox` now (see "Disablers"). While the player was dead the sandbox, like the client, did not move it
(`NOT_SIMULATED`), and matching resumed after the respawn. The client's own tick removes its dead player 20 ticks after
the death (`LocalPlayer.tickDeath`), and that tick sends nothing, since `Minecraft.tick` lets only a player still in the
level send its changes (`LocalPlayer.sendChanges`). The sandbox compared that tick with what the player would have sent
all the same, which failed `Simulation` when the tick fell on the player's position reminder, as in a death of the
inventory test (see "Screens"); it takes that tick as not simulated now.

At a server tick rate of 40 (`tick rate 40`), the client still ends 20 ticks a second (`Minecraft.getTickTargetMillis`)
but moves the living entities it shows twice as fast towards the positions the server sends them
(`ClientLevel.getRelativeTickSpeed`, by which `SteppedInterpolationHandler` advances). A cow without AI that the server
teleported through the standing player in steps of a quarter block pushed the player the way the client showed in all
304, 312 and 318 ticks of three runs at that rate, as it did at the normal rate. Before the sandbox followed the client
in this, 16 and 13 ticks of two runs failed at the tick rate of 40, by 0.0015 to 0.0027 blocks, and none at 20.

Over worse connections the courses matched as well. A proxy between the client and the server held back what each side
sent: by 300 ms each way; by 1 s each way, a round trip of 2 s; by 60 ms and up to 120 ms more for every piece it read,
never letting a piece overtake the one before; by 40 ms and up to 20 ms more, while one in 500 pieces stalled its
direction for 0.2 to 1 s, as TCP does while it resends a lost segment; and by 30 ms, while one in 2000 pieces stalled
its direction for 3 to 6 s. Over these connections, courses 1, 2, 3, 5, 7, 8, 9, 10, 11 and 12 and the block and combat
tests matched in all of their 35 526 simulated ticks but the 46 with the cheats of those tests, which ClauAC refused as
over a direct connection. ClauAC held the client's packets for 2 to 9 ms on average. When a stall split the packets of a
tick, they waited for the rest of the tick, at most for `setbacks.maximum-hold-millis`; once in the long stalls that
time ran out and they went on unjudged, as they are meant to, and otherwise only the packets the block test lets go that
way on purpose did. The long stalls and the round trip of 2 s each showed a bug, both fixed since (see "Following the
client's timeline"): Paper disconnected the player for answering its keep-alives out of order, and the answer to a
correction of a pig the player had taken over passed for steering.

Paper's own checks acted a few times over these connections. After a stall of several seconds towards the server, the
client's movement of those seconds arrived at once; Paper measured all of it from where the player had stood at the
start of the server tick and teleported the player back (`moved too quickly`). Its vehicle checks corrected a pig the
player took over with a hotbar key, most of all over the round trip of 2 s and the long stalls, and logged a happy
ghast as `moved wrongly`, as they do over a direct connection. The sandbox followed every one of these corrections as
the client did.

The final runs with the check of the answers to teleports, the setbacks left out for ticks that ended before a teleport,
and the client's interpolation at a faster tick rate (see "Setbacks" and above) matched as well. The thirteen courses
matched in all of their 19 867 simulated ticks and in all 19 768 of another run in which every tick of the player on
foot ran a second time from its snapshot and ended the same, and the test of the tick rates, at 20 and at 40, matched in
all of its 727. Over the five connections above, the courses and the block and combat tests matched in all of their
36 463 simulated ticks but the 47 with the cheats of those tests, which ClauAC refused as over a direct connection. Over
a direct connection, ClauAC refused every cheat of the combat and block tests, and the tests of `NoSwing`, `Timer`, the
tick budget, alerts and setbacks, held and late, passed as before; while positions were shifted, the server's position
of the player did not change in either setback test, and all 67 shifted answers of the held one failed `Simulation`.

The final runs with the fix of the disablers (see "Disablers"), in which course 6 no longer sends an attack on an
unknown entity, matched as well. The thirteen courses matched in all of their 19 795 simulated ticks and in all 19 636
of another run in which every tick of the player on foot ran a second time from its snapshot and ended the same, and
the test of the tick rates matched in all of its 725. Over the five connections above, the courses and the block and
combat tests matched in all of their 36 427 simulated ticks but the 47 with the cheats of those tests, which ClauAC
refused as over a direct connection. Over a direct connection, ClauAC refused every cheat of the combat, block and
`NoSwing` tests, and the tests of `Timer`, the tick budget, alerts and setbacks, held and late, passed as before; the
server's position of the player did not change in either setback test, and all 66 shifted answers of the held one
failed `Simulation`. In the test of the disablers none of the 1114 ticks of its connection was `UNVERIFIED`: 261 of its
movement packets and 131 of its actions were kept from the server, and the server's position of the player stayed
where it was.

A vanilla client's starts of breaking with an item the sandbox could not know yet matched in all 2130 simulated ticks
of their check: oak leaves broken with a stick while shears lay in the hotbar; the shears' number key and a click in
one frame, after which the client broke the leaves at once and walked into the gap until the server's acknowledgement
gave them back, the tick into the gap matching only with the leaves the shears had broken; a switch to the shears while
the stick's click was held; and in creative mode a sword's number key and a click in one frame with an empty hand, where
the client broke nothing. Sprint attacks on a boat soon after a hotbar key selected a sword, with delays around the tick
at which the client's attack reached full strength a tick before the sandbox's, matched in all 4927 ticks, 5 of the
attacks only as the knockback attack of the other strength.

The final runs with both situations tried (see "Disablers") matched as well. The thirteen courses matched in all of
their 20 105 simulated ticks and in all 20 123 of another run in which every tick of the player on foot ran a second
time from its snapshot and ended the same, and the test of the tick rates matched in all of its 746. Over the five
connections above, the courses and the block and combat tests matched in all of their 37 436 simulated ticks but the 47
with the cheats of those tests, which ClauAC refused as over a direct connection. Over a direct connection, ClauAC
refused every cheat of the combat, block and `NoSwing` tests, and the tests of `Timer`, the tick budget, alerts,
setbacks, held and late, the disablers and the creative inventory passed as before: the server's position of the player
did not change in either setback test, all 50 shifted answers of the held one failed `Simulation`, and the test of the
disablers left none of the 1094 ticks of its connection `UNVERIFIED`.

The final runs with the `Inventory` check (see "Screens") and the tick that removes a dead player taken as not
simulated matched as well. The thirteen courses matched in all of their 20 098 simulated ticks and in all 20 121 of
another run in which every tick of the player on foot ran a second time from its snapshot and ended the same, and the
test of the tick rates matched in all of its 756. Over the five connections above, the courses and the block and combat
tests matched in all of their 37 601 simulated ticks but the 46 with the cheats of those tests, which ClauAC refused as
over a direct connection. Over a direct connection, ClauAC refused every cheat of the combat, block and `NoSwing` tests,
and the tests of `Timer`, the tick budget, alerts, setbacks, held and late, the disablers, the two situations and the
creative inventory passed as before: the server's position of the player did not change in either setback test, all 41
shifted answers of the held one failed `Simulation`, and the test of the disablers left none of the 879 ticks of its
connection `UNVERIFIED`. The vanilla client's uncertain starts of breaking matched in all 2029 ticks of their check and
its sprint attacks in all 4975. No tick of these runs failed `Inventory` but those of the imitated cheats of the test of
the screens and of the clicks in the test of the disablers.

## Responses

Every `MISMATCHED` tick names the checks it failed, each with what exactly failed; a tick can fail several at once.

| Check               | Failed when                                                                                  |
|---------------------|----------------------------------------------------------------------------------------------|
| `Simulation`        | The player did not move the way the vanilla client moves it with the same keys and rotation: |
|                     | its position, ground or collision state, sprinting, flying or the start of gliding differs,  |
|                     | or a position was sent where the vanilla client sends none, or the other way round           |
| `Vehicle`           | The vehicle the player steers did not move the way the vanilla client moves it               |
| `BadPackets`        | The client sent a packet no vanilla client sends in its situation, or one the sandbox could  |
|                     | not decode or apply (see "Results")                                                          |
| `TickRate`          | The client ended more ticks than the tick budget holds (see "Cost and limits")               |
| `Timer`             | The client ended its ticks faster than the timer of a vanilla client runs (see below)        |
| `Pings`             | The client left so many of the server's packets unconfirmed that the older half was applied  |
|                     | without its answers (see "Cost and limits")                                                  |
| `Inventory`         | The client moved the player with its keys or turned it with its mouse while a screen was     |
|                     | open, or did something in its inventory screen when it cannot have opened it (see "Screens") |
| `Reach`             | The client acted on an entity or a block farther away than the player or its weapon reaches  |
|                     | (see "Attacks and interactions" and "Blocks and items")                                      |
| `Hitbox`            | The client acted on an entity or a block its crosshair did not point at: one behind a block  |
|                     | or another entity, one beside where the player looked, one no crosshair meets, one the       |
|                     | server never showed the client, another face or point of a block; or it used an item facing  |
|                     | another way than the player                                                                  |
| `Interaction`       | The client acted when a vanilla client does not: while it used an item, paddled a boat or    |
|                     | broke a block, as a spectator, outside the world border, with an item that cannot do what it |
|                     | did, or with another hotbar slot in the middle of its key handling                           |
| `FastBreak`         | The client finished breaking a block before the vanilla client's breaking progress with the  |
|                     | same tool and effects reached the whole block (see "Blocks and items")                       |
| `NoSwing`           | The client left out the swing a vanilla client sends right after every attack and every      |
|                     | start, finish and turn of breaking a block (see "Swings")                                    |
| `SimulationFailure` | The simulation itself failed during the tick, so nothing the client sent in it was checked   |

Something the simulation cannot know (see "What the simulation cannot know") only ever explains a difference in the
movement: the tick is `UNVERIFIED` instead of failing `Simulation` or `Vehicle`. The other checks fail regardless.

`Timer` follows the least real time the client's timer can have reached. When the client answers one of the server's
packets (a pong, the acceptance of a teleport, a rotation, the acknowledgement of a configuration phase), it has
processed that packet, so its timer is at least at the time the server sent it; every tick it ends afterwards advances
the timer by a tick target (50 ms, or the server's slower tick rate while its level runs normally), less 1% for clocks
that run fast. A tick end cannot arrive before the client ended the tick, and a vanilla client never gets ahead of
real time: a frame counts the ticks it owes when it starts, handles the server's packets and then runs at most 10 of
those ticks, dropping the rest (`Minecraft.runTick`, `DeltaTracker.Timer`). The ticks right after an answer can
therefore be owed for up to 10 tick targets before the frame started. The frame also handles every packet that arrives
while it handles packets (`PacketProcessor.processQueuedPackets` runs until the queue is empty), which took up to
650 ms in test joins while chunks kept arriving, and the next frame owes up to 10 more ticks for that time. With the
tick the leftover fraction completes, the ticks after an answer can get 21 tick targets ahead of the real time since
the server sent the answered packet. A tick end that arrives more than 22 tick targets (1.1 s) earlier than the timer
can have reached therefore fails `Timer`, and the timer is put back to that limit, so that only a tick further ahead
fails next. A connection that stalls only makes the tick ends arrive later, and a client that holds back its answers
only keeps the timer from being moved up. An earlier limit of 12 tick targets, which counted only the first frame,
failed 3 of 4 test joins through a proxy, whose ticks got 600 to 618 ms ahead while the client loaded the world.

In game, with the player walking back and forth through a proxy, no tick failed `Timer` in normal play, nor while the
proxy held the client's packets for 25 seconds and then sent them at once, nor in four joins through a proxy that
traced them, whose ticks got 455 to 577 ms ahead at most. One extra tick end injected every 250 ms, a client about 20%
fast, failed `Timer` from 137 ticks (6.9 seconds) on, 50 times in 15 seconds, and once more right after it stopped.
1500 tick ends injected at once failed it 1502 times.

### Screens

A vanilla client lets go of every key and of the mouse as a screen opens (`Gui.setScreen` calls `KeyMapping.releaseAll`
and `MouseHandler.releaseMouse`; `ToggleKeyMapping.release` lets go of a toggled sprint or sneak as well), and while the
screen is open no key or mouse button reaches a key mapping (`KeyboardHandler.keyPress` hands the keys to the screen,
`MouseHandler.onButton` sets key mappings only without a screen) and the mouse turns nobody
(`MouseHandler.handleAccumulatedMovement` turns the player only while it grabs the mouse). A tick with a screen open
therefore reports no keys (`KeyboardInput.tick`, `LocalPlayer.sendChanges`) and the rotation of the tick before. The
one exception is the jump an auto-jump adds in the tick after it triggered (`LocalPlayer.aiStep`), which can fall into
the first tick of a screen: an auto-jump triggers only while the player moves (`LocalPlayer.canAutoJump`). The cheats
called inventory walk keep the keys and the mouse working with a screen open, so that the player can sort its inventory
or empty a chest on the move; their ticks fail `Inventory`.

ClauAC knows of two kinds of open screens:

- A container screen opens with the server's packet (`ClientboundOpenScreenPacket`, `MenuScreens.create`, and
  `ClientboundMountScreenOpenPacket` for a horse or a nautilus), which the sandbox applies at the pong behind it, so
  that it knows the first tick that ran with the screen open. The screen stays open until the client closes it, which
  every container screen does with a packet (`LocalPlayer.closeContainer`, from `AbstractContainerScreen.onClose` and
  `tick`, the anvil, beacon and lectern screens and `LocalPlayer.handlePortalTransitionEffect`), until the server
  closes it (`LocalPlayer.clientSideCloseContainer`) or until the player joins a level again, whose loading screen
  replaces it.
- The player's own inventory screen opens without a packet, in the key handling of a tick (`Minecraft.handleKeybinds`,
  which runs only without a screen, before the player's tick). Only what the client does in it shows that it is open: a
  click in the player's inventory menu (`AbstractContainerScreen.slotClicked` is the only sender of container clicks),
  a recipe placed from the recipe book (`RecipeBookComponent`) and an item picked from a bundle (`BundleMouseActions`,
  part of every container screen). From then on until the client closes it, the screen is open, and it was open in the
  tick before at the latest.

A tick fails `Inventory`:

- when it reports keys while a screen was open since it began, but for a lone jump in the first such tick;
- when its movement turns the player while a screen was open since the tick before; the client's answer to a teleport
  fails the same way when it turns the player while a screen was open since the client's last tick;
- when the client clicks, places a recipe or picks an item of a bundle in its inventory screen right after a tick that
  reported keys (a lone jump aside), since the key handling that opened the screen let go of them before that tick's
  player moved, or with no tick since its last screen closed, since that screen opens only in a tick's key handling.

The server turns the player without the mouse (`ClientboundPlayerLookAtPacket`, which the sandbox applies as
`ClientPacketListener.handleLookAt` does, a teleport and `ClientboundPlayerRotationPacket`), and so does a new player
after a login or a respawn; a tick after one of them is not checked for turns, and neither is a tick of a riding player,
whose vehicle turns it during the tick. With `setback` on, a tick that fails `Inventory` is set back like one that fails
`Simulation` (see "Setbacks"). The clicks themselves go on to the server: they are neither movement nor one of the
actions held for their tick, and a vanilla client clicks in an open screen as well.

Limits:

- A client that keeps its inventory screen open without doing anything in it shows nothing; the check finds it from its
  first click on. In creative mode the inventory screen sends no clicks, only the items it sets
  (`CreativeModeInventoryScreen.slotClicked`), and an item picked from a bundle is all that shows it.
- A client that lets go of its keys for the tick before each click in its inventory screen and closes the screen right
  after the click passes: a vanilla player as fast can open the screen in that tick's key handling, click and close
  it before the next tick.
- Screens the client opens on its own (the chat, the pause menu, the advancements and the like) are never known.
- The server's book, a sign's text, a dialog, a resource pack prompt and the death, credits and demo screens may replace
  the open screen: a book or a sign's text closes to no screen without a packet, while a dialog or a resource pack
  prompt brings back the screen it replaced (`ClientCommonPacketListenerImpl.clearDialog`, the prompt's parent screen).
  After one of them the sandbox does not know whether a screen is open until one opens or closes again, or until the
  client does something in its inventory screen.

In game, with a chest beside the course, a vanilla client walked against the chest until its screen opened and moved
the mouse over the screen; opened its inventory with the inventory key while walking and clicked in it three times; had
its chest screen closed by the server; was turned by the server three ways while the chest was open
(`rotate ... facing`, `rotate`, `tp ... facing`); got a dialog over the chest's screen and closed both, and another one
the server cleared (`dialog clear`); placed a sign and left its text screen; read a written book; opened the inventory
of the horse it rode while the horse walked; declined a resource pack the server pushed over the chest's screen
(`Player#addResourcePack`, from a plugin of the tests); placed a recipe from the recipe book of its inventory; opened
the inventory of a chest boat it steered while the boat turned, and stayed in that screen while the server dismounted
it; and died with the chest open, waited on its death screen and respawned with its button. Where it walked or rode
into a screen, it held the movement key until after that screen had closed. All 2547 simulated ticks of these steps
matched, and none failed `Inventory`; the 51 while the player was dead were not simulated. A proxy between the client
and the server then imitated the cheat:

- It kept the chest's screen from the client (it dropped the server's `open_screen` packet), so that the client walked
  on against the chest and turned, as a client whose keys and mouse a cheat keeps working does. 45 ticks failed
  `Inventory`: 43 for the movement key, 2 for a turn in the client's answer to a setback. With the player standing, its
  first turn failed `Inventory` in its movement and the next 2 in the answers to the setbacks.
- A click the proxy made in the player's inventory while the player walked failed `Inventory` as a click right after a
  tick with the movement key, and so did the 20 ticks with the key held until the proxy closed the inventory a second
  later. A click and a close behind every tenth tick end while the player walked failed at each of the 4 clicks, and a
  click right behind a close, with no tick in between, failed once.
- A click of the proxy while the player stood, closed a second later, matched in all 48 ticks: a vanilla client can do
  that.

The 21 setbacks kept the player where the server had it while the screen was open, and Paper's own movement checks saw
nothing.

### Attacks and interactions

A vanilla client attacks an entity only from `Minecraft.startAttack` and interacts with one only from
`Minecraft.startUseItem` (the only callers of `MultiPlayerGameMode.attack` and `interact`), both during its key
handling, before the player's tick. The simulation replays that key handling from the tick's packets and checks every
attack and interaction against what these methods allow:

- The target is the entity the crosshair pointed at. `Minecraft.pick` runs at the start of the tick, before the keys,
  from the camera with the rotation the tick's movement packet reports; the sandbox runs the same vanilla code, so the
  blocks and entities in front of the target count, and so do its pick radius and its position as the client
  interpolated it. When the crosshair did not point at the target, the tick fails `Reach` if the target lay out of
  reach even for a crosshair on it, and `Hitbox` otherwise.
- The client's own player looks along the yaw its mouse turned it to (`LocalPlayer.getViewYRot`), not along its
  head's yaw as every other living entity does (`LivingEntity.getViewYRot`), and so does the sandbox's player. The
  head takes up the player's yaw only during the player's tick, after the player moved (`Player.aiStep`), so a
  crosshair along it lags a tick behind a view that turns: before the sandbox's player looked along the yaw the same
  way, a vanilla client that broke blocks while it swept its view fast failed `Hitbox` (see "Blocks and items"). The
  same view turns the blocks a player places facing where it looks (`Direction.orderedByNearest`), which the sandbox
  predicts as the client does.
- The crosshair picks from the entities the client knows, which are the ones the sandbox knows: the sandbox applies the
  server's packets before the tick whose key handling came after them. An attack or interaction on an entity the
  sandbox does not know therefore fails `Hitbox`. The crosshair meets the parts of an ender dragon instead of the
  dragon, and the sandbox looks their ids up as the server does (`ServerLevel.getEntityOrPart`).
- The crosshair meets an entity's box, grown by its pick radius, closer than the player's entity interaction range,
  and a weapon with an attack range (`minecraft:attack_range`) attacks only where that point lies within its range.
  An interaction also needs the entity's box itself closer than that range (`Player.isWithinEntityInteractionRange`)
  and the entity inside the world border.
- The attack and use keys do nothing while the player uses an item or paddles a boat, and the use key nothing while
  it breaks a block. A spectator spectates an entity instead of attacking it. An item the level's features do not
  enable ends the key's handling, a piercing weapon (a spear) stabs instead of attacking, and a weapon charged less
  than its minimum attack charge does nothing.

Where the packets leave the client's situation open, the check takes whatever a vanilla client may have done:

- A hotbar switch the tick starts with may have happened before this tick's key handling or during it (see "What the
  simulation cannot know"). Where the old and the new item pick differently (an attack range), a crosshair on the
  target with either counts, and a use of the main hand that the switch may have stopped does not keep the keys from
  acting. The minimum attack charge is checked against every value the client's attack strength may have.
- The interaction ranges and the attack speed are the attributes the server sent: the client applies an item's
  attribute modifiers only when the server sends them (`LivingEntity.detectEquipmentUpdates` runs on the server), so a
  hotbar switch does not change them.
- In a minecart with the experimental movement, the rotation the player acted with is unknown (see "What the
  simulation cannot know"), and what the crosshair pointed at is not checked; the tick's notes say so.

Attacks and interactions are held like the movement (see "Setbacks"). An attack or interaction that fails `Reach`,
`Hitbox`, `Interaction` or `NoSwing` (see "Swings") with `setback` on never reaches the server; the tick's other actions
and its movement go on unless another check of the tick keeps them back. The client keeps what it did on its own when it
attacked (its swing, the end of its sprint), and an interaction that used up or changed the held item has the server
send the player its inventory. An attack that went on unjudged, because the simulation fell further behind than
`setbacks.maximum-hold-millis`, has reached the server and cannot be taken back. Paper's own check lets an attack or
interaction through within the player's range plus 3 blocks (`misc.client-interaction-leniency-distance` in
`paper-global.yml`) and does not look at the crosshair.

In game, a cow without AI and with 1000 health stood on the floor. Three attacks from 1.6 blocks away, one from 2.9
blocks and feeding it wheat matched, and the attacks took 4 of its health. A proxy between the client and the server
then sent the client's attack on the cow again where no vanilla client makes it: 4.56 blocks away (`Reach`), with the
cow behind the player and behind glass (`Hitbox`), while the player looked through a spyglass, paddled a boat (also
`Hitbox`: the crosshair pointed at the floor) or was a spectator (`Interaction`), and it sent the client's interaction
with the cow again while the player broke obsidian (`Interaction` and `Hitbox`). Each failed its check in exactly one
tick, and none of them reached the server: the cow kept its health, `/clauac status` counted 7 attacks and
interactions kept from the server and no setback, and two more attacks made the vanilla way took 2 health again. An
alert reads:

    [ClauAC] Tester failed Hitbox x1 attacked minecraft:cow (entity 1051) behind the block at 103, 101, 119, which the crosshair pointed at

A ninth test course attacks and interacts the vanilla way, and all of its 1506 ticks matched. It made 25 attacks: while
backing away from a cow until it was out of reach, on a cow the server moved every 100 ms as it crossed the crosshair,
right after hotbar keys, crouching 2.86 blocks away, in creative mode from 4.5 blocks, from a boat the player did not
paddle, and on an interaction entity, an armor stand, an item frame, a slime and a calf. It made 5 interactions:
milking a cow, shearing a sheep, naming and feeding the calf, and using the interaction entity. The eight other
courses matched in all of their ticks as well.

### Blocks and items

A vanilla client starts, finishes and turns while breaking a block only from `Minecraft.startAttack` and
`continueAttack` (`MultiPlayerGameMode.startDestroyBlock` and `continueDestroyBlock`), stabs only from `startAttack`,
and uses items on blocks and in the air only from `Minecraft.startUseItem` (`useItemOn` and `useItem`), all during its
key handling. The simulation replays these from the tick's packets like the attacks and checks each against what those
methods allow:

- Breaking acts on the block and face the crosshair points at, and never on air: another block or face fails
  `Hitbox`, a block out of the player's block interaction range `Reach`. A start leaves alone a block the game mode
  keeps the player from breaking (`Player.blockActionRestricted`) and one outside the world border. `startAttack`
  does nothing while the player uses an item as the key handling starts, and `continueAttack`, which runs after
  everything else in it, while the player uses one then; neither breaks blocks with a piercing weapon. These fail
  `Interaction`.
- A finish comes only from `continueDestroyBlock`, once the breaking progress reaches the whole block. The progress
  starts at the block's start and grows by the block's share for every tick the client goes on breaking it: the
  vanilla `BlockState.getDestroyProgress` with the tool, the effects (haste, mining fatigue), the ground and water the
  player is in, and the attributes the server sent (block break speed, mining efficiency, submerged mining speed). It
  waits out 5 ticks after a finished break and needs the item the block was started with; in creative mode a start
  breaks the block at once. A finish before any of that fails `FastBreak`. The ticks the client goes on breaking are
  the ticks whose key handling ends with `continueAttack`'s swing: once per tick and after everything else, so the
  extra swings of clicks, or of a client that sends more, add no progress.
- An item used on a block carries the crosshair's block hit as the client encodes it (the point as floats relative to
  the block), so the block, the face and the point have to be exactly the crosshair's; anything else fails `Hitbox`.
  The use key does nothing while the player uses an item, paddles a boat or breaks a block, or outside the world
  border, and a hand holding an item the level's features do not enable ends the key's handling (`Interaction`).
- An item used in the air carries the player's rotation of that moment, which nothing changes before the tick's
  movement reports it: another rotation fails `Hitbox`, as when a client throws a potion or an ender pearl facing one
  way while it looks another. A boat turns its rider after the player's tick and keeps its yaw within 105 degrees of
  its own (`AbstractBoat.positionRider` and `clampRotation`); for a rider the sandbox turns the yaw the item was used
  with by the boat as the tick left it, which has to arrive where the client reported its rotation, up to the rounding
  of those float rotations. A spectator and an empty hand use no item (`Interaction`).
- The hotbar keys come first in `Minecraft.handleKeybinds`, and nothing else changes the selected slot during a tick.
  The first action that calls `MultiPlayerGameMode.ensureHasSentCarriedItem` reports the slot right before itself, so
  a vanilla client reports at most one slot after the tick's first packet, and none after such an action. Selecting
  another slot between two actions of the same tick, as a client does that throws a potion and switches back at once,
  fails `Interaction`.

Where the packets leave the client's situation open, the checks take whatever a vanilla client may have done:

- A start of breaking from `startAttack` goes out before the tick reports a hotbar key of the same key handling
  (`startDestroyBlock` does not call `ensureHasSentCarriedItem`), and the slot may be reported only by a later tick, or
  by none when it changed back meanwhile. Unless the tick shows the slot, the start may have come with any hotbar item:
  a check that depends on the item fails only when no hotbar item passes it. Where another hotbar item would have
  broken the block at once and the sandbox's does not, or the other way round, the client's block stays unknown until
  the server acknowledges the start: what the crosshair met along a sight line through it, a movement past it and the
  finish of a break are noted instead of checked meanwhile.
- While the sandbox's items differ from the client's (see "What the simulation cannot know"), no action is checked;
  the tick's notes say what each check would have found.

An action that fails `Reach`, `Hitbox`, `Interaction`, `FastBreak` or `NoSwing` with `setback` on never reaches the
server, like a failed attack, also when the hold let go of earlier packets of its tick unjudged: only an action that
went on unjudged itself has reached the server, which answers it. The client predicted what its block actions did and
keeps that until the server acknowledges the prediction (`MultiPlayerGameMode.startPrediction` numbers them), which the
server never does for a packet it never received: ClauAC acknowledges the latest prediction of the actions it kept from
the server in the server's stead, in one of its own bundles, and the client takes back what it predicted
(`ClientPacketListener.handleBlockChangedAck`). After an item used on a block or in the air is refused, the server sends
the player its inventory. The simulation still performs a refused action as the client did, since the client went on
from it: after a refused finish it waits out the delay after a break as well.

In game, the player stood before a stone wall with a diamond pickaxe, stone, snowballs, a wooden pickaxe, an iron
spear and shears in its hotbar. It mined the stone, placed stone on the floor, threw a snowball, pressed a hotbar key
together with the attack button and mined on, clicked a stone block five times, stabbed with the spear, broke
persistent leaves with its bare hand, and held the attack button on bedrock four times; all of those ticks matched, and
the server broke, placed and threw what the client did. The leaves are the open case above: their start came in a tick
that reported no slot, and the shears would have broken them at once, so the three ticks until the server acknowledged
the start matched with a note that the client may have broken them otherwise. A proxy between the client and the
server then sent block actions and item uses no vanilla client sends, each failing its check in exactly one tick while
the server changed nothing:

- a start and finish on a block behind the player (`Hitbox` and `FastBreak`) and on one 5.5 blocks away (`Reach` and
  `FastBreak`), a start and finish on the block the crosshair pointed at in one tick (`FastBreak` at 17.8% of the
  progress), the same with 8 swings in between (`FastBreak` at 17.8% again: the swings added nothing), and a start on
  air (`Hitbox`);
- stone placed against the floor behind the player and at a point of the floor's top 0.57 blocks from where the
  crosshair met it (`Hitbox`, with the points 0.1000, 1.0000, 0.9000 and 0.5001, 1.0000, 0.4999);
- a snowball thrown facing straight down while the player looked level (`Hitbox`; the server kept all snowballs), and
  a snowball thrown with a switch to the snowballs before it and back to the pickaxe after it in the same tick
  (`Interaction` for the switch back; the throw itself went through);
- a stab with the diamond pickaxe (`Interaction`: it is no piercing weapon);
- stone placed against the floor behind the player once more, in a client tick whose rest the proxy kept back for 1.6
  seconds after sending the hotbar slot the player held: the hold let go of the slot unjudged after a second, and the
  placement that came after it failed `Hitbox` and was kept from the server all the same. Against a build that refused
  nothing in such a tick, the same test had the server place the stone.

Last, the proxy hid the client's switch from the wooden to the diamond pickaxe from the server and ClauAC. The client's
own break of a stone block then failed `FastBreak` at 26.7% of the progress the wooden pickaxe makes, twice while the
button was held, the server kept the block, and the client showed it again. `/clauac status` counted 15 actions kept
from the server, 11 block predictions taken back and one time the packets went on unjudged. An alert reads:

    [ClauAC] Tester failed FastBreak x1 finished breaking minecraft:stone at 123, 101, 111 at 26.7% of its breaking progress

With these checks, the nine test courses matched in all of their 14 378 ticks, and the attacks and interactions of
"Attacks and interactions" failed and were kept from the server as before.

A player reported that a vanilla client failed `Hitbox` when it broke blocks while it turned its view fast. In game,
the vanilla client held the attack button on a wall of stone 2.5 blocks away while the mouse swept its view across the
wall and past its ends, 60 degrees in 0.3 seconds and back, in survival mode with a diamond pickaxe and with its bare
hand, in creative mode, up and down, and twice as fast. Sideways it failed `Hitbox` in 45 of 1181 ticks of one run and
in 54 of 1180 of another, up and down in none. A proxy that recorded the client's rotations and block actions showed
that each start of breaking that failed was on the block the crosshair met along the yaw the same tick reported, while
the sandbox's crosshair met the block along the yaw of the tick before: the sandbox's player looked along its head's
yaw, which took up the reported yaw only during the player's tick (see "Attacks and interactions"); the pitch, which
the view follows directly, never lagged. Since the sandbox's player looks along its yaw as the client's does, all 1170
ticks of the same test matched.

### Swings

The 26.3 client has no packet for the swing of its arm. It sends `ServerboundPunchPacket` (`minecraft:punch`, which
carries nothing) instead, and the server swings the player's arm for the other players when it arrives
(`ServerGamePacketListenerImpl.handlePunch`, which also starts the player's attack strength over; Paper calls
`PlayerArmSwingEvent` there as well, and a `PlayerInteractEvent` for a click at the air where its own ray trace meets
nothing). `Minecraft.startAttack` sends it after every click of the attack button, right after the attack or the start
of breaking the click made, with only the client's own swing of the arm in between (`LivingEntity.swing`, which sends
nothing on the client). `continueAttack` sends it after every tick in which `continueDestroyBlock` went on breaking,
right after the finish, the turn, or the start on another block or in creative mode that come from there. An attack
comes only from `startAttack` (`MultiPlayerGameMode.attack` is its only sender, and `startAttack` the only caller of
that), and the block actions only from these two methods, so a vanilla client follows every attack and every start,
finish and turn of breaking with a swing as the tick's next action. One without it fails `NoSwing` and, with `setback`
on, never reaches the server, like the actions above. A stab with a piercing weapon
(`MultiPlayerGameMode.piercingAttack`) and an abort come without a swing. The server swings the arm for a stab itself
(`PiercingWeapon.attack`), as it does for the use key's item uses and interactions
(`LivingEntity.swingAndResetAttackStrength` in their handlers), so a client can hide none of those swings. The check
needs nothing but the order of the tick's packets, so it applies also while the sandbox's items differ from the
client's.

Leaving the swing out makes no attack stronger: `Player.attack` starts the attack strength over itself
(`Player.onAttack`). What it hides is the swing: the other players do not see the player attack or break, and plugins
that listen for `PlayerArmSwingEvent` do not hear of it. The ticks between the start and the finish of a break send the
swing alone, and those swings are what tells the simulation the breaking progress (see "Blocks and items"): a client
that leaves them out finishes before the progress it showed, which fails `FastBreak` as well. A click that meets nothing
sends the swing alone, too. A client that leaves that one out sends nothing at all, which no check on the server can
see; the server then does not start the attack strength over as it does for a vanilla client's miss.

In game, a proxy between the 26.3 client and the server dropped the client's swings for a while, as a NoSwing cheat
does, while the player attacked a cow without AI, mined stone with a diamond pickaxe, held the attack button on bedrock
and broke a slime block, which breaks at once; the proxy also sent an attack on an entity id nobody had, without a
swing. Nine actions failed `NoSwing`, each in its own tick: the attack; the starts of breaking the stone, the bedrock,
the slime block and the floor behind it, which the crosshair met through the slime block the client had just broken
while the button was still down; the two finishes of the stone, which failed `FastBreak` as well; the turn to another
face of the stone, which `continueDestroyBlock` sends in the first tick it goes on breaking a block after a finish, as
the client did once the stone came back; and the attack on the unknown entity. None of them reached the server: the cow
kept its health, the stone and the slime block stayed, and `/clauac status` counted 9 actions kept from the server and 6
block predictions taken back. With the swings, before and after, every tick matched and the server applied what the
client did. Since the fix of the disablers, the attack on the unknown entity fails `Hitbox` as well (see "Disablers").
An alert reads:

    [ClauAC] Tester failed NoSwing x1 attacked minecraft:cow (entity 375) without the swing a vanilla client sends right after it

The combat and block tests now send the swing a vanilla client sends after each attack and block action they inject,
and each of their steps failed exactly the checks it failed before; course 6 sent its attack on an unknown entity with
that swing as well, which the test of disablers sends now (see "Disablers"). With the check, the thirteen courses
matched in all of their 19 719 simulated ticks, and the tests of `Timer`, the tick budget, alerts and setbacks, held
and late, passed as before.

### Setbacks

A tick that fails a check whose `setback` is on (every check but `SimulationFailure` by default) is set back: its
movement never reaches the server, and the client is put back where the server has the player. The checks of the
actions (`Reach`, `Hitbox`, `Interaction`, `FastBreak`, `NoSwing`) move nobody: their `setback` keeps the action that
failed from the server instead (see "Attacks and interactions", "Blocks and items" and "Swings").

- The server applies the client's movement only once the simulation has judged its tick. From a tick's first movement
  packet on (a position, rotation or ground update of the player, or a vehicle position), or from its first action (an
  attack, an interaction with an entity, a block action, an item use or a hotbar slot), ClauAC keeps the client's
  packets from the server until that tick's verdict, and then
  lets them go on in the order the client sent them. A packet that is neither goes on right away while nothing is
  held, and waits behind what is held otherwise, so that the server gets everything in its order. The packets go on
  from PacketEvents' decoder, past every packet listener, as if they arrived just then.
- The movement packets of a failed tick are thrown away, and so are those of every tick after it until the client has
  taken a correction: a teleport to where the server has the player, which keeps the client's own rotation and gives
  it the velocity it had when the failed tick began (none when the server has the player elsewhere by now). When the
  player steers a vehicle, the correction puts the vehicle where the server has it, as the server does after a vehicle
  moved wrongly, and stops it: that packet carries no velocity, and the client's and the simulation's would differ
  otherwise. The correction goes out in one of ClauAC's own bundles, so that the pong to the ping behind it shows when
  the client has taken it; the client's answer to ClauAC's teleport goes no further than the simulation. The server
  is not involved at all: it never saw the movement that was thrown away.
- The client answers a teleport with its acceptance, which carries its resulting position
  (`ClientPacketListener.handleMovePlayer`), and a movement cheat that changes the positions the client sends changes
  that one too. A coordinate the teleport sets outright is the teleport's on every client
  (`PositionMoveRotation.calculateAbsolute`), so an answer that differs in it fails `Simulation`, and the coordinate
  stays the teleport's in the sandbox, which the next tick starts from; only a coordinate the teleport gives relative
  to the player's own is taken over (see "What the simulation cannot know"). Otherwise the answer to a correction would
  carry the cheat's position past it.
- A tick that fails after the client has taken the correction gets a setback of its own, since the simulation went
  on from where the correction put the player. A tick the client played before it took the correction needs none; the
  correction puts the client back anyway. Neither does a tick that ended before a teleport of the server went out,
  the server's own or one of the setbacks below: the client played it before it could take that teleport, which puts
  it somewhere anyway, while the simulation began the tick where the tick before had left the player.
- When the simulation falls behind, the packets go on unjudged once the oldest has waited `setbacks.maximum-hold-millis`
  (a second by default), or once more than 2048 packets or 4 MiB are held. The packets of a connection that started
  while the vanilla runtime was starting are never held, since the simulation does not see all of them. The server
  then applies movement that may turn out to fail. Such a setback teleports the player back to where the failed tick
  began, on the server (`Player#teleport`, cause `UNKNOWN`), unless the server put the player somewhere itself after
  that tick began (a teleport or a respawn), which the setback would undo, or such a setback of an earlier tick
  teleported the player back after this tick ended (see above). A plugin can cancel that teleport; the log says so
  then. Vehicle movement that reached the server this way stays: the vehicle is only put back where the server has it.
- A dead or sleeping player is not set back, and neither is a rider that did not steer its vehicle, whose position the
  server decides.

Every setback is logged. `/clauac status` adds a line per connection with the packets held so far and for how long,
the movement packets and the actions kept from the server, the block predictions taken back, how often packets went
on unjudged, and the setbacks by kind.

Setbacks were tried in game with a proxy between the client and the server that shifted the positions in the
client's movement packets for three seconds while the player moved on, as a movement cheat would, and with the
server's own position of the player sampled with `data get entity` in the meantime:

- Walking east with positions reported 2 blocks higher, the ticks with a shifted position failed `Simulation` and the
  client was put back again and again: for the whole three seconds of walking it got no further than 0.12 to 0.87
  blocks from where the server had it, and the server's position did not change. With positions reported 4 blocks
  ahead the client stayed 0.10 to 0.63 blocks from it. A boat steered east and reported 3 blocks ahead failed
  `Vehicle` and stayed 0.04 to 0.54 blocks from where the server had it.
- Paper's own movement checks, which log a player or vehicle that "moved too quickly" or "moved wrongly", never saw
  any of it. Once the shift stopped, the next tick still failed (the simulation had continued from the last shifted
  report) and every tick after it matched again, the boat's included.
- In the eight test courses (see "Cost and limits"), which all matched as before, 13 582 packets were held, 2.2 ms on
  average and 77 ms at most, and no movement was kept from the server.
- With `setbacks.maximum-hold-millis` set to 1, the packets went on unjudged 126 times and the server did get the
  shifted positions; the setbacks were then 51 teleports on the server back to where the failed tick began, 14
  corrections where the verdict came first after all, and one left out because the previous teleport was still on its
  way to the client.
- The client answers every correction with its acceptance, which carries its resulting position, and the proxy
  shifted those as well, as a movement cheat would. While the sandbox took over a position that differed there, the
  answers carried the shift past the setbacks: the server's position of the player rose to 102.6 while positions were
  reported 2 blocks higher, and went from 98.5 to 116.3 while they were reported 4 blocks ahead. Since such an answer
  fails `Simulation` (see above), all 56 shifted answers of the same test failed it, and the server's position did not
  change in either part; the setbacks were 58 teleports and 34 corrections of the boat, which kept 388 movement packets
  from the server, and Paper's movement checks saw nothing.
- With `setbacks.maximum-hold-millis` at 1 and the answers left alone, a tick the client had played before it took a
  teleport of the server back got a setback of its own, back to where the simulation had begun that tick: the report
  of the tick before, shifted 4 blocks ahead. That moved the server's position of the player from 98.5 to 102.6 in one
  of two runs. Such ticks are left out now (see above), and in three runs the server's position stayed where it was;
  7, 3 and 2 setbacks were left out as ticks that ended before a teleport of the server went out.

### Alerts

Players with the permission `clauac.alerts` see in their chat when a player fails a check: from the moment they join
while `alerts.on-join` is `true` (the default), and otherwise once they turn alerts on with `/clauac alerts`, which
turns them off again as well. There is at most one alert per player and check within `alerts.interval-millis` (a second
by default); the flags in between are counted into the next alert. With the default format an alert reads:

    [ClauAC] Tester failed Simulation x677 differs in: expected a position, none was sent

where `x677` counts the flags of that player and check since its previous alert, this one included. The console logs
every `MISMATCHED` tick with the checks it failed either way.

### Commands and permissions

| Command          | Permission      | Does                                                                    |
|------------------|-----------------|-------------------------------------------------------------------------|
| `/clauac alerts` | `clauac.alerts` | Turns your alerts on or off                                             |
| `/clauac debug`  | `clauac.admin`  | Shows the outcome of every tick of your own connection, with the checks |
|                  |                 | it failed, in your action bar                                           |
| `/clauac status` | `clauac.admin`  | Summarises the simulation of every connection (see "Cost and limits")   |
|                  |                 | and its setbacks                                                        |
| `/clauac reload` | `clauac.admin`  | Reads `config.yml` again                                                |

Both permissions default to operators, and `clauac.admin` includes `clauac.alerts`.

### Configuration

The first start writes `plugins/ClauAC/config.yml` with the defaults and a comment on every setting; `/clauac reload`
reads it again while the server runs.

| Setting                        | Default     | Meaning                                                                    |
|--------------------------------|-------------|----------------------------------------------------------------------------|
| `alerts.on-join`               | `true`      | Players with `clauac.alerts` get alerts as soon as they join               |
| `alerts.interval-millis`       | `1000`      | At most one alert per player and check within this many milliseconds       |
| `alerts.format`                | (see above) | The alert in MiniMessage, with `%player%`, `%check%`, `%detail%`, `%tick%` |
|                                |             | (the client tick) and `%count%` filled in                                  |
| `setbacks.maximum-hold-millis` | `1000`      | How long the client's packets wait for their tick's verdict at most        |
| `checks.<name>.alert`          | `true`      | Whether failing the check of that name (see the table above) alerts        |
| `checks.<name>.setback`        | `true`      | Whether failing it sets the player back; `false` for `SimulationFailure`,  |
|                                |             | a failure of ClauAC's own that says nothing about the client. For `Reach`, |
|                                |             | `Hitbox`, `Interaction`, `FastBreak` and `NoSwing` it keeps the action     |
|                                |             | that failed from the server instead, and nobody is moved                   |

The format is [MiniMessage](https://docs.papermc.io/adventure/minimessage/format/) text. The filled-in values are
escaped, so that a detail with a `<` in it shows as it is instead of becoming a MiniMessage tag. A value ClauAC cannot
use, such as text where a number belongs or a negative interval, is replaced by its default, and a check name ClauAC
does not know is ignored, each with a warning in the log. A setting missing from the file takes its default, so that
the `config.yml` of an earlier version keeps working. The alerts go out through Paper's `String` API
(`Player#sendRichMessage`), so no adventure object crosses into Paper (see "Rule for ClauAC's own code").

### For other plugins: `ClauACFlagEvent`

ClauAC calls `io.github.hellotta.clauac.api.ClauACFlagEvent` once for every flag of a tick, before it responds to it:
for every check the tick failed, and once more for every other way it failed the same check (two packets no vanilla
client sends, or a teleport answered with another position and a tick's own movement that differs). The event holds the
player, the check (`io.github.hellotta.clauac.simulation.api.Check`), the detail and the client tick; cancelling it
keeps ClauAC from responding to that flag: it does not alert, and it sets the player back or keeps the failed action
from the server only when another flag of the same tick that nobody cancelled asks for it. The event is asynchronous:
the simulation thread that finished the tick calls it as soon as the result is known, with ClauAC's plugin class loader
as the thread's context class loader. A listener therefore has to be quick and must hand anything that touches the world
or the player's state to the server thread. A plugin that listens for it depends on ClauAC in its `plugin.yml`
(`depend: [ClauAC]`), so that it loads after ClauAC and sees its classes:

```java
@EventHandler
public void onFlag(ClauACFlagEvent event) {
    // - Leave the tick rate of this server's own test bots to them -
    if (event.getCheck() == Check.TICK_RATE && event.getPlayer().getName().startsWith("Bot")) {
        event.setCancelled(true);
    }
}
```

A test plugin that logged every event and cancelled those of `BadPackets` showed this in game: three pongs injected for
pings the server never sent failed `BadPackets` and brought no alert. 1500 tick ends injected at once brought a
`Simulation` alert, a second one a second later that counted the 677 flags since, and a `TickRate` alert. After
`checks.Simulation.alert` was set to `false` and `/clauac reload`, 300 more brought a single `TickRate` alert, which
counted 310 flags. All 1808 events came asynchronously on the simulation threads with ClauAC's class loader as their
context class loader.

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
