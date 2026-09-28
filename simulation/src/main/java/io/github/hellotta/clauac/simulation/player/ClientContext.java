package io.github.hellotta.clauac.simulation.player;

// - Client state that lives outside the player on the client (connection, game mode, camera, tab list) -
public interface ClientContext extends PlayerInfoDirectory {

    // - ClientPacketListener.hasClientLoaded: the player does not tick before the client reported it has loaded -
    boolean hasClientLoaded();

    // - LocalPlayer.isControlledCamera: whether the camera is the player itself rather than a spectated entity -
    boolean isCameraOnPlayer();

    // - MultiPlayerGameMode.isSpectator: the game mode the client applied to itself -
    boolean isLocalModeSpectator();

    // - The client started sprinting this tick through a path the server cannot observe directly (double tapping -
    // - forward within the client's sprint window option); its START_SPRINTING command of the tick reveals it -
    boolean sprintStartReportedThisTick();

    // - LocalPlayer.onUpdateAbilities sends the abilities to the server -
    void onAbilitiesSent();

    // - LocalPlayer.aiStep sends START_FALL_FLYING when the player starts gliding -
    void onFallFlyingStartSent();

    // - LocalPlayer.sendRidingJump sends START_RIDING_JUMP with the jump power when the player releases the jump of -
    // - its vehicle -
    void onRidingJumpSent(int jumpPower);

    // - LocalPlayer.tick sends the player's keys and movement right after the player's own tick: its position, or -
    // - while it rides its rotation and the position of the vehicle it steers -
    void onPlayerTicked();
}
