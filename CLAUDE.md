# ClauAC

Predictive (simulation-based) anticheat plugin for Paper 26.3, built on PacketEvents. Read [README.md](README.md)
first: it lists the target versions and explains how PacketEvents and adventure are bundled, including the rule that
ClauAC code must never pass adventure objects to or receive them from the Paper API.

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
  version manifest (https://piston-meta.mojang.com/mc/game/version_manifest_v2.json). Minecraft 26.3 is **not
  obfuscated**: the client jar, the vanilla server jar and Paper's server jar all use the real `net.minecraft` names,
  so classes can be read directly, e.g. `javap -p -c -cp <jar> net.minecraft.world.entity.LivingEntity`.
- **Other plugins or mods as dependencies:** clone that product's source code and check the exact version that is
  depended on.

## Rules for the simulation module

- Code in `simulation` runs inside the isolated vanilla runtime (see README): it may use `net.minecraft` and the
  libraries of the vanilla server, never Paper, Bukkit or PacketEvents. The plugin and the simulation only exchange
  the types in `simulation-api` (plain Java types and raw packet bytes).
- The client jar is not on the build's class path. Client-only behaviour is ported from the 26.3 client jar (read it
  with `javap` or a decompiler such as Vineflower) and each port names the class or method it comes from.
- Ports keep the client's order of operations. Leave out only what renders, plays sounds or shows screens, and say in
  a comment what was left out. Anything the sandbox cannot reproduce must be reported (`UNVERIFIED` with a note), never
  approximated silently.

## Verify by running it

A successful build is not verification. For every change:

1. Build with `./gradlew build`.
2. Start a real server in this environment (`./gradlew runServer`) and check that the plugin loads and the log shows no
   new errors or warnings.
3. Test the change in game with a real 26.3 client whenever it involves players, movement, combat or packets.
4. Check the result visually: take screenshots, and record video when motion or timing matters, then look at them.

## Real client in a headless container

Findings from running the official 26.3 client in a cloud container without a GPU or sound card:

- **Display:** Xvfb with Mesa's software renderer (llvmpipe). On Xvfb the client fails to create its OpenGL backend
  ("Couldn't find matching GLX visual") and falls back to Vulkan, so `mesa-vulkan-drivers` must be installed. On a
  4-core container it rendered about 10 distinct frames per second.
- **Joining the dev server:** launch the client with an offline identity and set `online-mode=false` in
  `run/server.properties`. The `server.properties` Paper 26.3 generated here had `white-list=true`, so the test player
  has to be added with `whitelist add <name>` first.
- **Input and screenshots:** `xdotool` for keyboard and mouse, `import -window root <file>.png` for screenshots.
- **Sound:** run PulseAudio with a `module-null-sink` and start the client with `ALSOFT_DRIVERS=pulse`; the sink's
  `.monitor` source then carries the game audio.
- **Recording:** ffmpeg `x11grab` plus `pulse` from the monitor source, encoded with `libx264 -preset ultrafast` so
  capture keeps up in real time. Measured on 26.3 gameplay, re-encoding the finished recording with
  `-preset medium -crf 21` gave an equal or slightly higher SSIM at about half the file size. Check the audio of every
  recording (for example with ffmpeg's `volumedetect`); a fully silent track measures about -91 dB.
- **Test world settings:** game rule ids are snake_case in 26.3 (`log_admin_commands`, `advance_time`,
  `drowning_damage`). Set `broadcast-console-to-ops=false` in `run/server.properties` for recordings, otherwise console
  commands fill the chat and cover the action bar. A test player left idle under water drowns, so turn
  `drowning_damage` off on the test world. Phantoms come for a player that has gone more than 72000 ticks without
  sleeping whenever the sky is dark enough (`PhantomSpawner`), which a thunderstorm makes it even at a stopped noon, and
  they killed an idle test player: turn `spawn_phantoms` off, clear the weather and turn `advance_weather` off.
- **Console commands:** `~ ~ ~` in a console command means the console's position (the world spawn); run relative
  commands through the player, e.g. `execute as Tester at @s run summon minecraft:cow ^ ^ ^2`.
- **Key presses:** `xdotool key` releases the key within the same client tick, which the client's per-tick key polling
  can miss (a double tap of jump to fly never registers). Hold keys with `keydown`, `sleep 0.1`, `keyup`.
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
  than 500 packets per second over 7 s, and the pongs to ClauAC's pings make the test client send about 80 to 170
  per second. Releasing 25 s of held packets got it disconnected; holds of 15 s stayed below the limit.
- **A second player:** start another client with its own game directory (a copy of `options.txt` with a low
  `maxFps` and `renderDistance` keeps both clients responsive). Both windows share the Xvfb display: find them with
  `xdotool search --pid <pid>`, move the second one off screen with `xdotool windowmove`, and give the first one the
  keyboard with `xdotool windowfocus`.
- **Test world:** blocks placed by earlier tests stay in the world and get in the way of later courses (a leftover
  furnace swallowed the clicks meant for a chest). Clear them before a recording, for example with
  `fill <from> <to> minecraft:air replace <block>`, which leaves the course itself alone.
