package com.mongle.backend.domain.dream.image.s3;

import org.springframework.transaction.support.TransactionSynchronizationManager;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.*;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.retries.StandardRetryStrategy;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** 실제 SDK 직렬화/서명을 거치지만 AWS/자격 증명 서비스와 통신하지 않는 전송 계층. */
public final class S3TestTransport implements SdkHttpClient {
    public record Failure(int status, String code) {}
    public record Call(SdkHttpMethod method, String key, Map<String,List<String>> headers,
            byte[] bytes, boolean transaction, String thread) {}
    public final Map<String,byte[]> objects = new ConcurrentHashMap<>();
    public final List<Call> calls = new CopyOnWriteArrayList<>();
    public final Queue<Failure> failures = new ConcurrentLinkedQueue<>();
    public volatile Consumer<Call> afterPut = ignored -> {};

    public static StaticCredentialsProvider credentials() {
        return StaticCredentialsProvider.create(AwsBasicCredentials.create("test-access", "test-secret"));
    }
    public S3Client client(int attempts) {
        return S3Client.builder().region(Region.AP_NORTHEAST_2).credentialsProvider(credentials())
                .httpClient(this)
                .overrideConfiguration(b -> b.apiCallTimeout(Duration.ofSeconds(3))
                        .apiCallAttemptTimeout(Duration.ofSeconds(1))
                        .retryStrategy(StandardRetryStrategy.builder().maxAttempts(attempts).build()))
                .build();
    }
    public static S3Presigner presigner() {
        return S3Presigner.builder().region(Region.AP_NORTHEAST_2).credentialsProvider(credentials()).build();
    }
    public S3ImageAssetStore store() {
        return new S3ImageAssetStore(client(1), presigner(), "mongle-test-images", Clock.systemUTC());
    }

    @Override public ExecutableHttpRequest prepareRequest(HttpExecuteRequest request) {
        return new ExecutableHttpRequest() {
            @Override public HttpExecuteResponse call() throws IOException {
                byte[] bytes = new byte[0];
                if (request.contentStreamProvider().isPresent()) {
                    try (var input = request.contentStreamProvider().get().newStream()) { bytes = input.readAllBytes(); }
                }
                var http = request.httpRequest();
                String key = http.encodedPath().substring(1);
                if (http.firstMatchingHeader("Content-Encoding").orElse("").contains("aws-chunked")) bytes = decodeChunks(bytes);
                var call = new Call(http.method(), key, http.headers(), bytes,
                        TransactionSynchronizationManager.isActualTransactionActive(), Thread.currentThread().getName());
                calls.add(call);
                var failure = failures.poll();
                int status = failure == null ? (http.method() == SdkHttpMethod.DELETE ? 204 : 200) : failure.status();
                String xml = failure == null ? "" : "<Error><Code>" + failure.code()
                        + "</Code><Message>private-aws-body</Message></Error>";
                if (failure == null && http.method() == SdkHttpMethod.PUT) {
                    objects.put(key, bytes); afterPut.accept(call);
                }
                if (failure == null && http.method() == SdkHttpMethod.DELETE) objects.remove(key);
                var body = xml.getBytes(StandardCharsets.UTF_8);
                return HttpExecuteResponse.builder()
                        .response(SdkHttpResponse.builder().statusCode(status)
                                .putHeader("Content-Type", "application/xml")
                                .putHeader("Content-Length", Integer.toString(body.length)).build())
                        .responseBody(AbortableInputStream.create(new ByteArrayInputStream(body))).build();
            }
            @Override public void abort() {}
        };
    }
    private static byte[] decodeChunks(byte[] raw) throws IOException {
        var output = new ByteArrayOutputStream();
        int offset = 0;
        while (offset < raw.length) {
            int end = offset;
            while (end + 1 < raw.length && !(raw[end] == 13 && raw[end + 1] == 10)) end++;
            String header = new String(raw, offset, end-offset, StandardCharsets.US_ASCII).split(";",2)[0];
            int size = Integer.parseInt(header,16);
            if (size == 0) break;
            offset = end + 2; output.write(raw,offset,size); offset += size + 2;
        }
        return output.toByteArray();
    }
    public void reset() { objects.clear(); calls.clear(); failures.clear(); afterPut = ignored -> {}; }
    @Override public void close() {}
}
