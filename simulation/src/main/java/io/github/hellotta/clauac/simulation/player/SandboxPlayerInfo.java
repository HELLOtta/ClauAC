package io.github.hellotta.clauac.simulation.player;

import com.mojang.authlib.GameProfile;
import net.minecraft.world.level.GameType;

// - The parts of the client-only PlayerInfo (a tab list entry) that players read: the profile a remote player is -
// - created with and the game mode that decides whether a player is a spectator -
public final class SandboxPlayerInfo {

    private final GameProfile profile;
    private GameType gameMode = GameType.DEFAULT_MODE;

    public SandboxPlayerInfo(GameProfile profile) {
        this.profile = profile;
    }

    public GameProfile getProfile() {
        return this.profile;
    }

    public GameType getGameMode() {
        return this.gameMode;
    }

    public void setGameMode(GameType gameMode) {
        this.gameMode = gameMode;
    }
}
