package com.hajacheck.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hajacheck.auth.dto.FindIdRequest;
import com.hajacheck.auth.dto.FindIdResponse;
import com.hajacheck.auth.entity.Company;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.repository.CompanyRepository;
import com.hajacheck.auth.repository.UserRepository;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * 아이디 찾기 단위 테스트. (비밀번호 찾기는 P1 로 제외됨 — 후속 #194.)
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AccountRecoveryServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private CompanyRepository companyRepository;

    @InjectMocks
    private AccountRecoveryService service;

    @Test
    void findId_대표자명매칭_마스킹이메일반환() {
        Company company = mock(Company.class);
        when(company.getOwnerUserId()).thenReturn(7L);
        when(companyRepository.findByBusinessRegistrationNumberAndRepresentativeName("1234567890", "김민수"))
                .thenReturn(Optional.of(company));
        User owner = mock(User.class);
        when(owner.getEmail()).thenReturn("owner@haja.com");
        when(userRepository.findById(7L)).thenReturn(Optional.of(owner));

        FindIdResponse response = service.findId(new FindIdRequest("123-45-67890", "김민수", null));

        assertThat(response.maskedEmail()).isEqualTo("o***@h***.com");
    }

    @Test
    void findId_상호명만매칭_마스킹이메일반환() {
        Company company = mock(Company.class);
        when(company.getOwnerUserId()).thenReturn(7L);
        when(companyRepository.findByBusinessRegistrationNumberAndName("1234567890", "(주)하자체크"))
                .thenReturn(Optional.of(company));
        User owner = mock(User.class);
        when(owner.getEmail()).thenReturn("haja@check.com");
        when(userRepository.findById(7L)).thenReturn(Optional.of(owner));

        FindIdResponse response = service.findId(new FindIdRequest("123-45-67890", null, "(주)하자체크"));

        assertThat(response.maskedEmail()).isEqualTo("h***@c***.com");
    }

    @Test
    void findId_무매칭_404_ACCOUNT_NOT_FOUND() {
        when(companyRepository.findByBusinessRegistrationNumberAndRepresentativeName(anyString(), anyString()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findId(new FindIdRequest("123-45-67890", "없는대표", null)))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.AUTH_ACCOUNT_NOT_FOUND));
    }

    @Test
    void findId_대표자명일치하나상호명불일치_404() {
        Company company = mock(Company.class);
        when(company.getName()).thenReturn("(주)진짜회사");
        when(companyRepository.findByBusinessRegistrationNumberAndRepresentativeName("1234567890", "김민수"))
                .thenReturn(Optional.of(company));

        // 둘 다 제공됐는데 상호명이 다르면 무매칭 처리.
        assertThatThrownBy(() -> service.findId(new FindIdRequest("123-45-67890", "김민수", "(주)가짜회사")))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.AUTH_ACCOUNT_NOT_FOUND));
    }
}
