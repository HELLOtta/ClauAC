package io.github.hellotta.clauac.simulation.session;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

// - Runs the tasks of one connection one after another, in submission order, on a shared thread pool. A task -
// - never overlaps with another task of the same connection, while different connections run in parallel. It -
// - counts what waits and for how long, and tells the connection's SimulationCost when each task starts and ends -
final class SerialExecutor {

    // - A task with the bytes it keeps in memory while it waits, and when it was submitted -
    private record QueuedTask(Runnable task, long bytes, long queuedAt) {
    }

    private final Executor pool;
    private final SimulationCost cost;
    private final Queue<QueuedTask> tasks = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean draining = new AtomicBoolean();
    private final AtomicInteger queuedTasks = new AtomicInteger();
    private final AtomicLong queuedBytes = new AtomicLong();

    SerialExecutor(Executor pool, SimulationCost cost) {
        this.pool = pool;
        this.cost = cost;
    }

    void execute(Runnable task, long bytes) {
        this.queuedTasks.incrementAndGet();
        this.queuedBytes.addAndGet(bytes);
        this.tasks.add(new QueuedTask(task, bytes, System.nanoTime()));
        this.scheduleDrain();
    }

    int queuedTasks() {
        return this.queuedTasks.get();
    }

    long queuedBytes() {
        return this.queuedBytes.get();
    }

    // - How long the oldest waiting task has waited; 0 when none waits -
    long lagNanos(long now) {
        QueuedTask oldest = this.tasks.peek();
        return oldest != null ? Math.max(0L, now - oldest.queuedAt()) : 0L;
    }

    // - Drops every waiting task and then submits this one, which runs after the task that runs now. A task that -
    // - the running drain took at the same moment still runs -
    void abandonWaitingTasks(Runnable last) {
        QueuedTask dropped;
        while ((dropped = this.tasks.poll()) != null) {
            this.dequeued(dropped);
        }
        this.execute(last, 0L);
    }

    private void scheduleDrain() {
        if (this.draining.compareAndSet(false, true)) {
            this.pool.execute(this::drain);
        }
    }

    private void dequeued(QueuedTask task) {
        this.queuedTasks.decrementAndGet();
        this.queuedBytes.addAndGet(-task.bytes());
    }

    // - A task that throws ends this drain; the exception reaches the pool thread's uncaught exception handler and -
    // - the remaining tasks continue in a new drain -
    private void drain() {
        try {
            QueuedTask queued;
            while ((queued = this.tasks.poll()) != null) {
                this.dequeued(queued);
                long startedAt = System.nanoTime();
                this.cost.taskStarted(startedAt, startedAt - queued.queuedAt());
                try {
                    queued.task().run();
                } finally {
                    this.cost.taskFinished(System.nanoTime());
                }
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
