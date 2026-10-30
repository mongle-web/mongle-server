package com.mongle.backend.domain.dream.image;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.mongle.backend.global.error.BusinessException;

import org.junit.jupiter.api.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;

class ImageDownloadServiceTest {
    final Instant now = Instant.parse("2026-10-30T12:00:00Z");
    ImageTransactions transactions;
    ImageAssetStore storage;
    ImageDownloadService service;

    @BeforeEach
    void setup() {
        transactions = mock(ImageTransactions.class);
        storage = mock(ImageAssetStore.class);
        service =
                new ImageDownloadService(
                        transactions,
                        storage,
                        Clock.fixed(now, ZoneOffset.UTC),
                        new ImageExecutionProperties(1, 1));
        when(storage.available()).thenReturn(true);
        when(storage.temporaryUrl(anyString(), eq(Duration.ofMinutes(5)))).thenReturn(valid());
    }

    ImageAssetStore.DownloadUrl valid() {
        return new ImageAssetStore.DownloadUrl(
                "https://images.test/a?signature=secret", now.plusSeconds(300));
    }

    @Test
    void deduplicatesInFirstRequestOrderAndReadsMetadataOnce() {
        when(transactions.assets(eq(1L), any()))
                .thenReturn(
                        List.of(
                                new ImageTransactions.Asset(2L, 8, "key-2"),
                                new ImageTransactions.Asset(3L, 9, "key-3")));
        var result = service.downloadUrls(1L, List.of(3L, 2L, 3L));
        assertThat(result.items())
                .extracting(ImageDownloadResponse.Item::imageId)
                .containsExactly(3L, 2L);
        assertThat(result.items())
                .extracting(ImageDownloadResponse.Item::imageVersion)
                .containsExactly(9L, 8L);
        assertThat(result.items())
                .allSatisfy(
                        item -> {
                            assertThat(item.status())
                                    .isEqualTo(ImageDownloadResponse.Status.SUCCESS);
                            assertThat(item.expiresAt()).isEqualTo(now.plusSeconds(300));
                            assertThat(item.failureCode()).isNull();
                        });
        verify(transactions).assets(1L, new LinkedHashSet<>(List.of(3L, 2L)));
        verifyNoMoreInteractions(transactions);
        var ordered = inOrder(storage);
        ordered.verify(storage).available();
        ordered.verify(storage).temporaryUrl("key-3", Duration.ofMinutes(5));
        ordered.verify(storage).available();
        ordered.verify(storage).temporaryUrl("key-2", Duration.ofMinutes(5));
        verifyNoMoreInteractions(storage);
        assertThat(result.toString()).doesNotContain("signature", "secret", "images.test");
        assertThat(new ImageTransactions.Asset(3L, 9, "private-storage-key").toString())
                .doesNotContain("private-storage-key");
    }

    @Test
    void unavailableAndUnknownAssetsHaveNoUrlAndAreNotSigned() {
        when(transactions.assets(eq(1L), any()))
                .thenReturn(List.of(new ImageTransactions.Asset(2L, 5, "key-2")));
        when(storage.available()).thenReturn(false);
        var result = service.downloadUrls(1L, List.of(99L, 2L));
        assertFailure(result.items().get(0), null, "IMAGE_404_1");
        assertFailure(result.items().get(1), 5L, "IMAGE_503_1");
        verify(storage, never()).temporaryUrl(anyString(), any());
    }

    @Test
    void signingFailureIsIsolatedAndPrivateProviderMessageIsNotReturned() {
        when(transactions.assets(eq(1L), any()))
                .thenReturn(
                        List.of(
                                new ImageTransactions.Asset(2L, 5, "key-2"),
                                new ImageTransactions.Asset(3L, 6, "key-3")));
        when(storage.temporaryUrl(eq("key-2"), any()))
                .thenThrow(
                        new IllegalStateException(
                                "private-key https://images.test?signature=secret"));
        var result = service.downloadUrls(1L, List.of(2L, 3L));
        assertFailure(result.items().get(0), 5L, "IMAGE_502_2");
        assertThat(result.items().get(1).status()).isEqualTo(ImageDownloadResponse.Status.SUCCESS);
        assertThat(result.toString()).doesNotContain("private-key", "signature", "secret");
    }

