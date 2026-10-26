package com.mongle.backend.domain.dream.image.openai;

import com.mongle.backend.domain.dream.image.ImageErrorCode;
import com.mongle.backend.global.error.BusinessException;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.*;

/** Content-Length 유무와 무관하게 수신 도중 제한하고, 초과하면 HTTP 구독을 취소한다. */
final class LimitedImageBody implements HttpResponse.BodySubscriber<byte[]> {
    private final int limit;
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private final CompletableFuture<byte[]> result = new CompletableFuture<>();
    private Flow.Subscription subscription;
    LimitedImageBody(int limit) { this.limit = limit; }
    @Override public CompletionStage<byte[]> getBody() { return result; }
    @Override public void onSubscribe(Flow.Subscription value) {
        if (subscription != null) { value.cancel(); return; }
        subscription = value; value.request(1);
    }
    @Override public void onNext(List<ByteBuffer> values) {
        if (result.isDone()) return;
        long incoming = values.stream().mapToLong(ByteBuffer::remaining).sum();
        if (incoming > limit - bytes.size()) {
            subscription.cancel();
            result.completeExceptionally(new BusinessException(ImageErrorCode.INVALID_OUTPUT));
            return;
        }
        for (var value : values) {
            byte[] part = new byte[value.remaining()]; value.get(part); bytes.writeBytes(part);
        }
        subscription.request(1);
    }
    @Override public void onError(Throwable error) { result.completeExceptionally(error); }
    @Override public void onComplete() { result.complete(bytes.toByteArray()); }
}
