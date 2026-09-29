# ClauAC

Predictive (simulation-based) anticheat plugin for Paper 26.2, built on PacketEvents. This branch is the 26.2 build;
the main branch targets 26.3. Read [README.md](README.md) first: it lists the target versions, what the 26.2 client
does differently from 26.3, and how PacketEvents and adventure are bundled, including the rule that ClauAC code must
never pass adventure objects to or receive them from the Paper API.

## Never write an API call from memory

Every API call, field and behaviour the code relies on is checked against the actual source or binaries of the exact
version on the classpath (see [`gradle/libs.versions.toml`](gradle/libs.versions.toml)), never assumed.

- **Plugins:** read the source of Paper and of every library called. Sources jars exist for the pinned versions, e.g.
  `paper-api-<version>-sources.jar` on https://repo.papermc.io/repository/maven-public/ and
  `packetevents-{api,spigot}-<version>-sources.jar` on https://repo.codemc.io/repository/maven-releases/. Where the
  behaviour lives in the server implementation rather than the API, read the Paper server source or jar.
- **Mods:** get the mod loader's source code and check its API directly in the same way.
- **Vanilla behaviour** (movement physics, collisions, packet handling, ...): read it in the real game jars, both
  client and server, plus sources jars when they help. The jars are listed in the version JSON reachable from Mojang's
  version manifest (https://piston-meta.mojang.com/mc/game/version_manifest_v2.json). Minecraft 26.2 is **not
  obfuscated**: the client jar, the vanilla server jar and Paper's server jar all use the real `net.minecraft` names,
  so classes can be read directly, e.g. `javap -p -c -cp <jar> net.minecraft.world.entity.LivingEntity`.
- **Other plugins or mods as dependencies:** clone that product's source code and check the exact version that is
  depended on.

## Rules for the simulation module

- Code in `simulation` runs inside the isolated vanilla runtime (see README): it may use `net.minecraft` and the
  libraries of the vanilla server, never Paper, Bukkit or PacketEvents. The plugin and the simulation only exchange
  the types in `simulation-api` (plain Java types and raw packet bytes).
- The client jar is not on the build's class path. Client-only behaviour is ported from the 26.2 client jar (read it
  with `javap` or a decompiler such as Vineflower) and each port names the class or method it comes from.
- Ports keep the client's order of operations. Leave out only what renders, plays sounds or shows screens, and say in
  a comment what was left out. Anything the sandbox cannot reproduce must be reported (`UNVERIFIED` with a note), never
  approximated silently.

## Verify by running it

A successful build is not verification. For every change:

1. Build with `./gradlew build`.
2. Start a real server in this environment (`./gradlew runServer`) and check that the plugin loads and the log shows no
   new errors or warnings.
3. Test the change in game with a real 26.2 client whenever it involves players, movement, combat or packets.
4. Check the result visually: take screenshots, and record video when motion or timing matters, then look at them.

## Real client in a headless container

Findings from running the official 26.3 and 26.2 clients in a cloud container without a GPU or sound card:

- **Display:** Xvfb with Mesa's software renderer (llvmpipe). The 26.2 client runs its OpenGL backend there. The 26.3
  client fails to create its OpenGL backend on Xvfb ("Couldn't find matching GLX visual") and falls back to Vulkan,
  so `mesa-vulkan-drivers` must be installed for it. On a 4-core container the 26.3 client rendered about 10 distinct
  frames per second, the 26.2 client about 5.6 (0.18 s apart on average, up to 0.27 s).
- **Joining the dev server:** launch the client with an offline identity and set `online-mode=false` in
  `run/server.properties`. The `server.properties` Paper 26.3 generated here had `white-list=true`, the one Paper 26.2
  generated `white-list=false`; add the test player with `whitelist add <name>` so that it joins either way.
- **Input and screenshots:** `xdotool` for keyboard and mouse, `import -window root <file>.png` for screenshots.
- **Sound:** run PulseAudio with a `module-null-sink` and start the client with `ALSOFT_DRIVERS=pulse`; the sink's
  `.monitor` source then carries the game audio.
