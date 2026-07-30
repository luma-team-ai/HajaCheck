package com.hajacheck.auth.service;

import com.hajacheck.auth.repository.CompanyMembershipRepository;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 인증 principal의 회사 포인터를 현재 DB의 유효 소속 상태와 대조하는 중앙 회사 스코프 가드.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CompanyScopeGuard {

    private final CompanyMembershipRepository companyMembershipRepository;

    public void requireEffectiveMembership(Long userId, Long companyId) {
        if (userId == null
                || companyId == null
                || !companyMembershipRepository.existsEffectiveApprovedMembership(
                        companyId, userId, Instant.now())) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
    }
}
