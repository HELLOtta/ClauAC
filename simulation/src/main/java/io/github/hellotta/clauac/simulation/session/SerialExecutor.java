package io.github.hellotta.clauac.simulation.session;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

// - Runs the tasks of one connection one after another, in submission order, on a shared thread pool. A task -
// - never overlaps with another task of the same connection, while different connections run in parallel -
final class SerialExecutor implements Executor {

    private final Executor pool;
    private final Queue<Runnable> tasks = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean draining = new AtomicBoolean();

    SerialExecutor(Executor pool) {
        this.pool = pool;
    }

    @Override
    public void execute(Runnable task) {
        this.tasks.add(task);
        this.scheduleDrain();
    }

    private void scheduleDrain() {
        if (this.draining.compareAndSet(false, true)) {
            this.pool.execute(this::drain);
        }
    }

    // - A task that throws ends this drain; the exception reaches the pool thread's uncaught exception handler and -
    // - the remaining tasks continue in a new drain -
    private void drain() {
        try {
            Runnable task;
            while ((task = this.tasks.poll()) != null) {
                task.run();
            }
        } finally {
            this.draining.set(false);
            // - A task added after the queue was seen empty but before the flag was cleared has to be picked up -
            if (!this.tasks.isEmpty()) {
                this.scheduleDrain();
            }
        }
    }
}
