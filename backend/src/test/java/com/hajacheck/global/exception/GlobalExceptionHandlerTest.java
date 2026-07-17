package com.hajacheck.global.exception;

import static org.assertj.core.api.Assertions.assertThat;

import com.hajacheck.global.common.ApiResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * GlobalExceptionHandler 단위 테스트(#330). 스프링 컨텍스트·DB 불요 — 핸들러 계약만 검증한다.
 * 실제 디스패처 체인에서 NoResourceFoundException 이 이 핸들러로 도달하는지는
 * NotFoundRoutingIntegrationTest(@SpringBootTest) 가 검증한다.
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("미존재 리소스 요청은 500이 아니라 404 + RESOURCE_NOT_FOUND 로 응답한다")
    void handleNoResourceFound_returns404() {
        NoResourceFoundException e = new NoResourceFoundException(HttpMethod.GET, "/api/does-not-exist");

        ResponseEntity<ApiResponse<Void>> response = handler.handleNoResourceFound(e);

        // 포괄 handleException 이 가로채면 500(INTERNAL_ERROR) 이 된다 — 그 회귀를 막는다.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().success()).isFalse();
        assertThat(response.getBody().data()).isNull();
        assertThat(response.getBody().error().code()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND.name());
        // 요청 경로가 응답 메시지로 새어나가지 않아야 한다(내부 경로 유추 방지).
        assertThat(response.getBody().error().message()).doesNotContain("/api/does-not-exist");
    }

    @Test
    @DisplayName("포괄 핸들러는 기존대로 500 INTERNAL_ERROR 를 유지한다")
    void handleException_returns500() {
        ResponseEntity<ApiResponse<Void>> response = handler.handleException(new IllegalStateException("boom"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().success()).isFalse();
    }
}
