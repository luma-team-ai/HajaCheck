package com.hajacheck.global.exception;

import com.hajacheck.global.common.ApiResponse;
import jakarta.validation.ConstraintViolationException;
import java.lang.reflect.InvocationTargetException;
import java.util.Map;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.validation.BindException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 로그 위조 방지용(#330) — CR/LF 및 기타 ASCII 제어문자.
     * \p{Cntrl} 는 기본 모드에서 ASCII(0x00-0x1F, 0x7F)만 잡으므로, 일부 로그 뷰어가 개행으로
     * 렌더링하는 유니코드 줄바꿈(U+2028/U+2029/U+0085)을 함께 포함한다.
     */
    private static final Pattern CONTROL_CHARS = Pattern.compile("[\\p{Cntrl}\\u0085\\u2028\\u2029]");
    private static final Pattern POSTGRES_QUOTED_CONSTRAINT =
            Pattern.compile("(?i)\\bconstraint\\s+\"([^\"]+)\"");
    private static final Map<String, ErrorCode> EXPECTED_INTEGRITY_CONFLICTS = Map.of(
            "uq_company_memberships_approved_user", ErrorCode.AUTH_APPROVED_MEMBERSHIP_CONFLICT,
            "uq_user_plans_active_user", ErrorCode.PLAN_ACTIVE_SUBSCRIPTION_CONFLICT,
            "uq_user_plans_active_company", ErrorCode.PLAN_ACTIVE_SUBSCRIPTION_CONFLICT,
            "uq_counsel_tickets_session", ErrorCode.COUNSEL_SESSION_ASSIGNMENT_CONFLICT,
            "uk_reports_inspection_version", ErrorCode.REPORT_VERSION_CONFLICT,
            "reports_inspection_id_version_key", ErrorCode.REPORT_VERSION_CONFLICT);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException e) {
        ErrorCode errorCode = e.getErrorCode();
        return ResponseEntity.status(errorCode.getStatus())
                .body(ApiResponse.fail(errorCode));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidationException(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .orElse(ErrorCode.INVALID_INPUT.getMessage());
        return ResponseEntity.status(ErrorCode.INVALID_INPUT.getStatus())
                .body(ApiResponse.fail(ErrorCode.INVALID_INPUT.name(), message));
    }

    /**
     * @ModelAttribute(multipart 폼) 바인딩/검증 실패는 MethodArgumentNotValidException 이 아니라
     * BindException 으로 던져진다(위 핸들러가 못 잡음) → 여기서 INVALID_INPUT(400) 으로 변환.
     * ⚠️ MethodArgumentNotValidException 이 BindException 의 하위형이지만, 그 전용 핸들러가 더 구체적이라
     * @RequestBody 검증은 위 핸들러가 우선 처리한다(여기로 내려오지 않음).
     */
    @ExceptionHandler(BindException.class)
    public ResponseEntity<ApiResponse<Void>> handleBindException(BindException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .orElse(ErrorCode.INVALID_INPUT.getMessage());
        return ResponseEntity.status(ErrorCode.INVALID_INPUT.getStatus())
                .body(ApiResponse.fail(ErrorCode.INVALID_INPUT.name(), message));
    }

    /**
     * @Validated 로 검증한 요청 파라미터(@RequestParam @NotBlank/@Email 등) 위반 → INVALID_INPUT(400).
     * (BindException 이 아니라 ConstraintViolationException 으로 던져지므로 별도 처리.)
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraintViolation(ConstraintViolationException e) {
        return ResponseEntity.status(ErrorCode.INVALID_INPUT.getStatus())
                .body(ApiResponse.fail(ErrorCode.INVALID_INPUT));
    }

    /**
     * 쿼리 파라미터의 enum 변환 실패(예: GET /api/defects?type=INVALID) → INVALID_INPUT(400).
     * (HAJA-30) 처리하지 않으면 이 클래스가 ResponseEntityExceptionHandler를 상속하지 않는 순수
     * @RestControllerAdvice라 하위 포괄 handleException이 잡아 500으로 새어나간다.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodArgumentTypeMismatch(
            MethodArgumentTypeMismatchException e) {
        return ResponseEntity.status(ErrorCode.INVALID_INPUT.getStatus())
                .body(ApiResponse.fail(ErrorCode.INVALID_INPUT));
    }

    /**
     * @RequestBody JSON 파싱/역직렬화 실패(예: enum에 정의되지 않은 문자열 값) → INVALID_INPUT(400).
     * 위 MethodArgumentTypeMismatchException(쿼리 파라미터)과 대응하는 요청 바디 쪽 케이스 —
     * 처리하지 않으면 하위 포괄 handleException이 잡아 500으로 새어나간다.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleHttpMessageNotReadable(HttpMessageNotReadableException e) {
        return ResponseEntity.status(ErrorCode.INVALID_INPUT.getStatus())
                .body(ApiResponse.fail(ErrorCode.INVALID_INPUT));
    }

    /**
     * multipart 파일 용량 초과(스프링 서블릿 레벨) → FILE_TOO_LARGE(400).
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleMaxUploadSize(MaxUploadSizeExceededException e) {
        return ResponseEntity.status(ErrorCode.FILE_TOO_LARGE.getStatus())
                .body(ApiResponse.fail(ErrorCode.FILE_TOO_LARGE));
    }

    /**
     * multipart 필수 파트 누락 → FILE_REQUIRED(400).
     */
    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingPart(MissingServletRequestPartException e) {
        return ResponseEntity.status(ErrorCode.FILE_REQUIRED.getStatus())
                .body(ApiResponse.fail(ErrorCode.FILE_REQUIRED));
    }

    /**
     * 로그인(AuthenticationManager.authenticate) 실패 통일 처리.
     * BadCredentials/UsernameNotFound/Locked 등을 계정 열거 방지를 위해 401 로 단일화.
     * (미인증 접근은 RestAuthenticationEntryPoint 가 필터 단에서 별도 처리.)
     */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiResponse<Void>> handleAuthenticationException(AuthenticationException e) {
        return ResponseEntity.status(ErrorCode.AUTH_INVALID_CREDENTIALS.getStatus())
                .body(ApiResponse.fail(ErrorCode.AUTH_INVALID_CREDENTIALS));
    }

    /**
     * 매핑되지 않은 경로/정적 리소스 요청(#330). 이 클래스는 ResponseEntityExceptionHandler 를 상속하지 않는
     * 순수 @RestControllerAdvice 라, 전용 핸들러가 없으면 아래 포괄 handleException 이 NoResourceFoundException 을
     * 가로채 500 + 전체 스택트레이스로 처리한다(= 단순 404가 서버 장애로 둔갑 + 로그 노이즈).
     * 따라서 404 로 정정하고, 스택트레이스 없이 WARN 단일 라인만 남긴다.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoResourceFound(NoResourceFoundException e) {
        log.warn("존재하지 않는 리소스 요청: {} {}", e.getHttpMethod(), sanitizeForLog(e.getResourcePath()));
        return ResponseEntity.status(ErrorCode.RESOURCE_NOT_FOUND.getStatus())
                .body(ApiResponse.fail(ErrorCode.RESOURCE_NOT_FOUND));
    }

    /**
     * 매핑은 존재하나 그 경로가 지원하지 않는 HTTP 메서드로 호출된 경우(#330과 동일 유형 — 그때는
     * NoResourceFoundException만 고치고 이 예외는 빠뜨렸었다). 전용 핸들러가 없으면 아래 포괄
     * handleException이 가로채 405가 500 + 전체 스택트레이스로 둔갑한다. 그래서 405로 정정하고,
     * 스택트레이스 없이 WARN 단일 라인만 남긴다.
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotSupported(
            HttpRequestMethodNotSupportedException e) {
        log.warn("지원하지 않는 요청 방식: {}", sanitizeForLog(e.getMethod()));
        return ResponseEntity.status(ErrorCode.METHOD_NOT_ALLOWED.getStatus())
                .body(ApiResponse.fail(ErrorCode.METHOD_NOT_ALLOWED));
    }

    /**
     * 로그 위조(CWE-117) 방지 — 사용자 입력 경로는 URL 디코딩된 값이라 %0d%0a 로 CR/LF 를 실어 보낼 수 있고,
     * Logback 은 파라미터의 제어문자를 이스케이프하지 않아 가짜 로그 라인이 주입될 수 있다.
     * 직접 단위 테스트하기 위해 package-private.
     */
    static String sanitizeForLog(String value) {
        if (value == null) {
            return null;
        }
        return CONTROL_CHARS.matcher(value).replaceAll("_");
    }

    /**
     * 상태 전이 엔티티(@Version 낙관적 락 — HAJA-25)의 동시 갱신 충돌 통일 처리.
     * 이 필드가 도입되기 전에는 read-check-write 경쟁이 조용히 덮어써졌으나(last-write-wins),
     * 이제 동시 갱신은 이 예외로 표면화된다. 500으로 새지 않도록 재시도 유도가 가능한 409로 변환한다.
     */
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ApiResponse<Void>> handleOptimisticLockingFailure(
            ObjectOptimisticLockingFailureException e) {
        log.warn("낙관적 락 충돌: {}", e.getMessage());
        return ResponseEntity.status(ErrorCode.CONCURRENT_UPDATE_CONFLICT.getStatus())
                .body(ApiResponse.fail(ErrorCode.CONCURRENT_UPDATE_CONFLICT));
    }

    /**
     * HAJA-25에서 추가된 부분 유니크 인덱스 위반 중 사용자 경합으로 예상 가능한 제약만 409로 변환한다.
     * 그 밖의 무결성 위반은 데이터/프로그래밍 오류일 수 있으므로 무차별 409로 숨기지 않고 기존 500을 유지한다.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleDataIntegrityViolation(DataIntegrityViolationException e) {
        ErrorCode errorCode = findExpectedIntegrityConflict(e);
        if (errorCode == null) {
            log.error("처리되지 않은 데이터 무결성 위반", e);
            return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.getStatus())
                    .body(ApiResponse.fail(ErrorCode.INTERNAL_ERROR));
        }

        log.warn("예상 가능한 데이터 무결성 충돌: {}", errorCode.name());
        return ResponseEntity.status(errorCode.getStatus())
                .body(ApiResponse.fail(errorCode));
    }

    private ErrorCode findExpectedIntegrityConflict(DataIntegrityViolationException exception) {
        Throwable cause = exception;
        while (cause != null) {
            if (cause instanceof org.hibernate.exception.ConstraintViolationException constraintViolation) {
                String constraintName = constraintViolation.getConstraintName();
                if (constraintName != null) {
                    return EXPECTED_INTEGRITY_CONFLICTS.get(constraintName);
                }
            }
            if (isPostgresSqlException(cause)) {
                String constraintName = getPostgresConstraintName(cause);
                if (constraintName != null) {
                    return EXPECTED_INTEGRITY_CONFLICTS.get(constraintName);
                }
            }
            cause = cause.getCause();
        }

        String message = exception.getMostSpecificCause().getMessage();
        if (message == null) {
            return null;
        }
        java.util.regex.Matcher matcher = POSTGRES_QUOTED_CONSTRAINT.matcher(message);
        return matcher.find() ? EXPECTED_INTEGRITY_CONFLICTS.get(matcher.group(1)) : null;
    }

    private boolean isPostgresSqlException(Throwable cause) {
        Class<?> type = cause.getClass();
        while (type != null) {
            if ("org.postgresql.util.PSQLException".equals(type.getName())) {
                return true;
            }
            type = type.getSuperclass();
        }
        return false;
    }

    /**
     * PostgreSQL JDBC 드라이버는 runtimeOnly 의존성이므로 컴파일 타임 결합 없이 구조화된 constraint 필드를 읽는다.
     */
    private String getPostgresConstraintName(Throwable postgresException) {
        try {
            Object serverErrorMessage = postgresException.getClass()
                    .getMethod("getServerErrorMessage")
                    .invoke(postgresException);
            if (serverErrorMessage == null) {
                return null;
            }
            return (String) serverErrorMessage.getClass().getMethod("getConstraint").invoke(serverErrorMessage);
        } catch (IllegalAccessException | InvocationTargetException | NoSuchMethodException | ClassCastException e) {
            log.debug("PostgreSQL 구조화 제약명을 읽지 못함", e);
            return null;
        }
    }

    /**
     * Entity 도메인 메서드의 명시적인 입력 검증 실패만 400으로 변환한다.
     * 표준 IllegalArgumentException은 프로그래밍 오류일 수 있으므로 이 핸들러가 잡지 않으며 500으로 처리한다.
     * 민감한 입력 조각이 로그에 남지 않도록 예외 메시지 자체도 기록하지 않는다.
     */
    @ExceptionHandler(DomainValidationException.class)
    public ResponseEntity<ApiResponse<Void>> handleDomainValidationException(DomainValidationException e) {
        log.warn("도메인 입력값 검증 실패: {}", e.getClass().getSimpleName());
        return ResponseEntity.status(ErrorCode.INVALID_INPUT.getStatus())
                .body(ApiResponse.fail(ErrorCode.INVALID_INPUT));
    }

    /**
     * Entity 도메인 메서드의 명시적인 상태 전이 가드 위반만 409로 변환한다.
     * 표준 IllegalStateException은 프로그래밍 오류일 수 있으므로 이 핸들러가 잡지 않으며 500으로 처리한다.
     */
    @ExceptionHandler(DomainStateTransitionException.class)
    public ResponseEntity<ApiResponse<Void>> handleDomainStateTransitionException(DomainStateTransitionException e) {
        log.warn("잘못된 상태 전이 요청: {}", e.getMessage());
        return ResponseEntity.status(ErrorCode.INVALID_STATE_TRANSITION.getStatus())
                .body(ApiResponse.fail(ErrorCode.INVALID_STATE_TRANSITION));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleException(Exception e) {
        log.error("처리되지 않은 예외 발생", e);
        return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.getStatus())
                .body(ApiResponse.fail(ErrorCode.INTERNAL_ERROR));
    }
}
