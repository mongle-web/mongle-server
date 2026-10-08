package com.mongle.backend.domain.ai.liner;

import com.mongle.backend.MongleBackendApplication;
import com.mongle.backend.domain.ai.config.AiAsyncProperties;
import com.mongle.backend.domain.ai.config.AiAsyncResources;
import com.mongle.backend.domain.ai.dto.request.AiGenerationRequest;
import com.mongle.backend.domain.ai.dto.request.AiMessage;
import com.mongle.backend.domain.ai.entity.AiTaskType;
import com.mongle.backend.domain.ai.error.AiGatewayErrorCode;
import com.mongle.backend.domain.ai.error.AiGatewayException;
import com.mongle.backend.domain.user.entity.User;
import com.mongle.backend.domain.user.repository.UserRepository;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.builder.SpringApplicationBuilder;

import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.mongle.backend.domain.ai.support.AiGatewayTestAwait.await;
import static org.assertj.core.api.Assertions.*;

/** 실제 Spring 종료 순서와 H2 커밋, 저장 지연 시 제한 시간·인터럽트 처리를 검증한다. */
class AiGatewayShutdownIntegrationTest {
    @Test
    void springShutdownCommitsQueuedFailureBeforeClosingDatabase() throws Exception {
        var sent = new CountDownLatch(1);
        var releaseBody = new CountDownLatch(1);
        var releaseLogWorker = new CountDownLatch(1);
        var workerStarted = new CountDownLatch(1);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try (var providerExecutor = Executors.newVirtualThreadPerTaskExecutor();
             var closer = Executors.newVirtualThreadPerTaskExecutor()) {
            server.setExecutor(providerExecutor);
            server.createContext("/chat", exchange -> {
                exchange.getRequestBody().readAllBytes();
                sent.countDown();
                try { releaseBody.await(5, TimeUnit.SECONDS); }
                catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
                finally { exchange.close(); }
            });
            server.start();
            String url = "jdbc:h2:mem:shutdown-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE";
            // create-drop이면 검증 전에 테이블이 사라진다. create로 준비해 종료 후 별도 연결에서 행을 읽는다.
            try (var context = new SpringApplicationBuilder(MongleBackendApplication.class).run(
                    "--spring.profiles.active=test", "--server.port=0", "--spring.main.banner-mode=off",
                    "--spring.datasource.url=" + url, "--spring.jpa.hibernate.ddl-auto=create",
                    "--mongle.ai.async.log-threads=1", "--mongle.ai.async.log-queue-capacity=4",
                    "--mongle.ai.liner.api-key=shutdown-test-key",
                    "--mongle.ai.liner.endpoint=http://127.0.0.1:" + server.getAddress().getPort() + "/chat")) {
                var resources = context.getBean(AiAsyncResources.class);
                var user = context.getBean(UserRepository.class).saveAndFlush(User.register("shutdown@example.com"));
                // 저장 풀을 먼저 점유해 종료 실패 행이 큐에 남도록 만든다.
                resources.executeLog(() -> {
                    workerStarted.countDown();
                    try { releaseLogWorker.await(5, TimeUnit.SECONDS); }
                    catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
                });
                assertThat(workerStarted.await(2, TimeUnit.SECONDS)).isTrue();
                var gateway = context.getBean(LinerAiGateway.class);
                var pending = gateway.generate(new AiGenerationRequest(user.getId(), AiTaskType.DREAM_STRUCTURE,
                        "shutdown-v1", List.of(new AiMessage(AiMessage.Role.USER, "모의 입력")), null));
                assertThat(sent.await(2, TimeUnit.SECONDS)).isTrue();
                var closed = closer.submit(context::close);
                assertThatThrownBy(() -> await(pending)).isInstanceOfSatisfying(AiGatewayException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(AiGatewayErrorCode.PROVIDER_UNAVAILABLE));
                assertThat(closed.isDone()).isFalse(); // 종료 요청을 받았지만 로그 배출을 기다리는 중이다.
                releaseLogWorker.countDown();
                closed.get(5, TimeUnit.SECONDS);
            } finally {
                releaseLogWorker.countDown();
                releaseBody.countDown();
            }
            // Hikari/EMF 종료 후 새 JDBC 연결로 실제 커밋을 확인한다. 로그 서비스 호출 여부만 검사하지 않는다.
            try (var connection = DriverManager.getConnection(url, "sa", "");
                 var statement = connection.createStatement();
                 var rows = statement.executeQuery("select error_code, http_attempted from ai_generation_logs")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString("error_code")).isEqualTo("AI_503_3");
                assertThat(rows.getBoolean("http_attempted")).isTrue();
                assertThat(rows.next()).isFalse();
            }
        } finally {
            releaseLogWorker.countDown();
            releaseBody.countDown();
            server.stop(0);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void delayedStorageHasBoundedDrainAndPreservesShutdownInterrupt(boolean interrupt) throws Exception {
        var workerStarted = new CountDownLatch(1);
        var workerInterrupted = new CountDownLatch(1);
        var releaseWorker = new CountDownLatch(1);
        var queuedRan = new AtomicBoolean();
        var drained = new AtomicBoolean(true);
        var interruptPreserved = new AtomicBoolean();
        try (var resources = new AiAsyncResources(new AiAsyncProperties(1, 1, 1, 1, 1))) {
            resources.executeLog(() -> {
                workerStarted.countDown();
                try { releaseWorker.await(5, TimeUnit.SECONDS); }
                catch (InterruptedException exception) { workerInterrupted.countDown(); Thread.currentThread().interrupt(); }
            });
            assertThat(workerStarted.await(2, TimeUnit.SECONDS)).isTrue();
            resources.executeLog(() -> queuedRan.set(true));
            var shutdown = Thread.ofPlatform().start(() -> {
                if (interrupt) Thread.currentThread().interrupt();
                drained.set(resources.drainLogs(Duration.ofMillis(interrupt ? 1000 : 50)));
                interruptPreserved.set(Thread.currentThread().isInterrupted());
            });
            shutdown.join(2000);
            assertThat(shutdown.isAlive()).isFalse();
            assertThat(drained).isFalse();
            assertThat(interruptPreserved.get()).isEqualTo(interrupt);
            assertThat(workerInterrupted.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(queuedRan).isFalse();
            assertThatThrownBy(() -> resources.executeLog(() -> { })).isInstanceOf(RejectedExecutionException.class);
        } finally {
            releaseWorker.countDown();
        }
    }
}
