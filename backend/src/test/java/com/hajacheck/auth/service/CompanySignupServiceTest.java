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
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import java.time.Duration;
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
    }

    private CompanySignupRequest request() {
        return new CompanySignupRequest(
                "haja@check.com", "pass1234", "(주)하자체크", "123-45-67890",
                "김민수", "서울시 강남구", "101호", true, true, file);
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
        verify(accountWriter, never()).createAccount(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
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

        verify(accountWriter, never()).createAccount(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
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
        Company company = companyStub(12L, CompanyStatus.PENDING_REVIEW);
        when(accountWriter.createAccount(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(company);
        when(tokenStore.issue(eq(TokenNamespaces.SIGNUP_STATUS), eq("12"), any(Duration.class)))
                .thenReturn("signup-tok");

        CompanySignupResponse response = service.signup(request());

        assertThat(response.companyId()).isEqualTo(12L);
        assertThat(response.status()).isEqualTo("PENDING_REVIEW");
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
                eq("1.0"), eq("1.0"));
        assertThat(emailCap.getValue()).isEqualTo("haja@check.com");
        assertThat(repCap.getValue()).isEqualTo("김민수");
        assertThat(hashCap.getValue()).isEqualTo("$2a$hashed");
        assertThat(companyNameCap.getValue()).isEqualTo("(주)하자체크");
        assertThat(brnCap.getValue()).isEqualTo("1234567890");
        verify(fileStorage, never()).delete(anyString());
    }

    @Test
    void signup_저장중경합_이메일unique위반_보상삭제후_409EMAIL() {
        when(userRepository.existsByEmail("haja@check.com"))
                .thenReturn(false)   // 선검사 통과
                .thenReturn(true);   // 보상 후 재확인 → 이메일 충돌
        when(companyRepository.existsByBusinessRegistrationNumber(anyString())).thenReturn(false);
        when(fileStorage.store(any(), eq("business-registration"), any(), anyLong()))
                .thenReturn(new StoredFile("/files/business-registration/x.png", "business-registration/x.png"));
        when(accountWriter.createAccount(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
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
        when(accountWriter.createAccount(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new DataIntegrityViolationException("companies_business_registration_number_key"));

        assertThatThrownBy(() -> service.signup(request()))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.AUTH_BUSINESS_NUMBER_DUPLICATED));

        verify(fileStorage).delete("business-registration/x.png");
    }
}
