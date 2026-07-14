package com.hajacheck.auth.controller;

import com.hajacheck.auth.dto.FindIdRequest;
import com.hajacheck.auth.dto.FindIdResponse;
import com.hajacheck.auth.dto.PasswordInquiryRequest;
import com.hajacheck.auth.dto.PasswordInquiryResponse;
import com.hajacheck.auth.dto.PasswordResetRequest;
import com.hajacheck.auth.service.AccountRecoveryService;
import com.hajacheck.global.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 아이디 찾기 + 비밀번호 찾기(1·2단계). 모든 경로 /api/auth/** (permitAll 커버).
 * 실패는 400/404 만 사용(401 금지). 계정 열거 방지로 실패 메시지 통일.
 */
@Tag(name = "AccountRecovery", description = "아이디/비밀번호 찾기 API")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AccountRecoveryController {

    private final AccountRecoveryService accountRecoveryService;

    @Operation(summary = "아이디(이메일) 찾기", description = "사업자번호 + 대표자명|상호명 매칭 → 마스킹 이메일")
    @PostMapping("/id-inquiry")
    public ResponseEntity<ApiResponse<FindIdResponse>> findId(
            @Valid @RequestBody FindIdRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(accountRecoveryService.findId(request)));
    }

    @Operation(summary = "비밀번호 찾기 1단계", description = "이메일+사업자번호 인증 → 재설정 토큰 발급")
    @PostMapping("/password-inquiry")
    public ResponseEntity<ApiResponse<PasswordInquiryResponse>> passwordInquiry(
            @Valid @RequestBody PasswordInquiryRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(accountRecoveryService.verifyForPasswordReset(request)));
    }

    @Operation(summary = "비밀번호 찾기 2단계", description = "재설정 토큰 소비(단일 사용) 후 비밀번호 변경")
    @PostMapping("/password-reset")
    public ResponseEntity<ApiResponse<Void>> passwordReset(
            @Valid @RequestBody PasswordResetRequest request) {
        accountRecoveryService.resetPassword(request);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }
}
