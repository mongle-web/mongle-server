package com.mongle.backend.domain.payment;
import java.io.ByteArrayOutputStream;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.*;
final class PayAppBody implements HttpResponse.BodySubscriber<byte[]> {
    private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
    private final CompletableFuture<byte[]> body=new CompletableFuture<>();
    private Flow.Subscription subscription;
    public CompletionStage<byte[]> getBody() { return body; }
    public void onSubscribe(Flow.Subscription s) { if(subscription!=null){s.cancel();return;} subscription=s; s.request(1); }
    public void onNext(List<ByteBuffer> list) {
        if(body.isDone()) return;
        if(list.stream().mapToLong(ByteBuffer::remaining).sum()>32768-bytes.size()) {
            subscription.cancel(); body.completeExceptionally(new IllegalStateException("결제 응답 크기 초과")); return;
        }
        for(var b:list) { byte[] part=new byte[b.remaining()]; b.get(part); bytes.writeBytes(part); }
        subscription.request(1);
    }
    public void onError(Throwable t) { body.completeExceptionally(t); }
    public void onComplete() { body.complete(bytes.toByteArray()); }
}
