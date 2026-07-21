package com.hajacheck.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hajacheck.auth.entity.Company;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.repository.CompanyRepository;
import com.hajacheck.auth.repository.UserConsentRepository;
import com.hajacheck.auth.repository.UserRepository;
import com.hajacheck.membership.service.PlanProvisioningService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 기업 가입 원자 저장 + FREE 플랜 배정(#517) 연결 검증.
 */
@ExtendWith(MockitoExtension.class)
class CompanyAccountWriterTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private CompanyRepository companyRepository;
    @Mock
    private UserConsentRepository userConsentRepository;
    @Mock
    private PlanProvisioningService planProvisioningService;

    @InjectMocks
    private CompanyAccountWriter accountWriter;

    @Test
    void createAccount_회사저장후_FREE회사플랜배정호출() {
        User user = User.createCompanyOwner("owner@haja.com", "김민수", "$2a$hashed");
        Company company = mock(Company.class);
        when(company.getId()).thenReturn(99L);
        when(userRepository.save(any(User.class))).thenReturn(user);
        when(companyRepository.save(any(Company.class))).thenReturn(company);

        Company result = accountWriter.createAccount(
                "owner@haja.com", "김민수", "$2a$hashed",
                "(주)하자체크", "1234567890", "서울시 강남구", "101호",
                "/files/x.png", "{}", "1.0", "1.0");

        assertThat(result).isEqualTo(company);
        verify(planProvisioningService).ensureFreePlanForCompany(99L);
    }
}
