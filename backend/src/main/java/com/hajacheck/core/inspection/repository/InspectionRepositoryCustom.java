package com.hajacheck.core.inspection.repository;

import com.hajacheck.core.inspection.entity.Inspection;
import com.hajacheck.core.inspection.entity.InspectionStatus;
import java.time.LocalDate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface InspectionRepositoryCustom {
    Page<Inspection> findPageByCompanyIdAndFilters(
            Long companyId, Long facilityId, InspectionStatus status, Pageable pageable);

    // 마이페이지 "내 점검 이력" 목록(#844) — periodFrom(nullable, KST 기준 산출)이 없으면(ALL) 기간 필터
    // predicate 자체를 생성하지 않는다(위 메서드와 동일한 Criteria API 우회 이유는 아니지만 — LocalDate는
    // named enum이 아니라 null 바인딩 자체는 안전하다 — 일관된 house style로 Criteria를 유지한다).
    Page<Inspection> findMyInspectionsPage(Long userId, Long companyId, LocalDate periodFrom, Pageable pageable);
}
