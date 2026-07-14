package com.hajacheck.auth.service;

import com.hajacheck.auth.dto.FindIdRequest;
import com.hajacheck.auth.dto.FindIdResponse;
import com.hajacheck.auth.entity.Company;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.repository.CompanyRepository;
import com.hajacheck.auth.repository.UserRepository;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import com.hajacheck.global.util.EmailMasker;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 아이디(이메일) 찾기.
 *
 * <p>계정 열거 방지: 무매칭은 AUTH_ACCOUNT_NOT_FOUND(404) 로 통일한다(어느 항목이 틀렸는지 노출하지 않음).
 * 검증 실패는 절대 401 금지.
 *
 * <p>⚠️ 비밀번호 찾기(이메일+사업자번호만으로 재설정 토큰 발급)는 계정 탈취 위험(P1)으로 이번 범위에서 제외됐다.
 * SMTP 미사용 결정에 따라 보안질문 방식으로 후속 처리(#194 / HAJA-172).
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class AccountRecoveryService {

    private final UserRepository userRepository;
    private final CompanyRepository companyRepository;

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

    private static boolean isNotBlank(String value) {
        return value != null && !value.isBlank();
    }
}
