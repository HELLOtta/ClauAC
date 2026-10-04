package io.github.hellotta.clauac.response;

import java.util.Arrays;
import java.util.Optional;
import java.util.stream.Collectors;

// - Where a setback puts the player (setbacks.type in config.yml). PREDICTED puts it where the simulation moved it in -
// - the tick that failed, which is where a vanilla client with the same keys and rotation would be, and the server -
// - gets that as the tick's movement: a cheat that stops its fall or its movement short of what a vanilla client -
// - does is moved on as a vanilla client moves. SERVER puts it back where the server has it, which the failed tick's -
// - movement never reached: the player stays wherever its last tick that passed left it. A tick whose inputs are in -
// - doubt (Check.doubtsInputs) always goes back where the server has the player, since the simulation moved the -
// - player with those inputs as well -
public enum SetbackType {
    PREDICTED("predicted"),
    SERVER("server");

    private final String configName;

    SetbackType(String configName) {
        this.configName = configName;
    }

    // - The name config.yml uses -
    public String configName() {
        return this.configName;
    }

    // - The type of this name in config.yml, if there is one -
    public static Optional<SetbackType> byConfigName(String name) {
        return Arrays.stream(values()).filter(type -> type.configName.equals(name)).findFirst();
    }

    // - Every name config.yml accepts, for a warning -
    public static String configNames() {
        return Arrays.stream(values()).map(SetbackType::configName).collect(Collectors.joining(", "));
    }
}
