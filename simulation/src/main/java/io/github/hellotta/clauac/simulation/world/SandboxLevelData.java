package io.github.hellotta.clauac.simulation.world;

import net.minecraft.world.Difficulty;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.level.storage.WritableLevelData;

// - Port of the client-only ClientLevel.ClientLevelData. The horizon and void-darkness helpers are left out because -
// - only the renderer reads them -
public final class SandboxLevelData implements WritableLevelData {

    private final boolean hardcore;
    private final boolean isFlat;
    private LevelData.RespawnData respawnData;
    private long gameTime;
    private Difficulty difficulty;
    private boolean difficultyLocked;

    public SandboxLevelData(Difficulty difficulty, boolean hardcore, boolean isFlat) {
        this.difficulty = difficulty;
        this.hardcore = hardcore;
        this.isFlat = isFlat;
    }

    @Override
    public LevelData.RespawnData getRespawnData() {
        return this.respawnData;
    }

    @Override
    public long getGameTime() {
        return this.gameTime;
    }

    public void setGameTime(long time) {
        this.gameTime = time;
    }

    @Override
    public void setSpawn(LevelData.RespawnData respawnData) {
        this.respawnData = respawnData;
    }

    @Override
    public boolean isHardcore() {
        return this.hardcore;
    }

    @Override
    public Difficulty getDifficulty() {
        return this.difficulty;
    }

    @Override
    public boolean isDifficultyLocked() {
        return this.difficultyLocked;
    }

    public void setDifficulty(Difficulty difficulty) {
        this.difficulty = difficulty;
    }

    public void setDifficultyLocked(boolean locked) {
        this.difficultyLocked = locked;
    }

    public boolean isFlat() {
        return this.isFlat;
    }
}
