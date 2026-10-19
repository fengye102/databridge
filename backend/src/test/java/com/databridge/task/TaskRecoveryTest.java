package com.databridge.task;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TaskRecoveryTest {
    private final TaskRepository repository = mock(TaskRepository.class);
    private final TaskCoordinator coordinator = mock(TaskCoordinator.class);

    @Test
    void startsExactlyOnePersistedActiveTask() throws Exception {
        UUID id = UUID.randomUUID();
        when(repository.recoverableTaskIds()).thenReturn(List.of(id));
        new TaskRecovery(repository, coordinator).run(null);
        verify(coordinator).start(id);
    }

    @Test
    void rejectsAmbiguousRecoveryInsteadOfPickingArbitraryTask() {
        when(repository.recoverableTaskIds()).thenReturn(
                List.of(UUID.randomUUID(), UUID.randomUUID()));
        assertThrows(IllegalStateException.class,
                () -> new TaskRecovery(repository, coordinator).run(null));
        verifyNoInteractions(coordinator);
    }
}
