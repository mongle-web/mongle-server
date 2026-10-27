package com.mongle.backend.domain.dream.image.openai;

import static org.assertj.core.api.Assertions.*;

import com.mongle.backend.domain.dream.image.*;
import com.mongle.backend.global.error.BusinessException;
import com.mongle.backend.global.logging.support.LogCapture;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

class OpenAiImageGeneratorTest {
    OpenAiTestServer server;
    @BeforeEach void start() { server=new OpenAiTestServer(); }
    @AfterEach void close() { server.close(); }
    static <T> T await(CompletableFuture<T> future) {
        try { return future.get(4,TimeUnit.SECONDS); }
        catch (ExecutionException ex) { throw (RuntimeException)ex.getCause(); }
        catch (Exception ex) { throw new AssertionError(ex); }
    }
    void received(int count) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        while(server.received.size()<count && System.nanoTime()<end) Thread.sleep(5);
        assertThat(server.received).hasSize(count);
    }

    @Test void sendsOneAuthenticatedImageRequestWithJsonDataAndNoLegacyFields() {
        var bytes=OpenAiTestServer.image("png"); server.replies.add(new OpenAiTestServer.Reply(200,OpenAiTestServer.body(bytes),
                Map.of("x-request-id","req_safe_123"),null,null));
        var input=OpenAiTestServer.input();
        try(var capture=new LogCapture(OpenAiImageGenerator.class);
                var generator=server.generator(Duration.ofSeconds(2),4)) {
            assertThat(await(generator.generate(input))).containsExactly(bytes);
            assertThat(server.received).hasSize(1);
            var received=server.received.getFirst();
            assertThat(received.method()).isEqualTo("POST"); assertThat(received.authorization()).isEqualTo("Bearer fake-openai-secret");
            var request=JsonMapper.builder().build().readTree(received.body());
            assertThat(request.path("n").asInt()).isEqualTo(1);
            assertThat(request.path("model").asString()).isEqualTo("gpt-image-1-mini");
            assertThat(request.path("output_format").asString()).isEqualTo("png");
            assertThat(request.path("size").asString()).isEqualTo("1024x1024");
            assertThat(request.path("quality").asString()).isEqualTo("low");
            assertThat(request.path("stream").asBoolean()).isFalse();
            assertThat(request.has("response_format")).isFalse(); assertThat(request.has("style")).isFalse();
            assertThat(request.path("prompt").asString()).contains("SCENE","AI_BRIDGE","watercolor","calm","private-story-scene");
            var message=completedLog(capture,input);
            assertThat(message).contains("requestId=req_safe_123","outcome=BYTES_RECEIVED","status=200");
            assertThat(String.join("\n",capture.messages())).doesNotContain("fake-openai-secret","private-story-scene","private-story-bridge",Base64.getEncoder().encodeToString(bytes));
        }
    }

    @ParameterizedTest @CsvSource({"401,PROVIDER_AUTH_FAILED","403,PROVIDER_AUTH_FAILED","429,PROVIDER_RATE_LIMITED",
            "500,PROVIDER_UNAVAILABLE","503,PROVIDER_UNAVAILABLE","400,PROVIDER_REJECTED","302,PROVIDER_REJECTED"})
    void classifiesHttpErrorsWithoutRetryRedirectOrProviderBodyLeak(int status,String code) {
        server.replies.add(new OpenAiTestServer.Reply(status,"private-error-body",Map.of("Location",server.endpoint().toString(),
                "x-request-id","unsafe.private.header"),null,null));
        var input=OpenAiTestServer.input();
        try(var capture=new LogCapture(OpenAiImageGenerator.class);
                var generator=server.generator(Duration.ofSeconds(2),4)) {
            assertThatThrownBy(()->await(generator.generate(input)))
                    .isInstanceOfSatisfying(ImageProviderException.class,ex -> assertThat(ex.failureCode()).isEqualTo(code)).hasNoCause();
            assertThat(server.received).hasSize(1);
            assertThat(completedLog(capture,input)).contains("requestId=unavailable","outcome="+code,"status="+status);
            assertThat(String.join("\n",capture.messages())).doesNotContain("private-error-body","unsafe.private.header","fake-openai-secret");
        }
    }

    private static String completedLog(LogCapture capture,ImageGenerator.Input input) {
        String attempt="attemptId="+input.attemptId()+",";
        // 결과 Future와 완료 로그의 실행 순서는 보장되지 않는다. 해당 호출의 실제 로그를 기다린다.
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                assertThat(capture.messages()).anyMatch(message -> message.contains(attempt)));
        var messages=capture.messages().stream().filter(message -> message.contains(attempt)).toList();
        assertThat(messages).hasSize(1);
        return messages.getFirst();
    }

    @ParameterizedTest @ValueSource(strings={"{}","not-json","{\"data\":[]}","{\"data\":[{},{}]}",
            "{\"data\":[{\"url\":\"https://private.example/image\"}]}","{\"data\":[{\"b64_json\":123}]}",
            "{\"data\":[{\"b64_json\":\"!invalid!\"}]}","{\"data\":[{\"b64_json\":\"\"}]}"})
    void rejectsMalformedOrNonSingleBase64Output(String body) {
        server.replies.add(new OpenAiTestServer.Reply(200,body));
        try(var generator=server.generator(Duration.ofSeconds(2),4)) {
            assertThatThrownBy(()->await(generator.generate(OpenAiTestServer.input())))
                    .isInstanceOfSatisfying(BusinessException.class,ex -> assertThat(ex.getErrorCode()).isEqualTo(ImageErrorCode.INVALID_OUTPUT)).hasNoCause();
        }
    }

    @Test void publicCancellationDoesNotCancelInternalCallAndCapacityIsBounded() throws Exception {
        var gate=new CountDownLatch(1); var body=OpenAiTestServer.body(OpenAiTestServer.image("png"));
        for(int i=0;i<4;i++) server.replies.add(new OpenAiTestServer.Reply(200,body,Map.of(),gate,null));
        try(var generator=server.generator(Duration.ofSeconds(3),4)) {
            var futures=new ArrayList<CompletableFuture<byte[]>>();
            for(int i=0;i<4;i++) futures.add(generator.generate(OpenAiTestServer.input()));
            received(4); futures.forEach(future->assertThat(future).isNotDone());
            futures.getFirst().cancel(true);
            assertThatThrownBy(()->await(generator.generate(OpenAiTestServer.input()))).isInstanceOf(ImageProviderException.class);
            assertThat(server.received).hasSize(4); gate.countDown();
            for(int i=1;i<4;i++) assertThat(await(futures.get(i))).containsExactly(OpenAiTestServer.image("png"));
            server.replies.add(new OpenAiTestServer.Reply(200,body));
            assertThat(await(generator.generate(OpenAiTestServer.input()))).containsExactly(OpenAiTestServer.image("png"));
        } finally { gate.countDown(); }
    }

    @Test void fullBodyDeadlineFailsStalledBodyOnceWithoutAutomaticRetry() throws Exception {
        var gate=new CountDownLatch(1);
        server.replies.add(new OpenAiTestServer.Reply(200,OpenAiTestServer.body(OpenAiTestServer.image("png")),Map.of(),null,gate));
        try(var generator=server.generator(Duration.ofMillis(350),4)) {
            var pending=generator.generate(OpenAiTestServer.input()); received(1);
            assertThatThrownBy(()->await(pending)).isInstanceOfSatisfying(ImageProviderException.class,
                    ex->assertThat(ex.failureCode()).isEqualTo("PROVIDER_TIMEOUT"));
            assertThat(server.received).hasSize(1);
        } finally { gate.countDown(); }
    }

    @Test void closeCompletesPendingCallAndRejectsNewCalls() throws Exception {
        var gate=new CountDownLatch(1);
        server.replies.add(new OpenAiTestServer.Reply(200,"{}",Map.of(),gate,null));
        var generator=server.generator(Duration.ofSeconds(3),4);
        try {
            var pending=generator.generate(OpenAiTestServer.input()); received(1); generator.close(); generator.close();
            assertThatThrownBy(()->await(pending)).isInstanceOf(ImageProviderException.class);
            assertThat(generator.available()).isFalse();
            assertThatThrownBy(()->await(generator.generate(OpenAiTestServer.input()))).isInstanceOf(ImageProviderException.class);
        } finally { gate.countDown(); generator.close(); }
    }

    @Test void invalidOptionNeverSendsAnHttpRequest() {
        var input=OpenAiTestServer.input();
        try(var generator=server.generator(Duration.ofSeconds(2),4)) {
            var bad=new ImageGenerator.Input(input.userId(),input.imageId(),input.attemptId(),input.promptVersion(),
                    "unknown",null,input.sections());
            assertThatThrownBy(()->await(generator.generate(bad))).isInstanceOf(BusinessException.class);
            assertThat(server.received).isEmpty();
        }
    }

    @Test void rejectsOversizedHttpBodyAndDoesNotDownloadExternalImageUrls() {
        String oversized="x".repeat(OpenAiImageGenerator.MAX_JSON+1);
        server.replies.add(new OpenAiTestServer.Reply(200,oversized));
        try(var generator=server.generator(Duration.ofSeconds(3),4)) {
            assertThatThrownBy(()->await(generator.generate(OpenAiTestServer.input())))
                    .isInstanceOfSatisfying(BusinessException.class,ex->assertThat(ex.getErrorCode()).isEqualTo(ImageErrorCode.INVALID_OUTPUT));
            assertThat(server.received).hasSize(1);
        }
    }

    @Test void decodesExplicitJpegAndRejectsConflictingOutputMetadata() {
        var initial=OpenAiTestServer.properties(Duration.ofSeconds(2));
        var properties=new OpenAiImageProperties(true,initial.apiKey(),initial.model(),initial.size(),initial.quality(),"jpeg",
                initial.connectTimeout(),initial.totalTimeout(),2,initial.stylePrompts(),initial.moodPrompts());
        byte[] bytes=OpenAiTestServer.image("jpeg");
        server.replies.add(new OpenAiTestServer.Reply(200,OpenAiTestServer.body(bytes)));
        try(var generator=new OpenAiImageGenerator(properties,OpenAiTestServer.options(),new ImageExecutionProperties(4,2),
                OpenAiTestServer.storage(),java.net.http.HttpClient.newHttpClient(),server.endpoint())) {
            assertThat(await(generator.generate(OpenAiTestServer.input()))).containsExactly(bytes);
            var body=JsonMapper.builder().build().readTree(server.received.getFirst().body());
            assertThat(body.path("output_format").asString()).isEqualTo("jpeg");
            server.replies.add(new OpenAiTestServer.Reply(200,"{\"output_format\":\"webp\",\"data\":[{\"b64_json\":\"AQID\"}]}"));
            assertThatThrownBy(()->await(generator.generate(OpenAiTestServer.input()))).isInstanceOf(BusinessException.class);
        }
    }
}
