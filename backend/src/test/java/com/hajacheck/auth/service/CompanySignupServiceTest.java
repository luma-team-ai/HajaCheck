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
        file = new MockMultipartFile(
                "businessRegistrationFile", "brn.png", "image/png", "PNG".getBytes());
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
    void signup_등록증대조_RATE_LIMITED_failopen_가입성공_판정이_감사기록에_남는다() {
        // 캡 소진은 "게이트가 조용히 꺼진 상태"라 DB에 흔적이 남아야 사후 재구성이 가능하다.
        stubHappyPathStorage();
        when(businessLicenseCrossCheckService.crossCheck(any(), anyString()))
                .thenReturn(BusinessLicenseCrossCheckOutcome.RATE_LIMITED);

        service.signup(request());

        assertThat(capturedOcrRaw()).contains("\"licenseOcrOutcome\":\"RATE_LIMITED\"");
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
