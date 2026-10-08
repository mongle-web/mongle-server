package com.mongle.backend.domain.dream.story;

import static com.mongle.backend.domain.dream.gateway.DreamAiTestAwait.await;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mongle.backend.domain.dream.analysis.StructureResult;
import com.mongle.backend.global.error.BusinessException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.List;
import java.util.Set;

class DreamStoryServiceTest {
    private final StoryTransactions transactions = mock(StoryTransactions.class);
    private final StoryGenerator generator = mock(StoryGenerator.class);
    private final StoryValidator validator = mock(StoryValidator.class);
    private final com.mongle.backend.domain.dream.gateway.DreamGenerationResources resources =
            new com.mongle.backend.domain.dream.gateway.DreamGenerationResources(4);

    @org.junit.jupiter.api.AfterEach
    void closeResources() {
        resources.close();
    }

    private final DreamStoryService service =
            new DreamStoryService(transactions, generator, validator, resources);
    private final StoryRequest request = new StoryRequest(1L, false, null);
    private final StoryGenerator.Input input =
            new StoryGenerator.Input(
                    1L,
                    2L,
                    3L,
                    "attempt-id",
                    1L,
                    "바다 위를 날았다.",
                    Set.of(),
                    List.of(new StructureResult.Scene(1, "바다 위를 날았다.", false, List.of())),
                    List.of());
    private final StoryResult result =
            new StoryResult(
                    List.of(new StoryResult.Section(1, StoryResult.Kind.SCENE, 1, "바다 위를 날았다.")));
    private final RuntimeException persistenceFailure =
            new DataIntegrityViolationException("Result persistence failed");

    @BeforeEach
    void prepareSuccessfulGenerationAndFailedPersistence() {
        when(generator.available()).thenReturn(true);
        when(transactions.begin(1L, 4L, request, true))
                .thenReturn(new StoryTransactions.Reservation(null, input));
        when(generator.generate(input))
                .thenReturn(
                        java.util.concurrent.CompletableFuture.completedFuture("generated-output"));
        when(validator.parse("generated-output", input.scenes())).thenReturn(result);
        when(transactions.finish(input, result)).thenThrow(persistenceFailure);
    }

    @Test
    void preservesPersistenceFailureWhenFailureStateIsSaved() {
        var exception =
                assertThrows(
                        BusinessException.class, () -> await(service.generate(1L, 4L, request)));

        assertThat(exception.getErrorCode()).isEqualTo(StoryErrorCode.CALL_FAILED);
        assertThat(exception.getCause()).isSameAs(persistenceFailure);
        assertThat(persistenceFailure.getSuppressed()).isEmpty();
        verify(transactions).fail(input, "PERSISTENCE_FAILED");
    }

    @Test
    void preservesCallFailedMappingAndBothCausesWhenFailureStateCannotBeSaved() {
        var recoveryFailure = new DataAccessResourceFailureException("Database unavailable");
        when(transactions.fail(input, "PERSISTENCE_FAILED")).thenThrow(recoveryFailure);

        var exception =
                assertThrows(
                        BusinessException.class, () -> await(service.generate(1L, 4L, request)));

        assertThat(exception.getErrorCode()).isEqualTo(StoryErrorCode.CALL_FAILED);
        assertThat(exception.getCause()).isSameAs(persistenceFailure);
        assertThat(exception.getCause().getSuppressed()).containsExactly(recoveryFailure);
        verify(transactions).fail(input, "PERSISTENCE_FAILED");
    }
}
