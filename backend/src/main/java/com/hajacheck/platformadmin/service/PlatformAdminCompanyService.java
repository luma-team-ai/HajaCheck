package com.hajacheck.platformadmin.service;

import com.hajacheck.auth.entity.CompanyStatus;
import com.hajacheck.auth.repository.CompanyRepository;
import com.hajacheck.platformadmin.dto.CompanyOptionResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 플랫폼 관리자 콘솔 — 사용자 등록 모달의 기업명 selectbox(#576, PR #626 후속 요구사항).
 * 심사 승인(APPROVED)된 기업만 후보로 노출한다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PlatformAdminCompanyService {

    private final CompanyRepository companyRepository;

    public List<CompanyOptionResponse> listAssignableCompanies() {
        return companyRepository.findByStatusOrderByNameAsc(CompanyStatus.APPROVED).stream()
                .map(CompanyOptionResponse::from)
                .toList();
    }
}
