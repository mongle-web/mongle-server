package com.mongle.backend.domain.dream.image;

import static com.mongle.backend.domain.dream.gateway.DreamAiTestAwait.await;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.mongle.backend.global.error.BusinessException;

import org.junit.jupiter.api.*;

import java.time.*;
import java.util.List;
import java.util.concurrent.CompletableFuture;

class DreamImageServiceTest {
    ImageTransactions transactions;
    ImageGenerator generator;
    ImageAssetStore storage;
    DreamImageService service;
    ImageGenerationResources resources;
    ImageGenerator.Input input;
    ImageRequest request = new ImageRequest(0L, "test-style", null, false, null);
    Instant now = Instant.parse("2026-10-06T00:00:00Z");

    @BeforeEach
    void setup() {
        transactions = mock(ImageTransactions.class);
        generator = mock(ImageGenerator.class);
        storage = mock(ImageAssetStore.class);
        resources = new ImageGenerationResources(2);
        service =
                new DreamImageService(
                        transactions, generator, storage, Clock.fixed(now, ZoneOffset.UTC), resources);
        input =
                new ImageGenerator.Input(
                        1L, 2L, "attempt", "image-v1-story", "test-style", null, List.of());
        when(generator.available()).thenReturn(true);
        when(storage.available()).thenReturn(true);
        when(transactions.begin(eq(1L), eq(3L), eq(request), eq(true), any()))
                .thenAnswer(invocation -> {
                    invocation.getArgument(4, Runnable.class).run();
                    return new ImageTransactions.Reservation(mock(ImageResponse.class), input);
                });
    }

    @AfterEach
    void close() { resources.close(); }

    @Test
    void preservesProviderFailureWhenFailureRecordingAlsoFails() {
        var original = new IllegalStateException("provider");
        var recovery = new IllegalStateException("database");
        when(generator.generate(input)).thenThrow(original);
        when(transactions.fail(input, "CALL_FAILED")).thenThrow(recovery);
        assertThatThrownBy(() -> await(service.generate(1L, 3L, request)))
                .isInstanceOf(BusinessException.class)
                .hasCause(original);
        assertThat(original.getSuppressed()).containsExactly(recovery);
        verify(storage, never()).put(anyString(), any());
        verify(storage, never()).delete(anyString());
    }

    @Test
    void preservesPersistenceFailureAndCleanupFailureWithoutDeletingPriorAsset() {
        var original = new IllegalStateException("persist");
        var cleanup = new IllegalStateException("cleanup");
        var recovery = new IllegalStateException("recovery");
        when(generator.generate(input)).thenReturn(CompletableFuture.completedFuture(ImagePayloadTest.png()));
        when(transactions.finish(eq(input), anyString(), any())).thenThrow(original);
        doThrow(cleanup).when(storage).delete("dream-images/1/2/attempt.png");
        when(transactions.fail(input, "PERSISTENCE_FAILED")).thenThrow(recovery);
        assertThatThrownBy(() -> await(service.generate(1L, 3L, request))).hasCause(original);
        assertThat(original.getSuppressed()).containsExactly(cleanup, recovery);
        verify(storage).delete("dream-images/1/2/attempt.png");
        verify(storage, never()).delete("prior-success.png");
    }

    @Test
    void rejectedCompletionReturnsCurrentStateEvenWhenCleanupFails() {
        var response = mock(ImageResponse.class);
        when(generator.generate(input)).thenReturn(CompletableFuture.completedFuture(ImagePayloadTest.png()));
        when(transactions.finish(eq(input), anyString(), any()))
                .thenReturn(new ImageTransactions.Completion(response, false));
        doThrow(new IllegalStateException("cleanup")).when(storage).delete(anyString());
        assertThat(await(service.generate(1L, 3L, request))).isSameAs(response);
        verify(transactions, never()).fail(any(), anyString());
    }

    @Test
    void reservationRollbackReleasesAcquiredSlotWithoutCallingProvider() {
        var database = new IllegalStateException("reservation commit");
        when(transactions.begin(eq(1L), eq(3L), eq(request), eq(true), any()))
                .thenAnswer(invocation -> {
                    invocation.getArgument(4, Runnable.class).run();
                    throw database;
                });
        assertThatThrownBy(() -> service.generate(1L, 3L, request)).isSameAs(database);
        verify(generator, never()).generate(any());
        try (var first = resources.reserve(); var second = resources.reserve()) {
            assertThatThrownBy(resources::reserve)
                    .isInstanceOf(java.util.concurrent.RejectedExecutionException.class);
        }
    }

    @Test
    void validatesSignedUrlSchemeCredentialsAndLifetime() {
        when(transactions.assetKey(1L, 2L)).thenReturn("private-key");
        for (var url :
                List.of(
                        new ImageAssetStore.DownloadUrl(
                                "http://images.test/a", now.plusSeconds(100)),
                        new ImageAssetStore.DownloadUrl(
                                "https://name:secret@images.test/a", now.plusSeconds(100)),
                        new ImageAssetStore.DownloadUrl("https://images.test/a", now),
                        new ImageAssetStore.DownloadUrl(
                                "https://images.test/a", now.plusSeconds(301)),
                        new ImageAssetStore.DownloadUrl("https://images.test/a", null))) {
            when(storage.temporaryUrl("private-key", Duration.ofMinutes(5))).thenReturn(url);
            assertThatThrownBy(() -> service.downloadUrl(1L, 2L))
                    .isInstanceOf(BusinessException.class);
        }
        var valid =
                new ImageAssetStore.DownloadUrl(
                        "https://images.test/a?signature=hidden", now.plusSeconds(300));
        when(storage.temporaryUrl("private-key", Duration.ofMinutes(5))).thenReturn(valid);
        assertThat(service.downloadUrl(1L, 2L)).isEqualTo(valid);
        assertThat(valid.toString()).doesNotContain("signature", "hidden", "images.test");
    }

    @Test
    void ownershipIsCheckedBeforeUrlSigning() {
        when(transactions.assetKey(1L, 2L))
                .thenThrow(new BusinessException(ImageErrorCode.NOT_FOUND));
        assertThatThrownBy(() -> service.downloadUrl(1L, 2L)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(storage);
    }
}