    @Test
    void rejectsUntrustedUrlsWithoutAbortingTheBatchOrLeakingTheSignedValue() {
        when(transactions.assets(eq(1L), any()))
                .thenReturn(List.of(new ImageTransactions.Asset(2L, 5, "key-2")));
        for (var invalid :
                Arrays.asList(
                        new ImageAssetStore.DownloadUrl(
                                "http://images.test/a", now.plusSeconds(10)),
                        new ImageAssetStore.DownloadUrl(
                                "https://user:secret@images.test/a", now.plusSeconds(10)),
                        new ImageAssetStore.DownloadUrl("https:/relative", now.plusSeconds(10)),
                        new ImageAssetStore.DownloadUrl("https://images.test/a", now),
                        new ImageAssetStore.DownloadUrl(
                                "https://images.test/a", now.minusSeconds(1)),
                        new ImageAssetStore.DownloadUrl(
                                "https://images.test/a", now.plusSeconds(301)),
                        new ImageAssetStore.DownloadUrl("https://images.test/a", null),
                        new ImageAssetStore.DownloadUrl(null, now.plusSeconds(10)),
                        null)) {
            when(storage.temporaryUrl(eq("key-2"), any())).thenReturn(invalid);
            assertFailure(
                    service.downloadUrls(1L, List.of(2L)).items().getFirst(), 5L, "IMAGE_502_2");
        }
        when(storage.temporaryUrl(eq("key-2"), any())).thenReturn(valid());
        assertThat(service.downloadUrls(1L, List.of(2L)).items().getFirst().status())
                .isEqualTo(ImageDownloadResponse.Status.SUCCESS);
    }

    @Test
    void invalidRequestsDoNotReadMetadataOrCallStorageAndLimitAppliesBeforeDeduplication() {
        for (var ids :
                Arrays.asList(
                        null,
                        List.<Long>of(),
                        Arrays.asList(1L, null),
                        List.of(0L),
                        List.of(-1L),
                        Collections.nCopies(32, 2L))) {
            assertThatThrownBy(() -> service.downloadUrls(1L, ids))
                    .isInstanceOf(BusinessException.class);
        }
        verifyNoInteractions(transactions, storage);
    }

    @Test
    void capacityRejectsBeforeAnotherQueryAndReturnsPermitAfterCompletion() throws Exception {
        when(transactions.assets(eq(1L), any()))
                .thenReturn(List.of(new ImageTransactions.Asset(2L, 5, "key-2")));
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(storage.temporaryUrl(eq("key-2"), any()))
                .thenAnswer(
                        invocation -> {
                            started.countDown();
                            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
                            return valid();
                        });
        try (var executor = Executors.newSingleThreadExecutor()) {
            var first = executor.submit(() -> service.downloadUrls(1L, List.of(2L)));
            try {
                assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> service.downloadUrls(1L, List.of(2L)))
                        .isInstanceOfSatisfying(
                                BusinessException.class,
                                failure ->
                                        assertThat(failure.getErrorCode())
                                                .isEqualTo(ImageErrorCode.BUSY));
                verify(transactions, times(1)).assets(eq(1L), any());
            } finally {
                release.countDown();
            }
            assertThat(first.get(5, TimeUnit.SECONDS).items().getFirst().status())
                    .isEqualTo(ImageDownloadResponse.Status.SUCCESS);
        }
        assertThat(service.downloadUrls(1L, List.of(2L)).items().getFirst().status())
                .isEqualTo(ImageDownloadResponse.Status.SUCCESS);
    }

    @Test
    void metadataFailureReleasesCapacityAndDoesNotSignUnknownFiles() {
        when(transactions.assets(eq(1L), any()))
                .thenThrow(new IllegalStateException("database unavailable"))
                .thenReturn(List.of());
        assertThatThrownBy(() -> service.downloadUrls(1L, List.of(2L)))
                .isInstanceOf(IllegalStateException.class);
        assertFailure(
                service.downloadUrls(1L, List.of(2L)).items().getFirst(), null, "IMAGE_404_1");
        verifyNoInteractions(storage);
    }

    static void assertFailure(ImageDownloadResponse.Item item, Long version, String code) {
        assertThat(item.status()).isEqualTo(ImageDownloadResponse.Status.FAILED);
        assertThat(item.imageVersion()).isEqualTo(version);
        assertThat(item.failureCode()).isEqualTo(code);
        assertThat(item.url()).isNull();
        assertThat(item.expiresAt()).isNull();
    }
}
