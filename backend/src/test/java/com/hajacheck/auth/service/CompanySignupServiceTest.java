package com.hajacheck.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hajacheck.auth.config.AuthProperties;
import com.hajacheck.auth.config.FileStorageProperties;
import com.hajacheck.auth.config.PolicyProperties;
import com.hajacheck.auth.dto.CompanySignupRequest;
import com.hajacheck.auth.dto.CompanySignupResponse;
import com.hajacheck.auth.entity.Company;
import com.hajacheck.auth.entity.CompanyStatus;
import com.hajacheck.auth.repository.CompanyRepository;
import com.hajacheck.auth.repository.UserRepository;
import com.hajacheck.auth.support.FileStorageService;
import com.hajacheck.auth.support.FileStorageService.StoredFile;
import com.hajacheck.auth.support.TokenNamespaces;
import com.hajacheck.auth.support.TokenStore;
import com.hajacheck.bizverify.service.NtsBusinessVerifyClient;
import com.hajacheck.bizverify.service.NtsVerificationOutcome;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import java.time.Duration;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.multipart.MultipartFile;
import com.hajacheck.core.ai.dto.BusinessLicenseOcrResponse;
import com.hajacheck.core.ai.service.AiProxyService;
import com.hajacheck.global.common.ApiResponse;
import com.hajacheck.support.InMemoryRateLimiter;
import com.hajacheck.support.PngTestFixtures;
import java.util.concurrent.atomic.AtomicInteger;
import com.hajacheck.core.media.support.PdfFirstPageRasterizer;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CompanySignupServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private CompanyRepository companyRepository;
    @Mock
    private CompanyAccountWriter accountWriter;
    @Mock
    private NtsBusinessVerifyClient ntsBusinessVerifyClient;
    @Mock
    private BusinessLicenseCrossCheckService businessLicenseCrossCheckService;
    @Mock
    private FileStorageService fileStorage;
    @Mock
    private FileStorageProperties fileStorageProperties;
    @Mock
    private TokenStore tokenStore;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private PolicyProperties policyProperties;
    @Mock
    private AuthProperties authProperties;

    @InjectMocks
    private CompanySignupService service;

    private MultipartFile file;

    @BeforeEach
    void setUp() {
        // ⚠️ 진짜 PNG 여야 한다 — 저장 전 검증(BusinessLicenseUploadValidator)이 선언 Content-Type ↔
        // 실제 매직바이트 일치와 디코딩 가능성을 대조보다 먼저 확인한다.
        file = new MockMultipartFile(
                "businessRegistrationFile", "brn.png", "image/png", PngTestFixtures.realPng());
        when(policyProperties.getTermsVersion()).thenReturn("1.0");
        when(policyProperties.getPrivacyVersion()).thenReturn("1.0");
        when(authProperties.getSignupStatusTtl()).thenReturn(Duration.ofDays(30));
        when(passwordEncoder.encode(anyString())).thenReturn("$2a$hashed");
        // 진위확인 기본 stub: SKIPPED(fail-open) — 개별 테스트에서 필요 시 오버라이드.
        when(ntsBusinessVerifyClient.validate(anyString(), anyString(), any()))
                .thenReturn(NtsVerificationOutcome.SKIPPED);
        // 등록증 대조 기본 stub: MATCH(#1469) — 개별 테스트에서 필요 시 오버라이드.
        when(businessLicenseCrossCheckService.crossCheck(any(), anyString()))
                .thenReturn(BusinessLicenseCrossCheckOutcome.MATCH);
    }

    private CompanySignupRequest request() {
        return new CompanySignupRequest(
                "haja@check.com", "pass1234", "(주)하자체크", "123-45-67890",
                "김민수", LocalDate.of(2020, 1, 1), "서울시 강남구", "101호", true, true, file);
    }

    private Company companyStub(Long id, CompanyStatus status) {
        Company company = org.mockito.Mockito.mock(Company.class);
        when(company.getId()).thenReturn(id);
        when(company.getStatus()).thenReturn(status);
        return company;
    }

    @Test
    void signup_이메일중복_409_파일저장안함() {
        when(userRepository.existsByEmail("haja@check.com")).thenReturn(true);

        assertThatThrownBy(() -> service.signup(request()))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.AUTH_EMAIL_DUPLICATED));

        verify(fileStorage, never()).store(any(), anyString(), any(), anyLong());
        verify(accountWriter, never()).createAccount(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void signup_사업자번호중복_409_파일저장안함() {
        when(userRepository.existsByEmail(anyString())).thenReturn(false);
        // 정규화(하이픈 제거)된 값으로 조회되어야 함.
        when(companyRepository.existsByBusinessRegistrationNumber("1234567890")).thenReturn(true);

        assertThatThrownBy(() -> service.signup(request()))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.AUTH_BUSINESS_NUMBER_DUPLICATED));

        verify(fileStorage, never()).store(any(), anyString(), any(), anyLong());
    }

    @Test
    void signup_파일누락_FILE_REQUIRED_전파() {
        when(userRepository.existsByEmail(anyString())).thenReturn(false);
        when(companyRepository.existsByBusinessRegistrationNumber(anyString())).thenReturn(false);
        when(fileStorage.store(any(), eq("business-registration"), any(), anyLong()))
                .thenThrow(new BusinessException(ErrorCode.FILE_REQUIRED));

        assertThatThrownBy(() -> service.signup(request()))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.FILE_REQUIRED));

        verify(accountWriter, never()).createAccount(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void signup_잘못된MIME_FILE_INVALID_TYPE_전파() {
        when(userRepository.existsByEmail(anyString())).thenReturn(false);
        when(companyRepository.existsByBusinessRegistrationNumber(anyString())).thenReturn(false);
        when(fileStorage.store(any(), eq("business-registration"), any(), anyLong()))
                .thenThrow(new BusinessException(ErrorCode.FILE_INVALID_TYPE));

        assertThatThrownBy(() -> service.signup(request()))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.FILE_INVALID_TYPE));
    }

    @Test
    void signup_해피패스_파일저장_writer호출_토큰발급_마스킹응답() {
        when(userRepository.existsByEmail(anyString())).thenReturn(false);
        when(companyRepository.existsByBusinessRegistrationNumber(anyString())).thenReturn(false);
        when(fileStorage.store(any(), eq("business-registration"), any(), anyLong()))
                .thenReturn(new StoredFile("/files/business-registration/x.png", "business-registration/x.png"));
        // #1324 — writer 가 회사를 즉시 APPROVED 로 만들므로 응답 상태도 APPROVED 다.
        Company company = companyStub(12L, CompanyStatus.APPROVED);
        when(accountWriter.createAccount(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(company);
        when(tokenStore.issue(eq(TokenNamespaces.SIGNUP_STATUS), eq("12"), any(Duration.class)))
                .thenReturn("signup-tok");

        CompanySignupResponse response = service.signup(request());

        assertThat(response.companyId()).isEqualTo(12L);
        assertThat(response.status()).isEqualTo("APPROVED");
        assertThat(response.signupToken()).isEqualTo("signup-tok");
        assertThat(response.maskedEmail()).isEqualTo("h***@c***.com");

        // writer 에 정규화 brn·대표자명(=user.name)·해시가 전달됐는지 검증.
        ArgumentCaptor<String> emailCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> repCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> hashCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> companyNameCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> brnCap = ArgumentCaptor.forClass(String.class);
        verify(accountWriter).createAccount(emailCap.capture(), repCap.capture(), hashCap.capture(),
                companyNameCap.capture(), brnCap.capture(), any(), any(), any(), any(),
                eq("1.0"), eq("1.0"), any());
        assertThat(emailCap.getValue()).isEqualTo("haja@check.com");
        assertThat(repCap.getValue()).isEqualTo("김민수");
        assertThat(hashCap.getValue()).isEqualTo("$2a$hashed");
        assertThat(companyNameCap.getValue()).isEqualTo("(주)하자체크");
        assertThat(brnCap.getValue()).isEqualTo("1234567890");
        verify(fileStorage, never()).delete(anyString());
    }

    @Test
    void signup_진위확인결과가_ocrRaw에_담겨_writer로_전달된다() {
        when(userRepository.existsByEmail(anyString())).thenReturn(false);
        when(companyRepository.existsByBusinessRegistrationNumber(anyString())).thenReturn(false);
        when(ntsBusinessVerifyClient.validate(anyString(), anyString(), any()))
                .thenReturn(NtsVerificationOutcome.VERIFIED);
        when(fileStorage.store(any(), eq("business-registration"), any(), anyLong()))
                .thenReturn(new StoredFile("/files/business-registration/x.png", "business-registration/x.png"));
        Company company = companyStub(12L, CompanyStatus.APPROVED);
        when(accountWriter.createAccount(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(company);
        when(tokenStore.issue(eq(TokenNamespaces.SIGNUP_STATUS), eq("12"), any(Duration.class)))
                .thenReturn("signup-tok");

        service.signup(request());

        // #1324 P1 — 자동승인은 진위확인 결과와 무관하게 VERIFIED 를 만들므로, 결과 자체를 감사·재처리용
        // jsonb(business_registration_ocr_raw)에 영속해야 사후에 "검증 없이 승인된 회사"를 집계할 수 있다.
        ArgumentCaptor<String> ocrRawCap = ArgumentCaptor.forClass(String.class);
        verify(accountWriter).createAccount(any(), any(), any(), any(), any(), any(), any(), any(),
                ocrRawCap.capture(), eq("1.0"), eq("1.0"), any());
        assertThat(ocrRawCap.getValue())
                .contains("\"ntsOutcome\":\"VERIFIED\"")
                .contains("\"source\":\"MANUAL_INPUT\"")
                .contains("\"ntsCheckedAt\"");
        // 개인정보 금지 — enum 라벨과 타임스탬프만.
        assertThat(ocrRawCap.getValue())
                .doesNotContain("1234567890").doesNotContain("김민수").doesNotContain("haja@check.com");
    }

    @Test
    void signup_failopen_스킵도_ocrRaw에_SKIPPED로_기록된다() {
        // 기본 stub 이 SKIPPED — 이 값이 남아야 "국세청이 확인해 준 회사"와 구분된다.
        when(userRepository.existsByEmail(anyString())).thenReturn(false);
        when(companyRepository.existsByBusinessRegistrationNumber(anyString())).thenReturn(false);
        when(fileStorage.store(any(), eq("business-registration"), any(), anyLong()))
                .thenReturn(new StoredFile("/files/business-registration/x.png", "business-registration/x.png"));
        Company company = companyStub(12L, CompanyStatus.APPROVED);
        when(accountWriter.createAccount(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(company);
        when(tokenStore.issue(eq(TokenNamespaces.SIGNUP_STATUS), eq("12"), any(Duration.class)))
                .thenReturn("signup-tok");

        service.signup(request());

        ArgumentCaptor<String> ocrRawCap = ArgumentCaptor.forClass(String.class);
        verify(accountWriter).createAccount(any(), any(), any(), any(), any(), any(), any(), any(),
                ocrRawCap.capture(), eq("1.0"), eq("1.0"), any());
        assertThat(ocrRawCap.getValue()).contains("\"ntsOutcome\":\"SKIPPED\"");
    }

    @Test
    void signup_저장중경합_이메일unique위반_보상삭제후_409EMAIL() {
        when(userRepository.existsByEmail("haja@check.com"))
                .thenReturn(false)   // 선검사 통과
                .thenReturn(true);   // 보상 후 재확인 → 이메일 충돌
        when(companyRepository.existsByBusinessRegistrationNumber(anyString())).thenReturn(false);
        when(fileStorage.store(any(), eq("business-registration"), any(), anyLong()))
                .thenReturn(new StoredFile("/files/business-registration/x.png", "business-registration/x.png"));
        when(accountWriter.createAccount(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new DataIntegrityViolationException("users_email_key"));

        assertThatThrownBy(() -> service.signup(request()))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.AUTH_EMAIL_DUPLICATED));

        // 저장 실패 시 파일 보상삭제가 반드시 호출돼야 한다(고아 파일 방지).
        verify(fileStorage).delete("business-registration/x.png");
        verify(tokenStore, never()).issue(anyString(), anyString(), any());
    }

    @Test
    void signup_저장중경합_사업자번호unique위반_보상삭제후_409BRN() {
        when(userRepository.existsByEmail("haja@check.com"))
                .thenReturn(false)   // 선검사
                .thenReturn(false);  // 보상 후 재확인 → 이메일 아님 → brn 충돌로 판정
        when(companyRepository.existsByBusinessRegistrationNumber(anyString())).thenReturn(false);
        when(fileStorage.store(any(), eq("business-registration"), any(), anyLong()))
                .thenReturn(new StoredFile("/files/business-registration/x.png", "business-registration/x.png"));
        when(accountWriter.createAccount(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new DataIntegrityViolationException("companies_business_registration_number_key"));

        assertThatThrownBy(() -> service.signup(request()))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.AUTH_BUSINESS_NUMBER_DUPLICATED));

        verify(fileStorage).delete("business-registration/x.png");
    }

    @Test
    void signup_진위불일치_차단_400_파일저장안함() {
        when(userRepository.existsByEmail(anyString())).thenReturn(false);
        when(companyRepository.existsByBusinessRegistrationNumber(anyString())).thenReturn(false);
        when(ntsBusinessVerifyClient.validate(anyString(), anyString(), any()))
                .thenReturn(NtsVerificationOutcome.MISMATCH);

        assertThatThrownBy(() -> service.signup(request()))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.AUTH_BUSINESS_VERIFICATION_FAILED));

        // 진위확인은 파일 저장·계정 생성보다 먼저라, 차단 시 파일을 저장하지 않는다(고아 파일 방지).
        verify(fileStorage, never()).store(any(), anyString(), any(), anyLong());
        verify(accountWriter, never()).createAccount(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void signup_폐업사업자_차단_400() {
        when(userRepository.existsByEmail(anyString())).thenReturn(false);
        when(companyRepository.existsByBusinessRegistrationNumber(anyString())).thenReturn(false);
        when(ntsBusinessVerifyClient.validate(anyString(), anyString(), any()))
                .thenReturn(NtsVerificationOutcome.CLOSED);

        assertThatThrownBy(() -> service.signup(request()))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.AUTH_BUSINESS_VERIFICATION_FAILED));

        verify(fileStorage, never()).store(any(), anyString(), any(), anyLong());
    }

    @Test
    void signup_휴업사업자_차단_400_보수적처리() {
        when(userRepository.existsByEmail(anyString())).thenReturn(false);
        when(companyRepository.existsByBusinessRegistrationNumber(anyString())).thenReturn(false);
        when(ntsBusinessVerifyClient.validate(anyString(), anyString(), any()))
                .thenReturn(NtsVerificationOutcome.SUSPENDED);

        assertThatThrownBy(() -> service.signup(request()))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.AUTH_BUSINESS_VERIFICATION_FAILED));
    }

    @Test
    void signup_진위성공_계속사업자_개업일자전달_가입성공() {
        when(userRepository.existsByEmail(anyString())).thenReturn(false);
        when(companyRepository.existsByBusinessRegistrationNumber(anyString())).thenReturn(false);
        when(ntsBusinessVerifyClient.validate(anyString(), anyString(), any()))
                .thenReturn(NtsVerificationOutcome.VERIFIED);
        when(fileStorage.store(any(), eq("business-registration"), any(), anyLong()))
                .thenReturn(new StoredFile("/files/business-registration/x.png", "business-registration/x.png"));
        Company company = companyStub(12L, CompanyStatus.APPROVED);
        when(accountWriter.createAccount(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(company);
        when(tokenStore.issue(eq(TokenNamespaces.SIGNUP_STATUS), eq("12"), any(Duration.class)))
                .thenReturn("signup-tok");

        service.signup(request());

        // 개업일자(국세청 진위확인 파라미터, #596)는 writer 까지 그대로 전달돼야 한다.
        // (#1324) 진위확인 결과를 writer 로 넘기는 businessVerified 인자는 제거됐다 — writer 가
        // 결과와 무관하게 VERIFIED 로 승격하므로 그 인자는 죽은 값이 된다.
        ArgumentCaptor<LocalDate> startDateCap = ArgumentCaptor.forClass(LocalDate.class);
        verify(accountWriter).createAccount(any(), any(), any(), any(), any(), any(), any(), any(), any(),
                eq("1.0"), eq("1.0"), startDateCap.capture());
        assertThat(startDateCap.getValue()).isEqualTo(LocalDate.of(2020, 1, 1));
    }

    @Test
    void signup_failopen_스킵시_가입성공_APPROVED응답() {
        // 기본 stub 이 SKIPPED(fail-open) — 국세청 키 미설정·장애로 확인하지 못해도 가입은 진행되고,
        // #1324 전면 자동승인이라 응답 상태도 APPROVED 다(승인 대기 단계 없음).
        when(userRepository.existsByEmail(anyString())).thenReturn(false);
        when(companyRepository.existsByBusinessRegistrationNumber(anyString())).thenReturn(false);
        when(fileStorage.store(any(), eq("business-registration"), any(), anyLong()))
                .thenReturn(new StoredFile("/files/business-registration/x.png", "business-registration/x.png"));
        Company company = companyStub(12L, CompanyStatus.APPROVED);
        when(accountWriter.createAccount(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(company);
        when(tokenStore.issue(eq(TokenNamespaces.SIGNUP_STATUS), eq("12"), any(Duration.class)))
                .thenReturn("signup-tok");

        CompanySignupResponse response = service.signup(request());

        assertThat(response.companyId()).isEqualTo(12L);
        assertThat(response.status()).isEqualTo("APPROVED");
        verify(accountWriter).createAccount(any(), any(), any(), any(), any(), any(), any(), any(), any(),
                eq("1.0"), eq("1.0"), any());
    }

    // ---------- 사업자등록증 ↔ 입력 사업자번호 대조 게이트(#1469 / HAJA-650) ----------

    /** 대조에 성공 stub 을 깔고 happy-path 저장 경로까지 준비한다(반복되는 4줄을 묶는다). */
    private void stubHappyPathStorage() {
        when(userRepository.existsByEmail(anyString())).thenReturn(false);
        when(companyRepository.existsByBusinessRegistrationNumber(anyString())).thenReturn(false);
        when(fileStorage.store(any(), eq("business-registration"), any(), anyLong()))
                .thenReturn(new StoredFile("/files/business-registration/x.png", "business-registration/x.png"));
        Company company = companyStub(12L, CompanyStatus.APPROVED);
        when(accountWriter.createAccount(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(company);
        when(tokenStore.issue(eq(TokenNamespaces.SIGNUP_STATUS), eq("12"), any(Duration.class)))
                .thenReturn("signup-tok");
    }

    private String capturedOcrRaw() {
        ArgumentCaptor<String> ocrRawCap = ArgumentCaptor.forClass(String.class);
        verify(accountWriter).createAccount(any(), any(), any(), any(), any(), any(), any(), any(),
                ocrRawCap.capture(), eq("1.0"), eq("1.0"), any());
        return ocrRawCap.getValue();
    }

    @Test
    void signup_등록증대조_MATCH_가입성공_파일저장_licenseOcrOutcome기록() {
        stubHappyPathStorage();

        CompanySignupResponse response = service.signup(request());

        assertThat(response.status()).isEqualTo("APPROVED");
        // 대조는 정규화(하이픈 제거)된 입력 사업자번호로 수행돼야 한다.
        verify(businessLicenseCrossCheckService).crossCheck(file, "1234567890");
        // 대조 단계에서 MultipartFile 을 한 번 읽었어도(getBytes) 그 뒤 파일 저장이 정상 수행돼야 한다 —
        // 재읽기가 깨지면 조용히 빈 파일이 저장되므로 여기서 고정한다.
        verify(fileStorage).store(eq(file), eq("business-registration"), any(), anyLong());
        verify(fileStorage, never()).delete(anyString());
        assertThat(capturedOcrRaw()).contains("\"licenseOcrOutcome\":\"MATCH\"");
    }

    @Test
    void signup_등록증대조_하이픈없는_입력도_정규형으로_대조된다() {
        stubHappyPathStorage();
        CompanySignupRequest noHyphen = new CompanySignupRequest(
                "haja@check.com", "pass1234", "(주)하자체크", "1234567890",
                "김민수", LocalDate.of(2020, 1, 1), "서울시 강남구", "101호", true, true, file);

        service.signup(noHyphen);

        verify(businessLicenseCrossCheckService).crossCheck(file, "1234567890");
        verify(fileStorage).store(eq(file), eq("business-registration"), any(), anyLong());
    }

    @Test
    void signup_등록증대조_MISMATCH_차단_400_파일저장안함_고아파일없음() {
        // #1469 의 본체 — 남의 사업자등록증 업로드 + 실재하는 제3자 정보 수기 입력을 여기서 막는다.
        when(userRepository.existsByEmail(anyString())).thenReturn(false);
        when(companyRepository.existsByBusinessRegistrationNumber(anyString())).thenReturn(false);
        when(businessLicenseCrossCheckService.crossCheck(any(), anyString()))
                .thenReturn(BusinessLicenseCrossCheckOutcome.MISMATCH);

        assertThatThrownBy(() -> service.signup(request()))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.AUTH_BUSINESS_LICENSE_MISMATCH));

        // 대조는 파일 저장보다 먼저라 차단 시 저장 자체가 없다 → 보상삭제 경로도 타지 않는다.
        verify(fileStorage, never()).store(any(), anyString(), any(), anyLong());
        verify(fileStorage, never()).delete(anyString());
        verify(accountWriter, never()).createAccount(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void signup_등록증대조_OCR_NO_BRN_failopen_가입성공() {
        stubHappyPathStorage();
        when(businessLicenseCrossCheckService.crossCheck(any(), anyString()))
                .thenReturn(BusinessLicenseCrossCheckOutcome.OCR_NO_BRN);

        CompanySignupResponse response = service.signup(request());

        assertThat(response.status()).isEqualTo("APPROVED");
        assertThat(capturedOcrRaw()).contains("\"licenseOcrOutcome\":\"OCR_NO_BRN\"");
    }

    @Test
    void signup_등록증대조_OCR_FAILED_failopen_가입성공() {
        // AI 서버 장애가 회원가입 전체를 막으면 안 된다(국세청 진위확인 SKIPPED 와 같은 기조).
        stubHappyPathStorage();
        when(businessLicenseCrossCheckService.crossCheck(any(), anyString()))
                .thenReturn(BusinessLicenseCrossCheckOutcome.OCR_FAILED);

        CompanySignupResponse response = service.signup(request());

        assertThat(response.status()).isEqualTo("APPROVED");
        assertThat(capturedOcrRaw()).contains("\"licenseOcrOutcome\":\"OCR_FAILED\"");
    }

    @Test
    void signup_등록증대조_PDF_SKIPPED_failopen_가입성공() {
        // 회원가입 파일 허용목록엔 PDF 가 있으나 OCR 은 이미지 2종만 — 대조 없이 통과한다.
        stubHappyPathStorage();
        when(businessLicenseCrossCheckService.crossCheck(any(), anyString()))
                .thenReturn(BusinessLicenseCrossCheckOutcome.SKIPPED_UNSUPPORTED_TYPE);

        service.signup(request());

        assertThat(capturedOcrRaw()).contains("\"licenseOcrOutcome\":\"SKIPPED_UNSUPPORTED_TYPE\"");
    }

    @Test
    void signup_등록증대조_전역캡소진_RATE_LIMITED는_failopen_가입성공() {
        // ⚠️ 계약(2026-08-04 2차 리뷰에서 429 차단안을 되돌림): 전역 축은 모든 가입자가 공유하므로 막으면
        // 공격자가 캡을 태우는 것만으로 그날 기업 가입을 전면 정지시킬 수 있다(DoS 킬스위치).
        // 게이트가 꺼져 있었다는 사실은 log.error 경보와 아래 감사 라벨로 남는다.
        stubHappyPathStorage();
        when(businessLicenseCrossCheckService.crossCheck(any(), anyString()))
                .thenReturn(BusinessLicenseCrossCheckOutcome.RATE_LIMITED);

        CompanySignupResponse response = service.signup(request());

        assertThat(response.status()).isEqualTo("APPROVED");
        assertThat(capturedOcrRaw()).contains("\"licenseOcrOutcome\":\"RATE_LIMITED\"");
    }

    @Test
    void signup_등록증대조_시도한도초과_429중립문구로_차단_파일저장안함() {
        // 같은 사업자번호로 반복 실패해 시도 한도를 넘긴 경우(자기소진 봉쇄) — MISMATCH(400)와 <b>다른</b>
        // 코드로 응답한다. 합치면 AI 장애 중 몇 번 시도한 정상 사용자가 "등록증 번호가 다릅니다"라는
        // 사실과 다른 안내를 받고, 공격자는 응답 시간으로 어차피 구분한다(2차 리뷰 P2).
        when(userRepository.existsByEmail(anyString())).thenReturn(false);
        when(companyRepository.existsByBusinessRegistrationNumber(anyString())).thenReturn(false);
        when(businessLicenseCrossCheckService.crossCheck(any(), anyString()))
                .thenReturn(BusinessLicenseCrossCheckOutcome.BRN_ATTEMPT_EXCEEDED);

        assertThatThrownBy(() -> service.signup(request()))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.AUTH_BUSINESS_LICENSE_CHECK_UNAVAILABLE));
        assertThat(ErrorCode.AUTH_BUSINESS_LICENSE_CHECK_UNAVAILABLE.getStatus())
                .isEqualTo(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS);

        verify(fileStorage, never()).store(any(), anyString(), any(), anyLong());
        verify(accountWriter, never()).createAccount(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void signup_PDF래스터화_동시상한포화_429차단_파일저장안함() {
        // 렌더 슬롯 포화는 "대조를 수행하지 못함"이라 통과시키면 공격자가 슬롯을 점유해 위조 PDF 를
        // 통과시키는 직접적 우회가 된다. 피해는 PDF 경로·초 단위로 국소적이라 여기는 막는 쪽이 맞다.
        when(userRepository.existsByEmail(anyString())).thenReturn(false);
        when(companyRepository.existsByBusinessRegistrationNumber(anyString())).thenReturn(false);
        when(businessLicenseCrossCheckService.crossCheck(any(), anyString()))
                .thenReturn(BusinessLicenseCrossCheckOutcome.RASTERIZE_BUSY);

        assertThatThrownBy(() -> service.signup(request()))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.AUTH_BUSINESS_LICENSE_CHECK_UNAVAILABLE));

        verify(fileStorage, never()).store(any(), anyString(), any(), anyLong());
    }

    @Test
    void signup_선언ContentType과_실제바이트가_다르면_대조전에_거부된다() {
        // 대조는 선언 타입을 보지 않고 저장은 선언 타입을 쓰므로, 이 검증이 없으면 "대조 MATCH 통과 →
        // 저장 400 → 회사 행 없음 → 무한 반복"으로 전역 캡만 태우는 루프가 성립한다(2차 리뷰 P1).
        MultipartFile disguised = new MockMultipartFile(
                "businessRegistrationFile", "brn.pdf", "application/pdf", PngTestFixtures.realPng());
        CompanySignupRequest request = new CompanySignupRequest(
                "haja@check.com", "pass1234", "(주)하자체크", "123-45-67890",
                "김민수", LocalDate.of(2020, 1, 1), "서울시 강남구", "101호", true, true, disguised);
        when(userRepository.existsByEmail(anyString())).thenReturn(false);
        when(companyRepository.existsByBusinessRegistrationNumber(anyString())).thenReturn(false);

        assertThatThrownBy(() -> service.signup(request))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.FILE_INVALID_TYPE));

        // AI 호출도, 저장도 없어야 한다.
        verify(businessLicenseCrossCheckService, never()).crossCheck(any(), anyString());
        verify(fileStorage, never()).store(any(), anyString(), any(), anyLong());
    }

    @Test
    void signup_손상된_이미지는_증빙으로_저장되지_않는다() {
        // FF D8 FF + 쓰레기 = 시그니처는 JPEG 지만 열리지 않는 파일. 증빙으로 남으면 나중에 사람이
        // 등록증을 열람할 때 신뢰할 수 없다(2차 리뷰 P2-7①).
        byte[] brokenJpeg = new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x11, 0x22, 0x33, 0x44, 0x55};
        MultipartFile broken = new MockMultipartFile(
                "businessRegistrationFile", "brn.jpg", "image/jpeg", brokenJpeg);
        CompanySignupRequest request = new CompanySignupRequest(
                "haja@check.com", "pass1234", "(주)하자체크", "123-45-67890",
                "김민수", LocalDate.of(2020, 1, 1), "서울시 강남구", "101호", true, true, broken);
        when(userRepository.existsByEmail(anyString())).thenReturn(false);
        when(companyRepository.existsByBusinessRegistrationNumber(anyString())).thenReturn(false);

        assertThatThrownBy(() -> service.signup(request))
                .isInstanceOf(BusinessException.class);

        verify(fileStorage, never()).store(any(), anyString(), any(), anyLong());
    }

    @Test
    void signup_국세청차단이_등록증대조보다_먼저다_AI호출없음() {
        // 진위확인에서 이미 확정 불량이면 AI 호출(과금)까지 갈 이유가 없다.
        when(userRepository.existsByEmail(anyString())).thenReturn(false);
        when(companyRepository.existsByBusinessRegistrationNumber(anyString())).thenReturn(false);
        when(ntsBusinessVerifyClient.validate(anyString(), anyString(), any()))
                .thenReturn(NtsVerificationOutcome.MISMATCH);

        assertThatThrownBy(() -> service.signup(request()))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.AUTH_BUSINESS_VERIFICATION_FAILED));

        verify(businessLicenseCrossCheckService, never()).crossCheck(any(), anyString());
    }

    /**
     * 재읽기 회귀 가드(리뷰 P3) — 위 테스트들은 대조 서비스가 mock 이라 {@code getBytes()} 가 아예 호출되지
     * 않아, "OCR 이 파일을 읽은 뒤에도 저장이 정상 동작하는가"를 실제로는 검증하지 못한다. 여기서는
     * <b>진짜 BusinessLicenseCrossCheckService</b>(AI 만 stub)를 끼워 실제 PNG 를 OCR 경로에 태운 뒤,
     * 저장 단계에 <b>실제로 도달한 바이트 길이</b>를 확인한다.
     */
    @Test
    void signup_대조가_파일을_읽은_뒤에도_저장단계에_바이트가_그대로_전달된다() throws Exception {
        byte[] pngBytes = PngTestFixtures.realPng();
        MultipartFile realPng = new MockMultipartFile(
                "businessRegistrationFile", "brn.png", "image/png", pngBytes);

        AiProxyService aiProxyService = org.mockito.Mockito.mock(AiProxyService.class);
        when(aiProxyService.ocrBusinessLicenseForCrossCheck(anyString())).thenReturn(ApiResponse.ok(
                new BusinessLicenseOcrResponse("123-45-67890", "(주)하자체크", "김민수", "2020-01-01")));
        BusinessLicenseCrossCheckService realCrossCheck = new BusinessLicenseCrossCheckService(
                aiProxyService, new InMemoryRateLimiter(), new AuthProperties(), new FileStorageProperties(),
                new PdfFirstPageRasterizer());
        CompanySignupService serviceWithRealCrossCheck = new CompanySignupService(
                userRepository, companyRepository, accountWriter, ntsBusinessVerifyClient, realCrossCheck,
                fileStorage, fileStorageProperties, tokenStore, passwordEncoder, policyProperties, authProperties);

        when(userRepository.existsByEmail(anyString())).thenReturn(false);
        when(companyRepository.existsByBusinessRegistrationNumber(anyString())).thenReturn(false);
        when(fileStorageProperties.getMaxSizeBytes()).thenReturn(10_485_760L);
        AtomicInteger storedLength = new AtomicInteger(-1);
        when(fileStorage.store(any(), eq("business-registration"), any(), anyLong())).thenAnswer(invocation -> {
            // 저장 시점에 실제로 읽히는 바이트를 센다(대조가 스트림을 소진했다면 0이 된다).
            storedLength.set(((MultipartFile) invocation.getArgument(0)).getBytes().length);
            return new StoredFile("/files/business-registration/x.png", "business-registration/x.png");
        });
        Company company = companyStub(12L, CompanyStatus.APPROVED);
        when(accountWriter.createAccount(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(company);
        when(tokenStore.issue(eq(TokenNamespaces.SIGNUP_STATUS), eq("12"), any(Duration.class)))
                .thenReturn("signup-tok");

        CompanySignupRequest request = new CompanySignupRequest(
                "haja@check.com", "pass1234", "(주)하자체크", "123-45-67890",
                "김민수", LocalDate.of(2020, 1, 1), "서울시 강남구", "101호", true, true, realPng);
        serviceWithRealCrossCheck.signup(request);

        // 실제로 OCR 이 호출됐고(=파일을 읽었고), 그 뒤 저장에도 온전한 바이트가 전달됐다.
        verify(aiProxyService).ocrBusinessLicenseForCrossCheck(anyString());
        assertThat(storedLength.get()).isPositive().isEqualTo(pngBytes.length);
        assertThat(capturedOcrRaw()).contains("\"licenseOcrOutcome\":\"MATCH\"");
    }

    @Test
    void signup_등록증대조_판정은_ocrRaw에_남되_개인정보는_남기지않는다() {
        stubHappyPathStorage();

        service.signup(request());

        String ocrRaw = capturedOcrRaw();
        assertThat(ocrRaw)
                .contains("\"ntsOutcome\":\"SKIPPED\"")
                .contains("\"licenseOcrOutcome\":\"MATCH\"");
        assertThat(ocrRaw)
                .doesNotContain("1234567890").doesNotContain("김민수").doesNotContain("haja@check.com");
    }
}
