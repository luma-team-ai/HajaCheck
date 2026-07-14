package com.hajacheck.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hajacheck.auth.config.AuthProperties;
import com.hajacheck.auth.dto.FindIdRequest;
import com.hajacheck.auth.dto.FindIdResponse;
import com.hajacheck.auth.dto.PasswordInquiryRequest;
import com.hajacheck.auth.dto.PasswordInquiryResponse;
import com.hajacheck.auth.dto.PasswordResetRequest;
import com.hajacheck.auth.entity.Company;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.repository.CompanyRepository;
import com.hajacheck.auth.repository.UserRepository;
import com.hajacheck.auth.support.TokenNamespaces;
import com.hajacheck.auth.support.TokenStore;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AccountRecoveryServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private CompanyRepository companyRepository;
    @Mock
    private TokenStore tokenStore;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private AuthProperties authProperties;

    @InjectMocks
    private AccountRecoveryService service;

    // ---------- 아이디 찾기 ----------

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

        assertThat(response.maskedEmail()).isEqualTo("owne***@haja.com");
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

        assertThat(response.maskedEmail()).isEqualTo("haja***@check.com");
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

    // ---------- 비밀번호 1단계 ----------

    @Test
    void 비번1단계_매칭_재설정토큰발급() {
        User user = mock(User.class);
        when(user.getId()).thenReturn(7L);
        when(user.getEmail()).thenReturn("haja@check.com");
        when(userRepository.findByEmail("haja@check.com")).thenReturn(Optional.of(user));
        Company company = mock(Company.class);
        when(company.getOwnerUserId()).thenReturn(7L);
        when(companyRepository.findByBusinessRegistrationNumber("1234567890")).thenReturn(Optional.of(company));
        when(authProperties.getPasswordResetTtl()).thenReturn(Duration.ofMinutes(10));
        when(tokenStore.issue(eq(TokenNamespaces.PASSWORD_RESET), eq("7"), any(Duration.class)))
                .thenReturn("reset-tok");

        PasswordInquiryResponse response = service.verifyForPasswordReset(
                new PasswordInquiryRequest("haja@check.com", "123-45-67890"));

        assertThat(response.resetToken()).isEqualTo("reset-tok");
        assertThat(response.expiresInSeconds()).isEqualTo(600L);
        assertThat(response.maskedEmail()).isEqualTo("haja***@check.com");
    }

    @Test
    void 비번1단계_소유자불일치_400_VERIFICATION_FAILED() {
        User user = mock(User.class);
        when(user.getId()).thenReturn(7L);
        when(userRepository.findByEmail("haja@check.com")).thenReturn(Optional.of(user));
        Company company = mock(Company.class);
        when(company.getOwnerUserId()).thenReturn(99L); // 다른 소유자
        when(companyRepository.findByBusinessRegistrationNumber("1234567890")).thenReturn(Optional.of(company));

        assertThatThrownBy(() -> service.verifyForPasswordReset(
                new PasswordInquiryRequest("haja@check.com", "123-45-67890")))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.AUTH_VERIFICATION_FAILED));
        verify(tokenStore, never()).issue(anyString(), anyString(), any());
    }

    @Test
    void 비번1단계_이메일미존재_400_VERIFICATION_FAILED() {
        when(userRepository.findByEmail(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.verifyForPasswordReset(
                new PasswordInquiryRequest("none@check.com", "123-45-67890")))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.AUTH_VERIFICATION_FAILED));
    }

    // ---------- 비밀번호 2단계 ----------

    @Test
    void 비번2단계_토큰유효_비밀번호변경() {
        when(tokenStore.consume(TokenNamespaces.PASSWORD_RESET, "reset-tok")).thenReturn(Optional.of("7"));
        User user = mock(User.class);
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));
        when(passwordEncoder.encode("newpass12")).thenReturn("$2a$new");

        service.resetPassword(new PasswordResetRequest("reset-tok", "newpass12"));

        verify(user).changePassword("$2a$new");
    }

    @Test
    void 비번2단계_토큰무효또는이미소비_400_RESET_TOKEN_INVALID() {
        when(tokenStore.consume(TokenNamespaces.PASSWORD_RESET, "bad")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resetPassword(new PasswordResetRequest("bad", "newpass12")))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.AUTH_RESET_TOKEN_INVALID));
        verify(passwordEncoder, never()).encode(anyString());
    }
}
