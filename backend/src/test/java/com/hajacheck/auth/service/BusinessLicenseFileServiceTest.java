package com.hajacheck.auth.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hajacheck.auth.config.FileStorageProperties;
import com.hajacheck.auth.entity.Company;
import com.hajacheck.auth.repository.CompanyRepository;
import com.hajacheck.auth.support.FileStorageService;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BusinessLicenseFileServiceTest {

    private static final Long COMPANY_ID = 10L;
    private static final Long USER_ID = 20L;
    private static final String STORAGE_KEY = "business-registration/license.png";

    @Mock
    private CompanyRepository companyRepository;
    @Mock
    private CompanyScopeGuard companyScopeGuard;
    @Mock
    private FileStorageService fileStorage;

    private BusinessLicenseFileService service;

    @BeforeEach
    void setUp() {
        FileStorageProperties properties = new FileStorageProperties();
        properties.setBaseUrlPath("/files");
        service = new BusinessLicenseFileService(companyRepository, companyScopeGuard, fileStorage, properties);
    }

    @Test
    void 인증된_동일회사_사업자등록증조회_성공() {
        Company company = org.mockito.Mockito.mock(Company.class);
        when(company.getBusinessRegistrationFileUrl()).thenReturn("/files/" + STORAGE_KEY);
        when(companyRepository.findById(COMPANY_ID)).thenReturn(Optional.of(company));
        when(fileStorage.read(STORAGE_KEY)).thenReturn(new byte[] {1, 2, 3});

        BusinessLicenseFileService.LicenseFile result = service.load(COMPANY_ID, USER_ID, STORAGE_KEY);

        verify(companyScopeGuard).requireEffectiveMembership(USER_ID, COMPANY_ID);
        verify(fileStorage).read(STORAGE_KEY);
        org.assertj.core.api.Assertions.assertThat(result.content()).containsExactly(1, 2, 3);
        org.assertj.core.api.Assertions.assertThat(result.contentType()).isEqualTo("image/png");
    }

    @Test
    void 비로그인_회사스코프검증에서_거부() {
        org.mockito.Mockito.doThrow(new BusinessException(ErrorCode.UNAUTHORIZED))
                .when(companyScopeGuard).requireEffectiveMembership(null, COMPANY_ID);

        assertThatThrownBy(() -> service.load(COMPANY_ID, null, STORAGE_KEY))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.UNAUTHORIZED);

        verify(companyRepository, never()).findById(any());
        verify(fileStorage, never()).read(any());
    }

    @Test
    void 타회사_요청은_회사스코프검증에서_거부() {
        org.mockito.Mockito.doThrow(new BusinessException(ErrorCode.FORBIDDEN))
                .when(companyScopeGuard).requireEffectiveMembership(USER_ID, COMPANY_ID);

        assertThatThrownBy(() -> service.load(COMPANY_ID, USER_ID, STORAGE_KEY))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.FORBIDDEN);

        verify(companyRepository, never()).findById(any());
        verify(fileStorage, never()).read(any());
    }

    @Test
    void 경로조작과_타회사_파일키는_파일을_읽지_않음() {
        Company company = org.mockito.Mockito.mock(Company.class);
        when(company.getBusinessRegistrationFileUrl()).thenReturn("/files/" + STORAGE_KEY);
        when(companyRepository.findById(COMPANY_ID)).thenReturn(Optional.of(company));

        assertThatThrownBy(() -> service.load(COMPANY_ID, USER_ID, "business-registration/../other.png"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.FILE_NOT_FOUND);
        assertThatThrownBy(() -> service.load(COMPANY_ID, USER_ID, "business-registration/other-company.png"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.FILE_NOT_FOUND);

        verify(fileStorage, never()).read(any());
    }
}
