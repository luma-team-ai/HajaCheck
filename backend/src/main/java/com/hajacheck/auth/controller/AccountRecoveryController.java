package com.hajacheck.auth.controller;

import com.hajacheck.auth.dto.FindIdRequest;
import com.hajacheck.auth.dto.FindIdResponse;
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
 * 아이디(이메일) 찾기. 경로 /api/auth/** (permitAll 커버). 실패는 404 만 사용(401 금지), 계정 열거 방지 메시지 통일.
 *
 * <p>⚠️ 비밀번호 찾기(1·2단계)는 이메일+사업자번호만으로 재설정 토큰을 발급해 계정 탈취 위험(P1)으로 제외됐다.
 * SMTP 미사용 결정에 따라 보안질문 방식으로 후속 처리(#194 / HAJA-172).
 */
@Tag(name = "AccountRecovery", description = "아이디 찾기 API")
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
}
