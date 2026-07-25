package com.hajacheck.core.inspection.repository;

import com.hajacheck.core.defect.entity.DefectGrade;
import com.hajacheck.core.defect.entity.DefectStatus;
import com.hajacheck.core.defect.entity.DefectType;
import com.hajacheck.core.inspection.entity.Inspection;
import com.hajacheck.core.inspection.entity.InspectionStatus;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface InspectionRepositoryCustom {
    /**
     * #878(HAJA-452) — defectTypes/defectGrades/defectStatuses는 자연어 하자조건 검색
     * (POST /api/defects/nl-search)이 산출한 필터를 그대로 실어 재조회하는 용도. 셋 다 비어있으면
     * 기존 동작(하자 조건 무시)과 동일 — predicate 자체를 추가하지 않는다.
     */
    Page<Inspection> findPageByCompanyIdAndFilters(
            Long companyId, Long facilityId, InspectionStatus status,
            List<DefectType> defectTypes, List<DefectGrade> defectGrades, List<DefectStatus> defectStatuses,
            Pageable pageable);

    // 마이페이지 "내 점검 이력" 목록(#844) — periodFrom(nullable, KST 기준 산출)이 없으면(ALL) 기간 필터
    // predicate 자체를 생성하지 않는다(위 메서드와 동일한 Criteria API 우회 이유는 아니지만 — LocalDate는
    // named enum이 아니라 null 바인딩 자체는 안전하다 — 일관된 house style로 Criteria를 유지한다).
    Page<Inspection> findMyInspectionsPage(Long userId, Long companyId, LocalDate periodFrom, Pageable pageable);

    /**
     * 대시보드 "최근 점검 전체보기"(신규) 전용 페이지 조회 — {@link #findPageByCompanyIdAndFilters}와
     * 별도 메서드로 둔다(기존 하자 목록 화면 #725/#726이 이미 그 메서드에 의존 중이라, 시그니처를
     * 바꾸면 그 화면까지 회귀 위험에 노출된다). 정렬은 대시보드 기존 규칙과 동일하게
     * inspectionDate desc, id desc 고정(파라미터화하지 않음 — Pageable.getSort()는 무시).
     *
     * @param statuses          null/empty = 상태 필터 없음, non-empty = IN 조건(대시보드 4단계 한글
     *                          라벨 하나가 raw enum 여러 개에 대응 — 예: "분석중"→CREATED/UPLOADING/ANALYZING)
     * @param query             null/blank = 텍스트 검색 없음, 아니면 시설물명 대소문자 무시 LIKE
     * @param matchingCreatorIds query 로 이름이 매칭된 담당자(User) id 목록(서비스가 UserRepository로
     *                          선조회) — query 가 있고 매칭된 사용자가 있으면 facility.name OR
     *                          createdBy IN(:matchingCreatorIds)로 결합한다.
     */
    Page<Inspection> findRecentInspectionsPage(
            Long companyId, Long facilityId, Collection<InspectionStatus> statuses,
            String query, Collection<Long> matchingCreatorIds, Pageable pageable);
}
