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
