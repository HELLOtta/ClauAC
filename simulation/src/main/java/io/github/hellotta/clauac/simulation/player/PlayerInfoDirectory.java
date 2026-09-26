package io.github.hellotta.clauac.simulation.player;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

// - ClientPacketListener.getPlayerInfo: the tab list entries the client knows -
public interface PlayerInfoDirectory {

    @Nullable SandboxPlayerInfo getPlayerInfo(UUID profileId);
}
