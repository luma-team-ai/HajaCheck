package com.hajacheck.auth.controller;

import com.hajacheck.auth.dto.FindIdRequest;
import com.hajacheck.auth.dto.FindIdResponse;
import com.hajacheck.auth.dto.PasswordResetLinkRequest;
import com.hajacheck.auth.dto.PasswordResetLinkResponse;
import com.hajacheck.auth.dto.PasswordResetRequest;
import com.hajacheck.auth.dto.PasswordResetResponse;
import com.hajacheck.auth.service.AccountRecoveryService;
import com.hajacheck.auth.service.PasswordResetService;
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
 * 계정 찾기 — 아이디(이메일) 찾기 + 비밀번호 재설정 1·2단계.
 * 경로 /api/auth/** (SecurityConfig permitAll 커버). 계정 열거 방지로 실패 메시지를 통일한다.
 *
 * <p>⚠️ <b>검증 실패에 401 을 쓰지 않는다</b>(계약 공통 규약) — 프론트 axios 가 401 을 로그인 강제
 * 리다이렉트로 처리해, 재설정 화면에서 401 이 나가면 사용자가 폼째로 튕긴다. 400/404/429 만 사용.
 */
@Tag(name = "AccountRecovery", description = "아이디 찾기 · 비밀번호 재설정 API")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AccountRecoveryController {

    private final AccountRecoveryService accountRecoveryService;
    private final PasswordResetService passwordResetService;

    @Operation(summary = "아이디(이메일) 찾기", description = "사업자번호 + 대표자명|상호명 매칭 → 마스킹 이메일")
    @PostMapping("/id-inquiry")
    public ResponseEntity<ApiResponse<FindIdResponse>> findId(
            @Valid @RequestBody FindIdRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(accountRecoveryService.findId(request)));
    }

    /**
     * 1단계 — 재설정 링크 발송 요청. 계정 존재 여부와 무관하게 항상 200 {requested:true}.
     * ⚠️ 응답에 resetToken 을 절대 싣지 않는다(최초 P1 재발 방지) — 토큰은 메일로만 간다.
     */
    @Operation(summary = "비밀번호 재설정 링크 발송 요청",
            description = "계정이 존재할 때만 메일을 발송한다. 계정 열거 방지를 위해 존재 여부와 무관하게 동일한 200 응답을 준다. "
                    + "재설정 토큰은 응답에 포함되지 않으며 메일로만 전달된다. 초과 요청은 429.")
    @PostMapping("/password-reset-request")
    public ResponseEntity<ApiResponse<PasswordResetLinkResponse>> requestPasswordResetLink(
            @Valid @RequestBody PasswordResetLinkRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(passwordResetService.requestResetLink(request)));
    }

    /** 2단계 — 메일 링크의 토큰으로 새 비밀번호 설정. 토큰은 1회용이며 소비 즉시 무효화된다. */
    @Operation(summary = "새 비밀번호 설정",
            description = "메일 링크의 토큰을 소비해 비밀번호를 변경한다. 토큰 무효·만료·사용됨은 구분 없이 "
                    + "AUTH_RESET_TOKEN_INVALID(400) 로 통일 응답한다.")
    @PostMapping("/password-reset")
    public ResponseEntity<ApiResponse<PasswordResetResponse>> resetPassword(
            @Valid @RequestBody PasswordResetRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(passwordResetService.reset(request)));
    }
}
