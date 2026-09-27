package io.github.hellotta.clauac.simulation.api;

// - One reason a client tick is MISMATCHED: the check it failed and what exactly failed, in words -
public record Flag(Check check, String detail) {
}
