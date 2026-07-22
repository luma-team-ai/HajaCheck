package com.hajacheck.global.exception;

import static org.assertj.core.api.Assertions.assertThat;

import com.hajacheck.global.common.ApiResponse;
import java.sql.SQLException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
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
    @DisplayName("지원하지 않는 HTTP 메서드 요청은 500이 아니라 405 + METHOD_NOT_ALLOWED 로 응답한다")
    void handleMethodNotSupported_returns405() {
        // GET /api/inspections(목록 조회는 미구현, POST만 존재) 재현 — 실사용자가 겪은 회귀.
        HttpRequestMethodNotSupportedException e = new HttpRequestMethodNotSupportedException("GET");

        ResponseEntity<ApiResponse<Void>> response = handler.handleMethodNotSupported(e);

        // 포괄 handleException 이 가로채면 500(INTERNAL_ERROR) 이 된다 — 그 회귀를 막는다.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().success()).isFalse();
        assertThat(response.getBody().data()).isNull();
        assertThat(response.getBody().error().code()).isEqualTo(ErrorCode.METHOD_NOT_ALLOWED.name());
    }

    @Test
    @DisplayName("로그용 살균이 CR/LF 등 제어문자를 제거해 로그 위조를 막는다(CWE-117)")
    void sanitizeForLog_stripsControlChars() {
        // %0d%0a 는 디코딩되어 실제 CR/LF 로 도달 — 그대로 찍으면 가짜 로그 라인이 주입된다.
        String malicious = "/api/x\r\n2026-07-17 INFO 위조된-로그-라인\tTAB";

        String sanitized = GlobalExceptionHandler.sanitizeForLog(malicious);

        assertThat(sanitized).doesNotContain("\r").doesNotContain("\n").doesNotContain("\t");
        assertThat(sanitized).isEqualTo("/api/x__2026-07-17 INFO 위조된-로그-라인_TAB");
        assertThat(GlobalExceptionHandler.sanitizeForLog(null)).isNull();

        // 일부 로그 뷰어가 개행으로 렌더링하는 유니코드 줄바꿈도 제거한다(\p{Cntrl} 는 ASCII 만 잡음).
        // U+0085 NEL / U+2028 LINE SEPARATOR / U+2029 PARAGRAPH SEPARATOR.
        // 비가시 문자를 소스에 직접 넣으면 에디터가 공백으로 정규화해버리므로 명시적 이스케이프로 고정한다.
        assertThat(GlobalExceptionHandler.sanitizeForLog("/a\u0085b\u2028c\u2029d"))
                .isEqualTo("/a_b_c_d");
    }

    @Test
    @DisplayName("경로에 CRLF 가 실려도 404 응답은 유지된다")
    void handleNoResourceFound_crlfInPath_stillReturns404() {
        NoResourceFoundException e = new NoResourceFoundException(
                HttpMethod.GET, "/api/x\r\nINFO 위조");

        ResponseEntity<ApiResponse<Void>> response = handler.handleNoResourceFound(e);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error().code()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND.name());
    }

    @Test
    @DisplayName("포괄 핸들러는 기존대로 500 INTERNAL_ERROR 를 유지한다")
    void handleException_returns500() {
        ResponseEntity<ApiResponse<Void>> response = handler.handleException(new IllegalStateException("boom"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().success()).isFalse();
    }

    /**
     * multipart 전역 한도(max-file-size 20MB/max-request-size 205MB, 리뷰 P2) 상향 이후에도, 한도
     * 초과 업로드가 서블릿 단계에서 raw 500이 아니라 FILE_TOO_LARGE(400)의 ApiResponse 계약으로
     * 매핑되는지 고정한다. MockMvc의 MockMultipartHttpServletRequest는 실 컨테이너의
     * MultipartConfigElement 크기 검증을 거치지 않아 대용량 업로드로 이 예외를 실제 재현할 수 없으므로,
     * 핸들러 매핑 자체를 직접 검증한다.
     */
    @Test
    void handleMaxUploadSize_FILE_TOO_LARGE_400_ApiResponse계약으로매핑() {
        MaxUploadSizeExceededException e = new MaxUploadSizeExceededException(20_971_520L);

        ResponseEntity<ApiResponse<Void>> response = handler.handleMaxUploadSize(e);

        assertThat(response.getStatusCode()).isEqualTo(ErrorCode.FILE_TOO_LARGE.getStatus());
        assertThat(response.getBody().success()).isFalse();
        assertThat(response.getBody().error().code()).isEqualTo(ErrorCode.FILE_TOO_LARGE.name());
    }

    @Test
    void handleOptimisticLockingFailure_mapsToConflictWithUnifiedErrorCode() {
        // Arrange
        ObjectOptimisticLockingFailureException exception =
                new ObjectOptimisticLockingFailureException("Report", 1L);

        // Act
        ResponseEntity<ApiResponse<Void>> response =
                handler.handleOptimisticLockingFailure(exception);

        // Assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().error().code())
                .isEqualTo(ErrorCode.CONCURRENT_UPDATE_CONFLICT.name());
    }

    @Test
    void handleDataIntegrityViolation_승인멤버십제약명_AUTH충돌로매핑() {
        DataIntegrityViolationException exception = constraintViolation(
                "uq_company_memberships_approved_user");

        ResponseEntity<ApiResponse<Void>> response = handler.handleDataIntegrityViolation(exception);

        assertConflict(response, ErrorCode.AUTH_APPROVED_MEMBERSHIP_CONFLICT);
    }

    @Test
    void handleDataIntegrityViolation_사용자활성플랜제약명_PLAN충돌로매핑() {
        DataIntegrityViolationException exception = constraintViolation("uq_user_plans_active_user");

        ResponseEntity<ApiResponse<Void>> response = handler.handleDataIntegrityViolation(exception);

        assertConflict(response, ErrorCode.PLAN_ACTIVE_SUBSCRIPTION_CONFLICT);
    }

    @Test
    void handleDataIntegrityViolation_회사활성플랜메시지Fallback_PLAN충돌로매핑() {
        DataIntegrityViolationException exception = new DataIntegrityViolationException(
                "could not execute statement",
                new SQLException("duplicate key value violates unique constraint \"uq_user_plans_active_company\""));

        ResponseEntity<ApiResponse<Void>> response = handler.handleDataIntegrityViolation(exception);

        assertConflict(response, ErrorCode.PLAN_ACTIVE_SUBSCRIPTION_CONFLICT);
    }

    @Test
    void handleDataIntegrityViolation_상담세션제약명_COUNSEL충돌로매핑() {
        DataIntegrityViolationException exception = constraintViolation("uq_counsel_tickets_session");

        ResponseEntity<ApiResponse<Void>> response = handler.handleDataIntegrityViolation(exception);

        assertConflict(response, ErrorCode.COUNSEL_SESSION_ASSIGNMENT_CONFLICT);
    }

    @Test
    void handleDataIntegrityViolation_보고서버전제약명_REPORT충돌로매핑() {
        // 동시 초안 생성이 같은 버전으로 저장을 시도하면 uk_reports_inspection_version 또는 reports_inspection_id_version_key 유니크 제약이 막는다 —
        // 500(INTERNAL_ERROR)이 아니라 재시도 유도가 가능한 409(REPORT_VERSION_CONFLICT)로 매핑돼야 한다(#455 P2-1).
        DataIntegrityViolationException exception = constraintViolation("uk_reports_inspection_version");
        DataIntegrityViolationException defaultPgException = constraintViolation("reports_inspection_id_version_key");

        ResponseEntity<ApiResponse<Void>> response = handler.handleDataIntegrityViolation(exception);
        ResponseEntity<ApiResponse<Void>> defaultPgResponse = handler.handleDataIntegrityViolation(defaultPgException);

        assertConflict(response, ErrorCode.REPORT_VERSION_CONFLICT);
        assertConflict(defaultPgResponse, ErrorCode.REPORT_VERSION_CONFLICT);
    }

    @Test
    void handleDataIntegrityViolation_알수없는구조화제약의Detail에허용이름포함_INTERNAL_ERROR유지() {
        DataIntegrityViolationException exception = new DataIntegrityViolationException(
                "could not execute statement",
                new org.hibernate.exception.ConstraintViolationException(
                        "duplicate key value violates unique constraint \"uq_actual_unknown\"; "
                                + "Detail: Key (memo)=(uq_user_plans_active_user)",
                        new SQLException("Detail: Key (memo)=(uq_user_plans_active_user)"),
                        "uq_actual_unknown"));

        ResponseEntity<ApiResponse<Void>> response = handler.handleDataIntegrityViolation(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error().code()).isEqualTo(ErrorCode.INTERNAL_ERROR.name());
        assertThat(response.getBody().error().message()).isEqualTo(ErrorCode.INTERNAL_ERROR.getMessage());
        assertThat(response.getBody().error().message()).doesNotContain("uq_user_plans_active_user");
    }

    @Test
    void handleDataIntegrityViolation_허용이름보다긴인용제약_INTERNAL_ERROR유지() {
        DataIntegrityViolationException exception = new DataIntegrityViolationException(
                "could not execute statement",
                new SQLException(
                        "duplicate key value violates unique constraint \"uq_user_plans_active_user_archive\""));

        ResponseEntity<ApiResponse<Void>> response = handler.handleDataIntegrityViolation(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error().code()).isEqualTo(ErrorCode.INTERNAL_ERROR.name());
    }

    @Test
    void handleDomainValidationException_mapsToInvalidInput() {
        ResponseEntity<ApiResponse<Void>> response =
                handler.handleDomainValidationException(new DomainValidationException("보고서 본문(contentJson)는 유효한 JSON이어야 한다"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().error().code())
                .isEqualTo(ErrorCode.INVALID_INPUT.name());
    }

    @Test
    void handleDomainStateTransitionException_mapsToInvalidStateTransition() {
        ResponseEntity<ApiResponse<Void>> response =
                handler.handleDomainStateTransitionException(new DomainStateTransitionException("이미 확정된 보고서는 수정할 수 없다"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().error().code())
                .isEqualTo(ErrorCode.INVALID_STATE_TRANSITION.name());
    }

    @Test
    void unexpectedStandardExceptions_fallBackToInternalError() {
        ResponseEntity<ApiResponse<Void>> argumentResponse =
                handler.handleException(new IllegalArgumentException("library bug"));
        ResponseEntity<ApiResponse<Void>> stateResponse =
                handler.handleException(new IllegalStateException("initialization bug"));

        assertThat(argumentResponse.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(argumentResponse.getBody().error().code()).isEqualTo(ErrorCode.INTERNAL_ERROR.name());
        assertThat(stateResponse.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(stateResponse.getBody().error().code()).isEqualTo(ErrorCode.INTERNAL_ERROR.name());
    }

    private static DataIntegrityViolationException constraintViolation(String constraintName) {
        return new DataIntegrityViolationException(
                "could not execute statement",
                new org.hibernate.exception.ConstraintViolationException(
                        "duplicate key value violates unique constraint",
                        new SQLException("duplicate key"),
                        constraintName));
    }

    private static void assertConflict(ResponseEntity<ApiResponse<Void>> response, ErrorCode errorCode) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().success()).isFalse();
        assertThat(response.getBody().error().code()).isEqualTo(errorCode.name());
        assertThat(response.getBody().error().message()).isEqualTo(errorCode.getMessage());
    }
}
