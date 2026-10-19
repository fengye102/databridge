package com.databridge.task;

import com.databridge.cdc.CdcCapture;
import com.databridge.cdc.CdcEventRepository;
import com.databridge.cdc.EventApplier;
import com.databridge.copy.CopyTable;
import com.databridge.copy.FullCopyWorker;
import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** Single-process, one-active-task runner for durable copy followed by ordered event replay. */
public final class TaskCoordinator implements AutoCloseable {
    private static final java.util.Set<UUID> RUNNING = ConcurrentHashMap.newKeySet();
    private final DataSource source;
    private final DataSource target;
    private final TaskRepository repository;
    private final Properties cdcSettings = new Properties();
    private final int batchSize;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(
            runnable -> new Thread(runnable, "databridge-task-runner"));
    private volatile Runner active;
    private volatile boolean closed;

    public TaskCoordinator(DataSource source, DataSource target, Properties cdcSettings, int batchSize) {
        this.source = Objects.requireNonNull(source);
        this.target = Objects.requireNonNull(target);
        this.repository = new TaskRepository(source, target);
        this.cdcSettings.putAll(Objects.requireNonNull(cdcSettings));
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize must be positive");
        }
        this.batchSize = batchSize;
    }

    public UUID create(CopyTable table) {
        requireOpen();
        return repository.create(table);
    }

    public synchronized void start(UUID taskId) {
        requireOpen();
        Objects.requireNonNull(taskId);
        if (active != null) {
            throw new IllegalStateException("This coordinator already has an active task");
        }
        if (!RUNNING.add(taskId)) {
            throw new IllegalStateException("Task already has an active runner: " + taskId);
        }
        TaskRepository.TaskRecord before;
        try {
            before = repository.get(taskId);
            repository.start(taskId);
        } catch (RuntimeException e) {
            RUNNING.remove(taskId);
            throw e;
        }
        Runner runner = new Runner(taskId, before.table(), "PAUSED".equals(before.status()));
        active = runner;
        try {
            executor.execute(runner);
        } catch (RuntimeException e) {
            active = null;
            RUNNING.remove(taskId);
            repository.fail(taskId, e);
            throw e;
        }
    }

    public void pause(UUID taskId) {
        requireOpen();
        Runner runner = active;
        if (runner != null && runner.taskId.equals(taskId)) {
            runner.phaseLock.lock();
            try {
                repository.pause(taskId);
            } finally {
                runner.phaseLock.unlock();
            }
        } else {
            repository.pause(taskId);
        }
    }

    public void resume(UUID taskId) {
        requireOpen();
        repository.resume(taskId);
    }

    public void stop(UUID taskId) {
        requireOpen();
        repository.stop(taskId);
        Runner runner = active;
        if (runner != null && runner.taskId.equals(taskId)) {
            waitFor(runner);
        }
    }

    public TaskRepository.TaskRecord task(UUID taskId) {
        requireOpen();
        return repository.get(taskId);
    }

    Properties settingsFor(UUID taskId) {
        var perTask = new Properties();
        perTask.putAll(cdcSettings);
        perTask.setProperty("topic.prefix", "databridge_" + taskId.toString().replace("-", ""));
        return perTask;
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("TaskCoordinator is closed");
        }
    }

    private void waitFor(Runner runner) {
        try {
            runner.finished.get(35, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while stopping task", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("Task did not stop within 35 seconds", e);
        }
    }

    @Override
    public void close() throws IOException {
        Runner runner;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            runner = active;
        }
        RuntimeException stoppingFailure = null;
        try {
            if (runner != null) {
                String status = repository.get(runner.taskId).status();
                if (!status.equals("STOPPED") && !status.equals("FAILED")) {
                    repository.stop(runner.taskId);
                }
                waitFor(runner);
            }
        } catch (RuntimeException e) {
            stoppingFailure = e;
            executor.shutdownNow();
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(35, TimeUnit.SECONDS)) {
                executor.shutdownNow();
                throw new IOException("Task runner did not terminate");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
            throw new IOException("Interrupted while closing TaskCoordinator", e);
        }
        if (stoppingFailure != null) {
            throw stoppingFailure;
        }
    }

    private final class Runner implements Runnable {
        private final UUID taskId;
        private final CopyTable table;
        private final boolean recoverPaused;
        private final CompletableFuture<Void> finished = new CompletableFuture<>();
        private final ReentrantLock phaseLock = new ReentrantLock();

        private Runner(UUID taskId, CopyTable table, boolean recoverPaused) {
            this.taskId = taskId;
            this.table = table;
            this.recoverPaused = recoverPaused;
        }

        @Override
        public void run() {
            try (var capture = new CdcCapture(taskId,
                    table.sourceSchema() + "." + table.sourceTable(),
                    settingsFor(taskId), new CdcEventRepository(target), source)) {
                capture.start();
                capture.awaitReady(Duration.ofSeconds(30));
                if (recoverPaused && repository.get(taskId).status().equals("CAPTURING")) {
                    repository.parkAfterRecovery(taskId, checkpointComplete() ? "REPLAYING" : "COPYING");
                }
                var copy = new FullCopyWorker(source, target);
                var applier = new EventApplier(target);
                while (true) {
                    if (capture.failure() != null) {
                        throw new IllegalStateException("CDC capture failed", capture.failure());
                    }
                    String status = repository.get(taskId).status();
                    switch (status) {
                        case "STOPPED", "FAILED" -> { return; }
                        case "PAUSED" -> sleep();
                        case "CAPTURING" -> repository.advance(taskId, "CAPTURING",
                                checkpointComplete() ? "REPLAYING" : "COPYING");
                        case "COPYING" -> copyBatch(copy);
                        case "REPLAYING", "STREAMING" -> applyBatch(applier);
                        default -> throw new IllegalStateException("Unexpected task status: " + status);
                    }
                }
            } catch (Throwable error) {
                try {
                    repository.fail(taskId, error);
                } catch (RuntimeException databaseError) {
                    error.addSuppressed(databaseError);
                }
            } finally {
                RUNNING.remove(taskId);
                synchronized (TaskCoordinator.this) {
                    if (active == this) {
                        active = null;
                    }
                }
                finished.complete(null);
            }
        }

        private boolean checkpointComplete() {
            var completed = new JdbcTemplate(target).queryForList("""
                    SELECT completed FROM databridge_meta.full_checkpoint WHERE task_id = ?
                    """, Boolean.class, taskId);
            return !completed.isEmpty() && completed.getFirst();
        }

        private void copyBatch(FullCopyWorker copy) {
            phaseLock.lock();
            try {
                if (repository.get(taskId).status().equals("COPYING")
                        && copy.copyNextBatch(taskId, table, batchSize) == 0) {
                    repository.advance(taskId, "COPYING", "REPLAYING");
                }
            } finally {
                phaseLock.unlock();
            }
        }

        private void applyBatch(EventApplier applier) {
            phaseLock.lock();
            try {
                String current = repository.get(taskId).status();
                if (!current.equals("REPLAYING") && !current.equals("STREAMING")) {
                    return;
                }
                int applied = applier.applyNextBatch(taskId, table, batchSize);
                if (applied == 0) {
                    if (current.equals("REPLAYING")) {
                        repository.advance(taskId, "REPLAYING", "STREAMING");
                    } else {
                        sleep();
                    }
                }
            } finally {
                phaseLock.unlock();
            }
        }

        private void sleep() {
            try {
                TimeUnit.MILLISECONDS.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Task runner interrupted", e);
            }
        }
    }
}
