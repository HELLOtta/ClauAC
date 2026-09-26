package io.github.hellotta.clauac.simulation.player;

import java.util.UUID;
import net.minecraft.world.level.GameType;
import org.jspecify.annotations.Nullable;

// - AbstractClientPlayer.getPlayerInfo and gameMode: a player looks up its tab list entry once it exists and keeps -
// - that entry for its whole lifetime, even if the entry is later removed and added again -
final class CachedPlayerInfo {

    private final PlayerInfoDirectory directory;
    private final UUID profileId;
    private @Nullable SandboxPlayerInfo playerInfo;

    CachedPlayerInfo(PlayerInfoDirectory directory, UUID profileId) {
        this.directory = directory;
        this.profileId = profileId;
    }

    @Nullable GameType gameMode() {
        if (this.playerInfo == null) {
            this.playerInfo = this.directory.getPlayerInfo(this.profileId);
        }
        return this.playerInfo != null ? this.playerInfo.getGameMode() : null;
    }
}
