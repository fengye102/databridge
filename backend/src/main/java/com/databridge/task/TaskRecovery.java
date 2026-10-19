package com.databridge.task;

import java.util.List;
import java.util.UUID;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

/** Restarts only the unique task that was active when this single process exited. */
public final class TaskRecovery implements ApplicationRunner {
    private final TaskRepository repository;
    private final TaskCoordinator coordinator;

    public TaskRecovery(TaskRepository repository, TaskCoordinator coordinator) {
        this.repository = repository;
        this.coordinator = coordinator;
    }

    @Override
    public void run(ApplicationArguments ignored) {
        List<UUID> active = repository.recoverableTaskIds();
        if (active.size() > 1) {
            throw new IllegalStateException("More than one task requires recovery");
        }
        if (!active.isEmpty()) {
            coordinator.start(active.getFirst());
        }
    }
}
