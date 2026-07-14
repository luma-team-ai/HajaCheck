package com.hajacheck.global.exception;

import com.hajacheck.global.common.ApiResponse;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.validation.BindException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

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

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleException(Exception e) {
        log.error("처리되지 않은 예외 발생", e);
        return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.getStatus())
                .body(ApiResponse.fail(ErrorCode.INTERNAL_ERROR));
    }
}
