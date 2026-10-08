package com.mongle.backend.domain.dream.generation;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.mongle.backend.domain.dream.analysis.AnalysisResponse;
import com.mongle.backend.domain.dream.analysis.AnalysisErrorCode;
import com.mongle.backend.domain.dream.analysis.DreamStructureService;
import com.mongle.backend.domain.dream.gateway.DreamAiProperties;
import com.mongle.backend.domain.dream.story.DreamStoryService;
import com.mongle.backend.global.common.GenerationStatus;
import com.mongle.backend.global.error.BusinessException;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.LongStream;

class DreamGenerationWorkerTest {
    private final DreamGenerationTransactions transactions = mock(DreamGenerationTransactions.class);
    private final DreamStructureService analysis = mock(DreamStructureService.class);
    private final DreamStoryService story = mock(DreamStoryService.class);

    private DreamGenerationWorker worker(int capacity, Clock clock) {
        return new DreamGenerationWorker(transactions, analysis, story,
                new DreamGenerationProperties(false, Duration.ofSeconds(2)),
                new DreamAiProperties(capacity), clock);
    }

    private DreamGenerationTransactions.Claim claim(long id) {
        return new DreamGenerationTransactions.Claim(
                id, id, id, 1, DreamGenerationJob.Stage.ANALYSIS, "claim-" + id, false);
    }

    @Test
    void limitsPendingCallsWithoutQueueingWaitingThreads() {
        var candidates = LongStream.rangeClosed(1, 5)
                .mapToObj(id -> new DreamGenerationTransactions.Candidate(id, id)).toList();
        when(transactions.candidates()).thenReturn(candidates);
        when(transactions.claim(any())).thenAnswer(invocation -> {
            var candidate = (DreamGenerationTransactions.Candidate) invocation.getArgument(0);
            return Optional.of(claim(candidate.id()));
        });
        when(analysis.analyzeSource(anyLong(), anyLong(), anyLong()))
                .thenAnswer(invocation -> new CompletableFuture<AnalysisResponse>());
        try (var worker = worker(2, Clock.systemUTC())) {
            worker.poll();
            worker.poll();
            verify(analysis, times(2)).analyzeSource(anyLong(), anyLong(), anyLong());
            verify(transactions, times(2)).claim(any());
            verify(transactions, never()).result(any(), any(), any());
        }
    }

    @Test
    void recordsResultOnDedicatedControlThreadInsteadOfProviderThread() throws Exception {
        var candidate = new DreamGenerationTransactions.Candidate(1L, 1L);
        when(transactions.candidates()).thenReturn(List.of(candidate));
        when(transactions.claim(candidate)).thenReturn(Optional.of(claim(1)));
        var future = new CompletableFuture<AnalysisResponse>();
        when(analysis.analyzeSource(1L, 1L, 1L)).thenReturn(future);
        var thread = new AtomicReference<String>();
        var recorded = new CountDownLatch(1);
        doAnswer(invocation -> {
            thread.set(Thread.currentThread().getName());
            recorded.countDown();
            return null;
        }).when(transactions).result(any(), eq(GenerationStatus.COMPLETED), isNull());
        try (var worker = worker(2, Clock.systemUTC())) {
            worker.poll();
            var response = mock(AnalysisResponse.class);
            when(response.status()).thenReturn(GenerationStatus.COMPLETED);
            future.complete(response);
            assertThat(recorded.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(thread.get()).startsWith("dream-auto-control-");
        }
    }

    @Test
    void recoveryUsesInjectedClockAndDoesNotCallAiForLiveAttempt() {
        var candidate = new DreamGenerationTransactions.Candidate(1L, 1L);
        var claim = claim(1);
        when(transactions.candidates()).thenReturn(List.of(candidate));
        when(transactions.claim(candidate)).thenReturn(Optional.of(claim));
        var now = Instant.parse("2001-01-01T00:00:00Z");
        when(transactions.progress(claim)).thenReturn(
                new DreamGenerationTransactions.Progress(GenerationStatus.PROCESSING, null, now.plusSeconds(1)));
        try (var worker = worker(2, Clock.fixed(now, ZoneOffset.UTC))) {
            worker.poll();
            verify(transactions).result(claim, GenerationStatus.PROCESSING, null);
            verifyNoInteractions(analysis, story);
        }
    }

    private CompletableFuture<AnalysisResponse> pendingAnalysis() {
        var candidate = new DreamGenerationTransactions.Candidate(1L, 1L);
        when(transactions.candidates()).thenReturn(List.of(candidate));
        when(transactions.claim(candidate)).thenReturn(Optional.of(claim(1)));
        var future = new CompletableFuture<AnalysisResponse>();
        when(analysis.analyzeSource(1L, 1L, 1L)).thenReturn(future);
        return future;
    }

    @Test
    void preservesStoredPersistenceFailureInsteadOfWrappedCallFailure() {
        var future = pendingAnalysis();
        when(transactions.progress(claim(1))).thenReturn(null,
                new DreamGenerationTransactions.Progress(
                        GenerationStatus.FAILED, "PERSISTENCE_FAILED", null));
        try (var worker = worker(2, Clock.systemUTC())) {
            worker.poll();
            future.completeExceptionally(new CompletionException(new BusinessException(
                    AnalysisErrorCode.CALL_FAILED,
                    new DataAccessResourceFailureException("테스트 저장 실패"))));
        }
        verify(transactions).result(claim(1), GenerationStatus.FAILED, "PERSISTENCE_FAILED");
        verify(transactions, never()).result(any(), any(), eq("CALL_FAILED"));
    }

    @Test
    void wrappedDatabaseFailureKeepsClaimForRecoveryWhenNoFailureWasStored() {
        var future = pendingAnalysis();
        try (var worker = worker(2, Clock.systemUTC())) {
            worker.poll();
            future.completeExceptionally(new CompletionException(new BusinessException(
                    AnalysisErrorCode.CALL_FAILED,
                    new IllegalStateException(new DataAccessResourceFailureException("테스트 DB 장애")))));
        }
        verify(transactions, never()).result(any(), any(), any());
    }

    @Test
    void unavailableProgressKeepsClaimForRecoveryInsteadOfGuessingFailure() {
        var future = pendingAnalysis();
        when(transactions.progress(claim(1))).thenReturn(null)
                .thenThrow(new DataAccessResourceFailureException("테스트 조회 장애"));
        try (var worker = worker(2, Clock.systemUTC())) {
            worker.poll();
            future.completeExceptionally(new BusinessException(AnalysisErrorCode.CALL_FAILED));
        }
        verify(transactions, never()).result(any(), any(), any());
    }

    @Test
    void providerFailureWithoutStoredStateStillRecordsCallFailure() {
        var future = pendingAnalysis();
        try (var worker = worker(2, Clock.systemUTC())) {
            worker.poll();
            future.completeExceptionally(new BusinessException(AnalysisErrorCode.CALL_FAILED));
        }
        verify(transactions).result(claim(1), GenerationStatus.FAILED, "CALL_FAILED");
    }
}
