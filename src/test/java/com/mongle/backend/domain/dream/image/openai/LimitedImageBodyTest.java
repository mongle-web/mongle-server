package com.mongle.backend.domain.dream.image.openai;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class LimitedImageBodyTest {
    @Test void boundsChunkedBodyWithoutRelyingOnContentLength() {
        var body=new LimitedImageBody(4); var subscription=mock(Flow.Subscription.class);
        body.onSubscribe(subscription);
        body.onNext(List.of(ByteBuffer.wrap(new byte[]{1,2}),ByteBuffer.wrap(new byte[]{3,4})));
        body.onNext(List.of(ByteBuffer.wrap(new byte[]{5})));
        verify(subscription).cancel();
        assertThat(body.getBody().toCompletableFuture()).isCompletedExceptionally();
        body.onComplete(); assertThat(body.getBody().toCompletableFuture()).isCompletedExceptionally();
    }
    @Test void returnsExactBytesAndRejectsSecondSubscription() throws Exception {
        var body=new LimitedImageBody(4); var first=mock(Flow.Subscription.class); var second=mock(Flow.Subscription.class);
        body.onSubscribe(first); body.onSubscribe(second); verify(second).cancel();
        body.onNext(List.of(ByteBuffer.wrap(new byte[]{1,2,3}))); body.onComplete();
        assertThat(body.getBody().toCompletableFuture().get(1,TimeUnit.SECONDS)).containsExactly(1,2,3);
    }
}
