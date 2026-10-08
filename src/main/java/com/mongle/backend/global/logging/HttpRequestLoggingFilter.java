package com.mongle.backend.global.logging;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

import java.io.IOException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** 보안 필터의 거절까지 포함해 HTTP 요청 완료를 기록한다. 본문·쿼리·쿠키·인증 헤더는 읽지 않는다. */
@Slf4j
public class HttpRequestLoggingFilter extends OncePerRequestFilter {
    public static final String REQUEST_ID = "requestId";
    public static final String HEADER = "X-Request-Id";
    private static final String STATE = HttpRequestLoggingFilter.class.getName() + ".state";

    @Override
    protected boolean shouldNotFilterAsyncDispatch() { return false; }

    @Override
    protected boolean shouldNotFilterErrorDispatch() { return false; }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var state = (RequestLog) request.getAttribute(STATE);
        if (state == null) {
            // 클라이언트가 준 식별자를 신뢰하지 않고 서버가 생성한다. 재디스패치에서는 같은 값을 재사용한다.
            state = new RequestLog(request, response);
            request.setAttribute(STATE, state);
        }
        response.setHeader(HEADER, state.id);
        String previous = MDC.get(REQUEST_ID);
        MDC.put(REQUEST_ID, state.id);
        try {
            chain.doFilter(new LoggingRequest(request, state), response);
        } catch (IOException | ServletException | RuntimeException exception) {
            // 스택과 예외 메시지는 공통 예외 처리의 책임이다. 여기서는 요청 처리 결과만 요약한다.
            state.errorType = exception.getClass().getSimpleName();
            throw exception;
        } finally {
            // 비동기는 첫 디스패치 반환이 요청 완료가 아니다. AsyncListener가 최종 완료를 기록한다.
            if (!state.async.get()) state.complete();
            // 재사용되는 서버 스레드에 이전 요청의 식별자가 남지 않도록 원래 컨텍스트로 복구한다.
            if (previous == null) MDC.remove(REQUEST_ID);
            else MDC.put(REQUEST_ID, previous);
        }
    }

    /** startAsync 직후 리스너를 붙여 빠른 Future 완료도 놓치지 않는다. */
    private static final class LoggingRequest extends HttpServletRequestWrapper {
        private final RequestLog state;

        private LoggingRequest(HttpServletRequest request, RequestLog state) {
            super(request);
            this.state = state;
        }

        @Override
        public AsyncContext startAsync() { return track(super.startAsync()); }

        @Override
        public AsyncContext startAsync(ServletRequest request, ServletResponse response) {
            return track(super.startAsync(request, response));
        }

        private AsyncContext track(AsyncContext context) {
            if (state.async.compareAndSet(false, true)) context.addListener(state);
            return context;
        }
    }

    private static final class RequestLog implements AsyncListener {
        private final String id = UUID.randomUUID().toString();
        private final long started = System.nanoTime();
        private final HttpServletRequest request;
        private final HttpServletResponse response;
        private final AtomicBoolean async = new AtomicBoolean();
        private final AtomicBoolean completed = new AtomicBoolean();
        private volatile String errorType = "none";

        private RequestLog(HttpServletRequest request, HttpServletResponse response) {
            this.request = request;
            this.response = response;
        }

        private void complete() {
            // ASYNC/ERROR 재디스패치와 중복 알림에도 HTTP 요청 하나당 완료 로그는 한 번만 기록한다.
            if (!completed.compareAndSet(false, true)) return;
            Object route = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
            String path = route == null ? request.getRequestURI() : route.toString();
            int status = response.getStatus();
            if (!async.get() && !errorType.equals("none") && status < 500) status = 500;
            var event = status >= 500 ? log.atError() : status >= 400 ? log.atWarn() : log.atInfo();
            event.log("HTTP 요청 완료: requestId={}, method={}, path={}, status={}, elapsedMs={}, errorType={}",
                    id, LogValues.safe(request.getMethod()), LogValues.safe(path), status,
                    Duration.ofNanos(System.nanoTime() - started).toMillis(), errorType);
        }

        @Override
        public void onComplete(AsyncEvent event) { complete(); }

        @Override
        public void onTimeout(AsyncEvent event) { errorType = "AsyncTimeout"; }

        @Override
        public void onError(AsyncEvent event) {
            errorType = event.getThrowable() == null ? "AsyncError" : event.getThrowable().getClass().getSimpleName();
        }

        @Override
        public void onStartAsync(AsyncEvent event) {
            // 다음 비동기 사이클에는 리스너가 자동 유지되지 않아 다시 등록한다.
            event.getAsyncContext().addListener(this);
        }
    }
}
