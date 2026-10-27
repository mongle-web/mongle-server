package com.mongle.backend.domain.dream.image.s3;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.mongle.backend.domain.dream.image.ImagePayload;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.http.apache5.Apache5HttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.retries.StandardRetryStrategy;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.*;
import java.time.*;
import java.util.concurrent.*;
import javax.imageio.ImageIO;

class S3ImageAssetStoreTest {
    static final String KEY = "dream-images/1/2/12345678-1234-1234-1234-123456789abc.png";
    S3TestTransport transport = new S3TestTransport();

    @Test void worldImagesUseTheSamePrivateStorageWithTheirOwnAttemptNamespace() throws Exception {
        try (var store = transport.store()) {
            String key = KEY.replace("dream-images/", "world-images/");
            store.put(key, payload("png"));
            store.delete(key);
            assertThat(transport.calls).hasSize(2);
            assertThat(transport.calls.getFirst().key()).contains("world-images/");
        }
    }
    static ImagePayload payload(String format) throws Exception {
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2,3,BufferedImage.TYPE_INT_RGB), format, bytes);
        return ImagePayload.parse(bytes.toByteArray());
    }

    @Test void uploadsValidatedPngAndJpegWithMimeAndNoPublicAclThenDeletesIdempotently() throws Exception {
        try (var store = transport.store()) {
            var png = payload("png"); var jpg = payload("jpeg");
            store.put(KEY,png); store.put(KEY.replace(".png",".jpg"),jpg);
            assertThat(transport.objects.get(KEY)).containsExactly(png.bytes());
            var call = transport.calls.getFirst();
            assertThat(call.headers().get("Content-Type")).containsExactly("image/png");
            assertThat(call.headers().keySet()).noneMatch(k -> k.equalsIgnoreCase("x-amz-acl"));
            assertThat(transport.calls.get(1).headers().get("Content-Type")).containsExactly("image/jpeg");
            store.delete(KEY); store.delete(KEY);
            assertThat(transport.objects).doesNotContainKey(KEY);
            assertThat(transport.calls.stream().filter(c -> c.method()==SdkHttpMethod.DELETE).count()).isEqualTo(2);
        }
    }

    @Test void onlyExplicitMissingKeyIsSuccessfulAndPermissionBucketAndUnknown404AreFailures() {
        try (var store = transport.store()) {
            transport.failures.add(new S3TestTransport.Failure(404,"NoSuchKey"));
            store.delete(KEY);
            for (var failure : new S3TestTransport.Failure[]{
                    new S3TestTransport.Failure(403,"AccessDenied"), new S3TestTransport.Failure(404,"NoSuchBucket"),
                    new S3TestTransport.Failure(404,"UnknownError"), new S3TestTransport.Failure(503,"SlowDown")}) {
                transport.failures.add(failure);
                assertThatThrownBy(() -> store.delete(KEY)).isInstanceOf(IllegalStateException.class)
                        .hasNoCause().hasMessageNotContaining(KEY).hasMessageNotContaining("private-aws-body").hasMessageNotContaining("test-secret");
            }
        }
    }

    @Test void sdkRetriesTransientFailuresWithinConfiguredAttemptLimit() {
        try (var store = new S3ImageAssetStore(transport.client(2), S3TestTransport.presigner(),"mongle-test-images",Clock.systemUTC())) {
            transport.failures.add(new S3TestTransport.Failure(500,"InternalError"));
            store.delete(KEY);
            assertThat(transport.calls).hasSize(2);
            transport.failures.add(new S3TestTransport.Failure(500,"InternalError"));
            transport.failures.add(new S3TestTransport.Failure(500,"InternalError"));
            assertThatThrownBy(() -> store.delete(KEY)).isInstanceOf(IllegalStateException.class);
            assertThat(transport.calls).hasSize(4);
        }
    }

    @Test void realPresignerCreatesBrowserHttpsGetForExactKeyWithAtMostFiveMinutes() {
        try (var store = transport.store()) {
            var before = Instant.now();
            var result = store.temporaryUrl(KEY,Duration.ofMinutes(5));
            var uri = URI.create(result.url());
            assertThat(uri.getScheme()).isEqualTo("https");
            assertThat(uri.getHost()).isEqualTo("mongle-test-images.s3.ap-northeast-2.amazonaws.com");
            assertThat(uri.getPath()).isEqualTo("/"+KEY);
            assertThat(uri.getQuery()).contains("X-Amz-Expires=300","X-Amz-Signature=","X-Amz-SignedHeaders=host");
            assertThat(result.expiresAt()).isAfter(before).isBeforeOrEqualTo(Instant.now().plusSeconds(300));
            assertThat(result.toString()).doesNotContain("Signature",KEY);
            assertThat(transport.calls).isEmpty();
            assertThat(store.temporaryUrl(KEY,Duration.ofSeconds(1)).expiresAt()).isAfter(Instant.now());
        }
    }

    static PresignedGetObjectRequest signed(String url, Instant expiration, boolean browser) throws Exception {
        var signed = mock(PresignedGetObjectRequest.class);
        when(signed.url()).thenReturn(URI.create(url).toURL());
        when(signed.expiration()).thenReturn(expiration);
        when(signed.isBrowserExecutable()).thenReturn(browser);
        return signed;
    }
    @ParameterizedTest @ValueSource(strings={"http://private.example/key","https://user:secret@private.example/key"})
    void rejectsUnsafePresignerUrls(String url) throws Exception {
        var presigner = mock(S3Presigner.class);
        var signed = signed(url,Instant.now().plusSeconds(30),true);
        when(presigner.presignGetObject(any(software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest.class)))
                .thenReturn(signed);
        try (var store = new S3ImageAssetStore(mock(S3Client.class),presigner,"mongle-test-images",Clock.systemUTC())) {
            assertThatThrownBy(() -> store.temporaryUrl(KEY,Duration.ofSeconds(30))).isInstanceOf(IllegalStateException.class).hasNoCause();
        }
    }

    @Test void rejectsExpiredOverlongAndNonBrowserSignatures() throws Exception {
        for (int i=0;i<3;i++) {
            var expiration = i==0 ? Instant.now().minusSeconds(1) : Instant.now().plusSeconds(i==1 ? 600 : 30);
            var presigner = mock(S3Presigner.class);
            var signed = signed("https://private.example/key",expiration,i!=2);
            when(presigner.presignGetObject(any(software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest.class)))
                    .thenReturn(signed);
            try (var store = new S3ImageAssetStore(mock(S3Client.class),presigner,"bucket",Clock.systemUTC())) {
                assertThatThrownBy(() -> store.temporaryUrl(KEY,Duration.ofMinutes(5))).isInstanceOf(IllegalStateException.class);
            }
        }
    }

    @ParameterizedTest @ValueSource(strings={"other/key","dream-images/1/2/../../secret.png","/dream-images/1/2/key.png", "dream-images/1/2/key.png?signature=secret"})
    void rejectsKeysOutsideServerAttemptNamespaceBeforeSdkCalls(String key) throws Exception {
        try (var store = transport.store()) {
            assertThatThrownBy(() -> store.put(key,payload("png"))).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> store.delete(key)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> store.temporaryUrl(key,Duration.ofSeconds(10))).isInstanceOf(IllegalArgumentException.class);
            assertThat(transport.calls).isEmpty();
        }
    }

    @Test void invalidMimeSizeAndLifetimeNeverReachSdk() throws Exception {
        try (var store = transport.store()) {
            assertThatThrownBy(() -> store.put(KEY,payload("jpeg"))).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> store.put(KEY,new ImagePayload(new byte[0],"image/png",1,1))).isInstanceOf(IllegalArgumentException.class);
            for (var ttl : new Duration[]{Duration.ZERO,Duration.ofSeconds(301),Duration.ofMillis(1500)})
                assertThatThrownBy(() -> store.temporaryUrl(KEY,ttl)).isInstanceOf(IllegalArgumentException.class);
            assertThat(transport.calls).isEmpty();
        }
    }

    @Test void closesOwnedSdkClientsOnceAndStopsAcceptingRequests() {
        var client = mock(S3Client.class); var signer = mock(S3Presigner.class);
        var store = new S3ImageAssetStore(client,signer,"bucket",Clock.systemUTC());
        assertThat(store.available()).isTrue(); store.close(); store.close();
        assertThat(store.available()).isFalse(); verify(client).close(); verify(signer).close();
        assertThatThrownBy(() -> store.delete(KEY)).isInstanceOf(IllegalStateException.class);
        verifyNoMoreInteractions(client,signer);
    }

    @Test void closeFailureStillClosesOtherResourcesWithoutExposingSdkCause() {
        var client=mock(S3Client.class); var signer=mock(S3Presigner.class);
        doThrow(new IllegalStateException("private-aws-body")).when(client).close();
        var store=new S3ImageAssetStore(client,signer,"bucket",Clock.systemUTC());
        assertThatThrownBy(store::close).hasNoCause().hasMessageNotContaining("private-aws-body");
        verify(signer).close(); assertThat(store.available()).isFalse();
    }

    @Test void closingStoreAbortsLocalBlockedS3RequestAfterWorkerInterruption() throws Exception {
        var entered=new CountDownLatch(1); var release=new CountDownLatch(1); var finished=new CountDownLatch(1);
        var interrupted=new java.util.concurrent.atomic.AtomicBoolean();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var handlers=Executors.newCachedThreadPool(); var workers=Executors.newSingleThreadExecutor();
        server.setExecutor(handlers);
        server.createContext("/", exchange -> {
            entered.countDown();
            try { release.await(5,TimeUnit.SECONDS); }
            catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        }); server.start();
        try (var client=S3Client.builder().region(Region.AP_NORTHEAST_2).credentialsProvider(S3TestTransport.credentials())
                .endpointOverride(URI.create("http://127.0.0.1:"+server.getAddress().getPort())).forcePathStyle(true)
                .httpClientBuilder(Apache5HttpClient.builder().connectionTimeout(Duration.ofSeconds(1)).socketTimeout(Duration.ofSeconds(5)))
                .overrideConfiguration(b -> b.apiCallTimeout(Duration.ofSeconds(10)).apiCallAttemptTimeout(Duration.ofSeconds(5))
                        .retryStrategy(StandardRetryStrategy.builder().maxAttempts(1).build())).build();
             var store=new S3ImageAssetStore(client,S3TestTransport.presigner(),"mongle-test-images",Clock.systemUTC())) {
            var task=workers.submit(() -> {
                try { store.delete(KEY); }
                catch (IllegalStateException ex) { interrupted.set(Thread.currentThread().isInterrupted()); }
                finally { finished.countDown(); }
            });
            assertThat(entered.await(3,TimeUnit.SECONDS)).isTrue(); task.cancel(true); store.close();
            assertThat(finished.await(2,TimeUnit.SECONDS)).isTrue();
            assertThat(interrupted).isTrue();
        } finally { release.countDown(); workers.shutdownNow(); server.stop(0); handlers.shutdownNow(); }
    }

    @Test void localStalledResponseTimesOutAndReturnsNoExternalCause() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var executor = Executors.newCachedThreadPool(); server.setExecutor(executor);
        server.createContext("/", exchange -> {
            entered.countDown();
            try { release.await(5,TimeUnit.SECONDS); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        }); server.start();
        try (var client = S3Client.builder().region(Region.AP_NORTHEAST_2).credentialsProvider(S3TestTransport.credentials())
                .endpointOverride(URI.create("http://127.0.0.1:"+server.getAddress().getPort())).forcePathStyle(true)
                .httpClientBuilder(Apache5HttpClient.builder().connectionTimeout(Duration.ofMillis(200)).socketTimeout(Duration.ofSeconds(2)))
                .overrideConfiguration(b -> b.apiCallTimeout(Duration.ofMillis(600)).apiCallAttemptTimeout(Duration.ofMillis(300))
                        .retryStrategy(StandardRetryStrategy.builder().maxAttempts(1).build())).build();
             var store = new S3ImageAssetStore(client,S3TestTransport.presigner(),"mongle-test-images",Clock.systemUTC())) {
            long started = System.nanoTime();
            assertThatThrownBy(() -> store.delete(KEY)).isInstanceOf(IllegalStateException.class).hasNoCause();
            assertThat(entered.getCount()).isZero();
            assertThat(Duration.ofNanos(System.nanoTime()-started)).isLessThan(Duration.ofSeconds(3));
        } finally { release.countDown(); server.stop(0); executor.shutdownNow(); }
    }
}
