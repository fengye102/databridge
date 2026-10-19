package com.databridge.task;

import com.databridge.copy.CopyTable;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tasks")
public class TaskController {
    private final TaskCoordinator coordinator;

    public TaskController(TaskCoordinator coordinator) {
        this.coordinator = coordinator;
    }

    @PostMapping
    public ResponseEntity<TaskRepository.TaskRecord> create(@RequestBody CopyTable table) {
        UUID taskId = coordinator.create(table);
        return ResponseEntity.created(URI.create("/api/tasks/" + taskId))
                .body(coordinator.task(taskId));
    }

    @GetMapping("/{taskId}")
    public TaskRepository.TaskRecord get(@PathVariable UUID taskId) {
        return coordinator.task(taskId);
    }

    @PostMapping("/{taskId}/start")
    public ResponseEntity<TaskRepository.TaskRecord> start(@PathVariable UUID taskId) {
        coordinator.start(taskId);
        return ResponseEntity.accepted().body(coordinator.task(taskId));
    }

    @PostMapping("/{taskId}/pause")
    public TaskRepository.TaskRecord pause(@PathVariable UUID taskId) {
        coordinator.pause(taskId);
        return coordinator.task(taskId);
    }

    @PostMapping("/{taskId}/resume")
    public TaskRepository.TaskRecord resume(@PathVariable UUID taskId) {
        coordinator.resume(taskId);
        return coordinator.task(taskId);
    }

    @PostMapping("/{taskId}/stop")
    public TaskRepository.TaskRecord stop(@PathVariable UUID taskId) {
        coordinator.stop(taskId);
        return coordinator.task(taskId);
    }
}