- **Recording:** ffmpeg `x11grab` plus `pulse` from the monitor source, encoded with `libx264 -preset ultrafast` so
  capture keeps up in real time. Measured on 26.3 gameplay, re-encoding the finished recording with
  `-preset medium -crf 21` gave an equal or slightly higher SSIM at about half the file size. Check the audio of every
  recording (for example with ffmpeg's `volumedetect`); a fully silent track measures about -91 dB.
- **Test world settings:** game rule ids are snake_case in 26.2 and 26.3 (`log_admin_commands`, `advance_time`,
  `drowning_damage`). Set `broadcast-console-to-ops=false` in `run/server.properties` for recordings, otherwise console
  commands fill the chat and cover the action bar. A test player left idle under water drowns, so turn
  `drowning_damage` off on the test world. Phantoms come for a player that has gone more than 72000 ticks without
  sleeping whenever the sky is dark enough (`PhantomSpawner`), which a thunderstorm makes it even at a stopped noon, and
  they killed an idle test player: turn `spawn_phantoms` off, clear the weather and turn `advance_weather` off.
- **The nether:** resistance 255 takes the damage of an attack away, not its knockback: a piglin that attacked an idle
  test player twice right beside the portal it had arrived through pushed it out of that portal. In a peaceful level no
  mob takes a player for its target (`LivingEntity.canAttack`); piglins stay in a peaceful level, most other monsters
  leave it (`EntityType.isAllowedInPeaceful`). Paper keeps a difficulty for every level, which the `difficulty` command
  reads and sets for the level it runs in: `execute in minecraft:the_nether run difficulty peaceful` makes the nether
  peaceful alone. Chunks that load in a peaceful level drop the monsters stored in them that do not stay there, and the
  server logs a warning `Skipping Entity with id <type>` for each (`EntityType.canSpawn`).
- **Console commands:** `~ ~ ~` in a console command means the console's position (the world spawn); run relative
  commands through the player, e.g. `execute as Tester at @s run summon minecraft:cow ^ ^ ^2`.
- **Aiming the test player:** `tp ... facing` and `rotate ... facing` turn the player from the command source's anchor,
  which is the feet for the console (`LookAt`, `ServerPlayer.lookAt`), so a player told to face a cow's eyes looks well
  above it. Aim from the eyes with `execute anchored eyes run tp Tester <pos> facing <point>`. `rotate` turns a riding
  player without taking it off its vehicle.
- **Key presses:** `xdotool key` releases the key within the same client tick, which the client's per-tick key polling
  can miss (a double tap of jump to fly never registers). Hold keys with `keydown`, `sleep 0.1`, `keyup`.
- **Hotbar keys:** the client takes in keys once a frame and handles the number keys that came since in the order of
  their slots, not of the presses (`Minecraft.handleKeybinds`): two number keys pressed within one frame select the
  higher slot. It reports the slot at the start of the next tick (`MultiPlayerGameMode.tick` runs before
  `handleKeybinds`) or with the first action that needs it, so an action a proxy injects right after a number key goes
  out with the slot before. Wait a second after a number key before anything that depends on it. A start of breaking
  does not report the slot (`MultiPlayerGameMode.startDestroyBlock`): `xdotool keydown <n> mousedown 1` starts breaking
  with the new item a tick before the server learns of it. The server keeps a player's slot, over restarts too, and
  gives it to the client when it joins, so a test finds the slot an earlier test left: one that depends on the held
  item selects its slot with its key first. A slot left that way once made a proxy's switch to it no switch.
- **Typing into the chat:** the chat key opens the chat only in the client's next tick, and the keys typed until then
  act as key bindings. Right after a 26.2 client joined, a press of the chat key opened nothing: the command typed
  after it never reached the server, and its `l` opened the advancements. Commands typed right after a 26.3 client
  joined went missing the same way, with no screenshot taken. Type only once a screenshot shows the chat's input line,
  a black box at half opacity along the bottom of the screen, and press the chat key again when it does not show.
- **Stopping the client:** `pkill -f <pattern>` also matches the shell that runs the command when the pattern appears
  in it, and kills that shell. Kill the client by the PID of its `net.minecraft.client.main.Main` process instead.
- **Simulation results:** `run/plugins/ClauAC/reports/*.csv` has one line per client tick; `/clauac debug` shows the
  outcome of every tick in the action bar. The CSV is written through a buffer and lags a few seconds behind; for the
  current client tick, run `clauac status` on the console, which prints a line with the outcomes of every connection
  and, for a simulated one, a second line with what its simulation costs.
- **Rebuilding:** `runServer` loads the plugin jar straight from `build/libs`. Building while the server runs replaces
  the jar under it and later fails with `NoClassDefFoundError`, so stop the server before building.
- **System properties:** the container already sets `JAVA_TOOL_OPTIONS` for its HTTPS proxy, so add ClauAC's
  properties to it instead of replacing it, e.g.
  `JAVA_TOOL_OPTIONS="$JAVA_TOOL_OPTIONS -Dclauac.verifyRepeatedTicks=true" ./gradlew runServer`.
- **Holding the client's packets** (a proxy imitating a stalled connection): Paper disconnects a client that sent more
  than 500 packets per second over 7 s. The test client sends about 80 to 170 packets per second, most of them pongs
  to ClauAC's pings, which ClauAC takes out before Paper counts them: releasing 25 s of held packets (3052) stayed
  below the limit, together with a flood of 1500 tick ends 12 s later.
- **A second player:** start another client with its own game directory (a copy of `options.txt` with a low
  `maxFps` and `renderDistance` keeps both clients responsive). Both windows share the Xvfb display: find them with
  `xdotool search --pid <pid>`, move the second one off screen with `xdotool windowmove`, and give the first one the
  keyboard with `xdotool windowfocus`.
- **Test world:** blocks placed by earlier tests stay in the world and get in the way of later courses (a leftover
  furnace swallowed the clicks meant for a chest). Clear them before a recording, for example with
  `fill <from> <to> minecraft:air replace <block>`, which leaves the course itself alone.
- **config.yml of the dev server:** `saveDefaultConfig` never overwrites `run/plugins/ClauAC/config.yml`, so after a
  new setting was added the file lacks it and the setting takes its default. Delete the file before starting the
  server to get the current one with its comments.
- **Testing setbacks:** a proxy that changes the positions in the client's movement packets imitates a movement cheat
  without a cheat client; the server's own position of the player can be sampled with `data get entity <name> Pos` on
  the console meanwhile. Findings from that: the client answers a vehicle correction (`ClientboundMoveVehiclePacket`)
  with a vehicle move packet right away, before the pong behind it; since the simulation continues from what a failed
  tick reported, a tick that fails after the client took a correction needs a setback of its own, or the ticks after it
  match and their movement reaches the server; and the 26.2 client answers a teleport with its acceptance and its
  resulting position in a movement packet right after it, which such a proxy shifts as well (26.3 puts the position into
  the acceptance). With `setbacks.maximum-hold-millis` at 1, shifted answers reach the server unjudged and keep the
  player floating over the floor, and the server kicks it after 80 ticks (`ServerGamePacketListenerImpl.tick`).
- **Testing disablers:** a cheat that leaves the sandbox unsure of what the client did, so that the tick's movement goes
  unchecked, has to be tested the way a cheat uses it. The proxy's `after-tick-end` sends its packet behind every tick
  end, where it leads the next tick's key handling, from a second before the shifted positions start, and the answers
  to the corrections stay unshifted (`shift-answers off`): otherwise the first shifted tick fails before the sandbox is
  unsure, and the check of the answers keeps the player in its setback, which hid the disabler in a first try. A
  sprint attack slows its attacker down only when charged (`after-tick-end every N` leaves time to charge), and a
  knockback enchantment never does on the client: `LivingEntity.getKnockback` adds it only in a `ServerLevel`. It does
  only when the client counts the attack as a hit (`Entity.hurtOrSimulate` calls `hurtClient` there): a boat does
  (`VehicleEntity.hurtClient`), a living entity never (`Entity.hurtClient`, which `LivingEntity` keeps), so a sprint
  attack on a mob or another player leaves the client's speed alone.
- **Pipelines under `pipefail`:** `producer | grep -q` fails whenever grep stops reading before the producer has written
  everything, since the producer then dies of SIGPIPE. A check of the client's command line
  (`tr '\0' '\n' < /proc/<pid>/cmdline | grep -q`) missed the running client in 6 of 300 tries that way. Read the input
  whole first, e.g. `grep -q -- "$pattern" <<< "$(tr '\0' '\n' < /proc/<pid>/cmdline)"`.
- **Entity tick order:** the client ticks its entities in the order it added them (`ClientLevel.tickEntities`,
  `EntityTickList`), and every entity came after the local player. A respawn in the same level adds the new player
  behind the entities the client has, but the server then sends each entity around it again, which
  `ClientLevel.addEntity` adds anew behind the player (it removes the one with the same id first): a cow summoned
  before the respawn pushed the player only after the player's tick.
