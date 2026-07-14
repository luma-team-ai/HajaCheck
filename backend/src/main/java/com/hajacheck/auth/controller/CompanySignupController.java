package com.hajacheck.auth.controller;

import com.hajacheck.auth.dto.CompanySignupRequest;
import com.hajacheck.auth.dto.CompanySignupResponse;
import com.hajacheck.auth.dto.EmailAvailabilityResponse;
import com.hajacheck.auth.dto.SignupStatusResponse;
import com.hajacheck.auth.service.CompanySignupService;
import com.hajacheck.global.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 기업 회원가입·중복확인·가입상태 조회. 모든 경로는 /api/auth/** (SecurityConfig permitAll 커버).
 * 검증 실패는 400/404/409 만 사용(401 금지 — 프론트 axios 가 401 을 로그인 리다이렉트로 처리).
 */
@Tag(name = "CompanySignup", description = "기업 회원가입 API")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Validated
public class CompanySignupController {

    private final CompanySignupService companySignupService;

    @Operation(summary = "기업 회원가입", description = "User+Company(PENDING_REVIEW)+동의이력 원자 생성 (multipart)")
    @PostMapping(value = "/companies", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<CompanySignupResponse>> signup(
            @Valid @ModelAttribute CompanySignupRequest request) {
        CompanySignupResponse response = companySignupService.signup(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response));
    }

    @Operation(summary = "이메일(아이디) 중복확인")
    @GetMapping("/email-availability")
    public ResponseEntity<ApiResponse<EmailAvailabilityResponse>> checkEmailAvailability(
            @RequestParam @NotBlank @Email String email) {
        boolean available = companySignupService.isEmailAvailable(email);
        return ResponseEntity.ok(ApiResponse.ok(EmailAvailabilityResponse.of(available)));
    }

    @Operation(summary = "가입 상태 조회", description = "승인 대기 화면 새로고침 — signupToken 으로 상태 조회")
    @GetMapping("/companies/status")
    public ResponseEntity<ApiResponse<SignupStatusResponse>> getSignupStatus(
            @RequestParam @NotBlank String token) {
        SignupStatusResponse response = companySignupService.getSignupStatus(token);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }
}
