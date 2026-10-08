package com.mongle.backend.domain.ai.gateway;

import com.mongle.backend.domain.ai.dto.request.AiGenerationRequest;
import com.mongle.backend.domain.ai.dto.response.AiGenerationResult;

import java.util.concurrent.CompletableFuture;

/**
 * 도메인 서비스가 사용하는 비동기·비스트리밍 텍스트/JSON 생성 계약.
 * 호출은 응답을 기다리지 않고 Future를 반환한다. 결과가 준비됐을 때 실행할 처리를 연결해 사용한다.
 *
 * <p>정상 완료 값은 생성 원문과 메타데이터이며, 외부 실패는 Future의 예외 완료로 전달한다.
 * 도메인 DTO 변환·업무 검증·결과 저장은 호출부의 책임이다. 해당 작업에 트랜잭션이 필요하면
 * 완료 처리용 작업 스레드에서 새로 시작한다. 호출부에서 get()/join()으로 기다리면 다시 블로킹된다.</p>
 */
public interface AiGateway {
    /**
     * @param request 도메인이 작성한 메시지·출력 스키마·호출 맥락. null은 내부 계약 위반이다.
     * @return 일반 호출은 로그 저장 시도까지 끝나면 완료되는 결과. 제공자 실패·용량 초과도 예외 완료로 전달한다.
     * 결과는 ai-log-*·ai-response-* 등 내부 작업 스레드에서 완료될 수 있으며 완료 스레드는 고정하지 않는다.
     * thenAccept()/thenApply() 같은 비-Async 후속 동작은 완료 스레드에서 실행될 수 있다.
     * 이미 완료된 Future에 연결하면 등록 스레드에서도 실행될 수 있으므로 느린 작업에 이 동작을 사용하지 않는다.
     * DB 저장·긴 업무 처리는 호출 도메인의 실행기를 지정한 thenAcceptAsync()/thenApplyAsync()로 연결한다.
     * 취소·서버 종료는 결과를 먼저 완료하며, 진행 중이던 시도의 로그 저장은 가능한 범위에서 시도한다.
     * 호출부는 완료 처리 연결 또는 cancel()만 사용하고 complete()/obtrudeValue()로 결과를 덮어쓰지 않는다.
     * 취소는 진행 중인 로컬 HTTP와 재시도를 중단하지만 제공자의 생성·과금 취소를 보장하지 않는다.
     */
    CompletableFuture<AiGenerationResult> generate(AiGenerationRequest request);
}
