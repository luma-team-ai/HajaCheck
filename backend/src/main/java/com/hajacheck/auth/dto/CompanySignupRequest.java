package com.hajacheck.auth.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.multipart.MultipartFile;

/**
 * 기업 회원가입 요청(multipart/form-data, @ModelAttribute 바인딩).
 * 파일(businessRegistrationFile) 검증은 서비스/FileStorage 에서 수행(FILE_* ErrorCode).
 * 약관 버전은 서버 소유이므로 클라이언트가 보내지 않는다(동의 여부만 @AssertTrue).
 */
public record CompanySignupRequest(

        @NotBlank(message = "이메일은 필수입니다.")
        @Email(message = "이메일 형식이 올바르지 않습니다.")
        String email,

        @NotBlank(message = "비밀번호는 필수입니다.")
        @Size(min = 8, message = "비밀번호는 8자 이상이어야 합니다.")
        @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).+$", message = "비밀번호는 영문과 숫자를 포함해야 합니다.")
        String password,

        @NotBlank(message = "상호명은 필수입니다.")
        @Size(max = 200, message = "상호명은 200자 이하여야 합니다.")
        String companyName,

        @NotBlank(message = "사업자등록번호는 필수입니다.")
        @Pattern(regexp = "\\d{3}-?\\d{2}-?\\d{5}", message = "사업자등록번호 형식이 올바르지 않습니다.")
        String businessRegistrationNumber,

        @NotBlank(message = "대표자명은 필수입니다.")
        @Size(max = 100, message = "대표자명은 100자 이하여야 합니다.")
        String representativeName,

        // 개업일자(ISO yyyy-MM-dd) — 국세청 진위확인 파라미터(#596). OCR 자동채움 또는 수기 입력으로
        // 프론트가 항상 보낸다. 형식 불량은 LocalDate 바인딩 실패(400)로, 누락은 @NotNull(400)로 거른다.
        @NotNull(message = "개업일자는 필수입니다.")
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        LocalDate businessStartDate,

        @NotBlank(message = "주소는 필수입니다.")
        @Size(max = 300, message = "주소는 300자 이하여야 합니다.")
        String address,

        @Size(max = 200, message = "상세주소는 200자 이하여야 합니다.")
        String addressDetail,

        @AssertTrue(message = "서비스 이용약관에 동의해야 합니다.")
        boolean agreeTermsOfService,

        @AssertTrue(message = "개인정보 처리방침에 동의해야 합니다.")
        boolean agreePrivacyPolicy,

        MultipartFile businessRegistrationFile
) {
}
