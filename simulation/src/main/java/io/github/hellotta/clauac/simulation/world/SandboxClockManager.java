package io.github.hellotta.clauac.simulation.world;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.Holder;
import net.minecraft.util.Mth;
import net.minecraft.world.clock.ClockInstance;
import net.minecraft.world.clock.ClockManager;
import net.minecraft.world.clock.ClockNetworkState;
import net.minecraft.world.clock.WorldClock;

// - Port of the client-only ClientClockManager, which advances world clocks between the server's time updates -
public final class SandboxClockManager implements ClockManager {

    private final Map<Holder<WorldClock>, SandboxClockInstance> clocks = new HashMap<>();
    private long lastTickGameTime;

    @Override
    public SandboxClockInstance getInstance(Holder<WorldClock> definition) {
        return this.clocks.computeIfAbsent(definition, ignored -> new SandboxClockInstance());
    }

    public void tick(long gameTime) {
        long gameTimeDelta = gameTime - this.lastTickGameTime;
        this.lastTickGameTime = gameTime;

        for (SandboxClockInstance instance : this.clocks.values()) {
            double newPartialTicks = instance.partialTick + (double) gameTimeDelta * instance.rate;
            long fullTicks = Mth.floor(newPartialTicks);
            instance.partialTick = (float) (newPartialTicks - fullTicks);
            instance.totalTicks += fullTicks;
        }
    }

    public void handleUpdates(long gameTime, Map<Holder<WorldClock>, ClockNetworkState> updates) {
        this.tick(gameTime);
        updates.forEach((definition, state) -> {
            SandboxClockInstance clock = this.getInstance(definition);
            clock.totalTicks = state.totalTicks();
            clock.partialTick = state.partialTick();
            clock.rate = state.rate();
        });
    }

    public static final class SandboxClockInstance implements ClockInstance {

        private long totalTicks;
        private float partialTick;
        private float rate = 1.0F;

        @Override
        public long totalTicks() {
            return this.totalTicks;
        }

        @Override
        public float partialTick() {
            return this.partialTick;
        }

        @Override
        public float rate() {
            return this.rate;
        }

        @Override
        public boolean isPaused() {
            return this.rate == 0.0F;
        }
    }
}
