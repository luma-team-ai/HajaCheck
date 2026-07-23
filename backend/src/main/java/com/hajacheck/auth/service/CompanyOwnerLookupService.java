package com.hajacheck.auth.service;

import com.hajacheck.auth.entity.Company;
import com.hajacheck.auth.repository.CompanyRepository;
import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 회사 소유자 사용자 식별자 조회 경계.
 *
 * <p>core 도메인이 회사 Entity/Repository를 직접 참조하지 않고 알림 수신자 같은 사용자 FK를 얻을 때 사용한다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CompanyOwnerLookupService {

    private final CompanyRepository companyRepository;

    public Map<Long, Long> findOwnerUserIds(Collection<Long> companyIds) {
        return companyRepository.findAllById(companyIds).stream()
                .collect(Collectors.toMap(Company::getId, Company::getOwnerUserId, keepFirst()));
    }

    private static <T> java.util.function.BinaryOperator<T> keepFirst() {
        return (first, ignored) -> first;
    }
}
