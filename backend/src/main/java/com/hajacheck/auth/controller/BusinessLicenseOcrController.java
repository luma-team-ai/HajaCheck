package com.hajacheck.auth.controller;

import com.hajacheck.auth.service.BusinessLicenseOcrService;
import com.hajacheck.core.ai.dto.BusinessLicenseOcrResponse;
import com.hajacheck.global.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 사업자등록증 OCR 공개 프록시(#557 / HAJA-169) — 기업 가입 전(비로그인) 화면 전용. 경로가
 * {@code /api/auth/**} 라 SecurityConfig 의 permitAll 목록에 이미 포함돼 있어 별도 화이트리스트
 * 추가가 필요 없다(CompanySignupController 와 동일 근거).
 */
@Tag(name = "BusinessLicenseOcr", description = "사업자등록증 OCR 공개 프록시 API")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class BusinessLicenseOcrController {

    private final BusinessLicenseOcrService businessLicenseOcrService;

    @Operation(summary = "사업자등록증 OCR", description = "가입 전 이미지 업로드로 사업자번호·상호·대표자명을 인식한다(비로그인, rate-limit 적용, multipart)")
    @PostMapping(value = "/business-license/ocr", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<BusinessLicenseOcrResponse>> ocr(
            @RequestParam("businessRegistrationFile") MultipartFile businessRegistrationFile) {
        return ResponseEntity.ok(businessLicenseOcrService.ocr(businessRegistrationFile));
    }
}
