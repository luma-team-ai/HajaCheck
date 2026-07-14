package com.hajacheck.auth.service;

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
import com.hajacheck.global.util.EmailMasker;
import java.time.Duration;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 아이디 찾기 + 비밀번호 재설정(2단계).
 *
 * <p>계정 열거 방지: 아이디 찾기 무매칭은 AUTH_ACCOUNT_NOT_FOUND(404) 로, 비밀번호 1단계 불일치는
 * AUTH_VERIFICATION_FAILED(400) 로 통일한다(어느 항목이 틀렸는지 노출하지 않음). 검증 실패는 절대 401 금지.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class AccountRecoveryService {

    private final UserRepository userRepository;
    private final CompanyRepository companyRepository;
    private final TokenStore tokenStore;
    private final PasswordEncoder passwordEncoder;
    private final AuthProperties authProperties;

    /**
     * 아이디(이메일) 찾기 — 사업자번호 + (대표자명|상호명) 매칭 → 소유자 이메일 마스킹 반환.
     * 대표자명·상호명이 모두 오면 둘 다 일치해야 한다.
     */
    public FindIdResponse findId(FindIdRequest request) {
        String brn = CompanySignupService.normalizeBrn(request.businessRegistrationNumber());
        boolean hasRepName = isNotBlank(request.representativeName());
        boolean hasCompanyName = isNotBlank(request.companyName());

        Optional<Company> matched;
        if (hasRepName) {
            matched = companyRepository
                    .findByBusinessRegistrationNumberAndRepresentativeName(brn, request.representativeName());
            // 둘 다 제공된 경우 상호명도 일치해야 함.
            if (matched.isPresent() && hasCompanyName
                    && !matched.get().getName().equals(request.companyName())) {
                matched = Optional.empty();
            }
        } else {
            matched = companyRepository
                    .findByBusinessRegistrationNumberAndName(brn, request.companyName());
        }

        Company company = matched.orElseThrow(() -> new BusinessException(ErrorCode.AUTH_ACCOUNT_NOT_FOUND));
        User owner = userRepository.findById(company.getOwnerUserId())
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_ACCOUNT_NOT_FOUND));

        return FindIdResponse.of(EmailMasker.mask(owner.getEmail()));
    }

    /**
     * 비밀번호 찾기 1단계 — 이메일+사업자번호로 소유자 검증 후 재설정 토큰 발급.
     * (email 사용자 = 해당 사업자번호 회사의 owner 여야 함.) 불일치·미존재는 통일 400.
     */
    public PasswordInquiryResponse verifyForPasswordReset(PasswordInquiryRequest request) {
        String brn = CompanySignupService.normalizeBrn(request.businessRegistrationNumber());

        User user = userRepository.findByEmail(request.email())
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_VERIFICATION_FAILED));
        Company company = companyRepository.findByBusinessRegistrationNumber(brn)
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_VERIFICATION_FAILED));
        if (!company.getOwnerUserId().equals(user.getId())) {
            throw new BusinessException(ErrorCode.AUTH_VERIFICATION_FAILED);
        }

        Duration ttl = authProperties.getPasswordResetTtl();
        String resetToken = tokenStore.issue(TokenNamespaces.PASSWORD_RESET, user.getId().toString(), ttl);

        return new PasswordInquiryResponse(resetToken, EmailMasker.mask(user.getEmail()), ttl.toSeconds());
    }

    /**
     * 비밀번호 찾기 2단계 — 재설정 토큰 소비(단일 사용) 후 비밀번호 변경.
     * 토큰 무효/만료는 AUTH_RESET_TOKEN_INVALID(400).
     */
    @Transactional
    public void resetPassword(PasswordResetRequest request) {
        String userId = tokenStore.consume(TokenNamespaces.PASSWORD_RESET, request.resetToken())
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_RESET_TOKEN_INVALID));
        User user = userRepository.findById(Long.valueOf(userId))
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_RESET_TOKEN_INVALID));
        user.changePassword(passwordEncoder.encode(request.newPassword()));
    }

    private static boolean isNotBlank(String value) {
        return value != null && !value.isBlank();
    }
}
