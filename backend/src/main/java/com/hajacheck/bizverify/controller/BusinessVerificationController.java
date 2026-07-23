package com.hajacheck.bizverify.controller;

import com.hajacheck.bizverify.dto.BusinessVerificationRequest;
import com.hajacheck.bizverify.dto.BusinessVerificationResponse;
import com.hajacheck.bizverify.service.BusinessVerificationService;
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
 * 사업자 진위확인 실시간 조회 API(#648) — 회원가입 전(비로그인) 화면 전용. 경로가 {@code /api/auth/**}
 * 라 SecurityConfig 의 permitAll 목록에 이미 포함돼 있어 별도 화이트리스트 추가가 필요 없다
 * (BusinessLicenseOcrController 와 동일 근거).
 */
@Tag(name = "BusinessVerification", description = "사업자 진위확인 실시간 조회 API")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class BusinessVerificationController {

    private final BusinessVerificationService businessVerificationService;

    @Operation(summary = "사업자 진위확인(실시간)",
            description = "회원가입 전 [진위확인] 버튼 — 비로그인, rate-limit 적용. 가입을 막지 않고 결과만 반환한다.")
    @PostMapping("/business-verification")
    public ResponseEntity<ApiResponse<BusinessVerificationResponse>> verify(
            @Valid @RequestBody BusinessVerificationRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(businessVerificationService.verify(request)));
    }
}
