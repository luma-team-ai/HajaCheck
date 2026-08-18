package com.hajacheck.core.inspection.repository;

import com.hajacheck.core.defect.entity.Defect;
import com.hajacheck.core.defect.entity.DefectGrade;
import com.hajacheck.core.defect.entity.DefectStatus;
import com.hajacheck.core.defect.entity.DefectType;
import com.hajacheck.core.facility.entity.Facility;
import com.hajacheck.core.inspection.entity.Inspection;
import com.hajacheck.core.inspection.entity.InspectionStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.AbstractQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.support.PageableExecutionUtils;
import org.springframework.util.StringUtils;

/**
 * 점검 목록 조회(HAJA-393/#725) — JPQL {@code :param is null or col = :param} 패턴은 PostgreSQL
 * named enum(inspection_status_type) 파라미터를 null로 바인딩할 때 "could not determine data type of
 * parameter" 예외를 일으킨다(DefectRepositoryImpl과 동일한 이유). 필터가 없으면 predicate 자체를
 * 생성하지 않는 Criteria API 방식으로 우회한다.
 *
 * <p>#878(HAJA-452) — 하자 조건(자연어) 필터 확장: defectType/defectGrade/defectStatus 는 JOIN이 아니라
 * correlated EXISTS 서브쿼리로 건다. JOIN을 쓰면 한 점검에 하자가 여러 건일 때 점검 단위 페이지네이션이
 * 하자 단위로 뻥튀기되고, 서로 다른 하자가 각각 조건 일부만 만족해도 매칭돼버린다. EXISTS는 "같은 하자
 * 하나가 주어진 조건(type/grade/status)을 전부 동시에 만족"하는지를 강제하면서도 점검 1행당 결과 1행을
 * 유지한다(계약 §"GET /api/inspections — 하자 조건(자연어) 필터 확장" — nl-search가 산출하는
 * {type[], grade[], status[]} 는 "하나의 하자 프로파일"을 나타낸다).
 */
@RequiredArgsConstructor
public class InspectionRepositoryImpl implements InspectionRepositoryCustom {

    private final EntityManager em;

    @Override
    public Page<Inspection> findPageByCompanyIdAndFilters(
            InspectionSearchCriteria criteria, Pageable pageable) {

        CriteriaBuilder cb = em.getCriteriaBuilder();

        CriteriaQuery<Inspection> query = cb.createQuery(Inspection.class);
        Root<Inspection> root = query.from(Inspection.class);
        Join<Inspection, Facility> facility = root.join("facility");
        root.fetch("facility");

        query.select(root)
                .where(buildPredicates(cb, query, root, facility, criteria)
                        .toArray(new Predicate[0]))
                .orderBy(cb.desc(root.get("inspectionDate")), cb.desc(root.get("id")));

        List<Inspection> content = em.createQuery(query)
                .setFirstResult((int) pageable.getOffset())
                .setMaxResults(pageable.getPageSize())
                .getResultList();

        CriteriaQuery<Long> countQuery = cb.createQuery(Long.class);
        Root<Inspection> countRoot = countQuery.from(Inspection.class);
        Join<Inspection, Facility> countFacility = countRoot.join("facility");
        countQuery.select(cb.count(countRoot))
                .where(buildPredicates(cb, countQuery, countRoot, countFacility, criteria)
                        .toArray(new Predicate[0]));

        Long total = em.createQuery(countQuery).getSingleResult();

        return PageableExecutionUtils.getPage(content, pageable, () -> total);
    }

    private List<Predicate> buildPredicates(
            CriteriaBuilder cb, AbstractQuery<?> query, Root<Inspection> root, Join<Inspection, Facility> facility,
            InspectionSearchCriteria criteria) {
        List<Predicate> predicates = new ArrayList<>();
        predicates.add(cb.equal(facility.get("companyId"), criteria.companyId()));
        if (criteria.facilityId() != null) {
            predicates.add(cb.equal(root.get("facilityId"), criteria.facilityId()));
        }
        if (hasValues(criteria.statuses())) {
            predicates.add(root.get("status").in(criteria.statuses()));
        }
        if (hasValues(criteria.inspectionTypes())) {
            predicates.add(root.get("type").in(criteria.inspectionTypes()));
        }
        if (criteria.inspectionDateFrom() != null) {
            predicates.add(cb.greaterThanOrEqualTo(root.get("inspectionDate"), criteria.inspectionDateFrom()));
        }
        if (criteria.inspectionDateTo() != null) {
            predicates.add(cb.lessThanOrEqualTo(root.get("inspectionDate"), criteria.inspectionDateTo()));
        }
        if (criteria.roundNoMin() != null) {
            predicates.add(cb.greaterThanOrEqualTo(root.get("roundNo"), criteria.roundNoMin()));
        }
        if (criteria.roundNoMax() != null) {
            predicates.add(cb.lessThanOrEqualTo(root.get("roundNo"), criteria.roundNoMax()));
        }
        Predicate defectExists = buildDefectFilterExistsPredicate(
                cb, query, root, criteria.defectTypes(), criteria.defectGrades(), criteria.defectStatuses());
        if (defectExists != null) {
            predicates.add(defectExists);
        }
        addDefectCountPredicates(cb, query, root, criteria, predicates);
        return predicates;
    }

    private void addDefectCountPredicates(
            CriteriaBuilder cb, AbstractQuery<?> query, Root<Inspection> inspectionRoot,
            InspectionSearchCriteria criteria, List<Predicate> predicates) {
        if (criteria.defectCountMin() == null && criteria.defectCountMax() == null) {
            return;
        }
        Subquery<Long> countSubquery = query.subquery(Long.class);
        Root<Defect> defectRoot = countSubquery.from(Defect.class);
        countSubquery.select(cb.count(defectRoot))
                .where(
                        cb.equal(defectRoot.get("inspectionId"), inspectionRoot.get("id")),
                        cb.isFalse(defectRoot.get("deleted")));
        if (criteria.defectCountMin() != null && criteria.defectCountMax() != null) {
            predicates.add(cb.between(
                    countSubquery, criteria.defectCountMin(), criteria.defectCountMax()));
        } else if (criteria.defectCountMin() != null) {
            predicates.add(cb.greaterThanOrEqualTo(countSubquery, criteria.defectCountMin()));
        } else {
            predicates.add(cb.lessThanOrEqualTo(countSubquery, criteria.defectCountMax()));
        }
    }

    /**
     * #878 — defectType/defectGrade/defectStatus 가 1개 이상 주어지면, 그 점검(inspection) 소속 하자 중
     * "삭제되지 않았고 주어진 조건을 전부(AND) 만족하는" 하자가 하나라도 있는지 correlated EXISTS로 확인한다.
     * 세 파라미터가 전부 비어있으면 null 을 반환해 호출부가 predicate 자체를 추가하지 않게 한다(house style —
     * 필터가 없으면 이전 동작과 완전히 동일해야 회귀가 없다).
     */
    private Predicate buildDefectFilterExistsPredicate(
            CriteriaBuilder cb, AbstractQuery<?> query, Root<Inspection> inspectionRoot,
            List<DefectType> defectTypes, List<DefectGrade> defectGrades, List<DefectStatus> defectStatuses) {
        boolean hasType = defectTypes != null && !defectTypes.isEmpty();
        boolean hasGrade = defectGrades != null && !defectGrades.isEmpty();
        boolean hasStatus = defectStatuses != null && !defectStatuses.isEmpty();
        if (!hasType && !hasGrade && !hasStatus) {
            return null;
        }

        Subquery<Long> subquery = query.subquery(Long.class);
        Root<Defect> defectRoot = subquery.from(Defect.class);
        subquery.select(cb.literal(1L));

        List<Predicate> defectPredicates = new ArrayList<>();
        // 상관(correlation) — 이 서브쿼리를 아우터 Inspection 행 하나당 평가한다.
        defectPredicates.add(cb.equal(defectRoot.get("inspectionId"), inspectionRoot.get("id")));
        defectPredicates.add(cb.isFalse(defectRoot.get("deleted")));
        if (hasType) {
            defectPredicates.add(defectRoot.get("type").in(defectTypes));
        }
        if (hasGrade) {
            // DefectRepositoryImpl.buildPredicates 의 grade 필터(단일값, "X 등급 이상" 임계값
            // greaterThanOrEqualTo)와 의도적으로 다르다 — 이쪽은 nl-search(POST /api/defects/nl-search)가
            // 산출한 NlSearchFilters.grade 배열을 그대로 IN 절로 실어 정확 매칭한다(자연어가 이미 "D 이상"
            // 같은 임계값을 등급 배열로 풀어서 내려주므로, 여기서 또 임계값 의미를 얹으면 이중 해석이 된다).
            defectPredicates.add(defectRoot.get("grade").in(defectGrades));
        }
        if (hasStatus) {
            defectPredicates.add(defectRoot.get("status").in(defectStatuses));
        }
        subquery.where(defectPredicates.toArray(new Predicate[0]));

        return cb.exists(subquery);
    }

    private static boolean hasValues(Collection<?> values) {
        return values != null && !values.isEmpty();
    }

    /**
     * 마이페이지 "내 점검 이력" 목록(#844) — assignedInspectorId 또는 createdBy가 요청자 본인인
     * 점검을 회사 스코프 안에서 조회한다. 정렬 기준은 findPageByCompanyIdAndFilters와 동일
     * (inspectionDate desc, id desc).
     */
    @Override
    public Page<Inspection> findMyInspectionsPage(
            Long userId, Long companyId, LocalDate periodFrom, Pageable pageable) {

        CriteriaBuilder cb = em.getCriteriaBuilder();

        CriteriaQuery<Inspection> query = cb.createQuery(Inspection.class);
        Root<Inspection> root = query.from(Inspection.class);
        Join<Inspection, Facility> facility = root.join("facility");
        root.fetch("facility");

        query.select(root)
                .where(buildMyPredicates(cb, root, facility, userId, companyId, periodFrom)
                        .toArray(new Predicate[0]))
                .orderBy(cb.desc(root.get("inspectionDate")), cb.desc(root.get("id")));

        List<Inspection> content = em.createQuery(query)
                .setFirstResult((int) pageable.getOffset())
                .setMaxResults(pageable.getPageSize())
                .getResultList();

        CriteriaQuery<Long> countQuery = cb.createQuery(Long.class);
        Root<Inspection> countRoot = countQuery.from(Inspection.class);
        Join<Inspection, Facility> countFacility = countRoot.join("facility");
        countQuery.select(cb.count(countRoot))
                .where(buildMyPredicates(cb, countRoot, countFacility, userId, companyId, periodFrom)
                        .toArray(new Predicate[0]));

        Long total = em.createQuery(countQuery).getSingleResult();

        return PageableExecutionUtils.getPage(content, pageable, () -> total);
    }

    private List<Predicate> buildMyPredicates(
            CriteriaBuilder cb, Root<Inspection> root, Join<Inspection, Facility> facility,
            Long userId, Long companyId, LocalDate periodFrom) {
        List<Predicate> predicates = new ArrayList<>();
        predicates.add(cb.equal(facility.get("companyId"), companyId));
        predicates.add(cb.or(
                cb.equal(root.get("assignedInspectorId"), userId),
                cb.equal(root.get("createdBy"), userId)));
        if (periodFrom != null) {
            predicates.add(cb.greaterThanOrEqualTo(root.get("inspectionDate"), periodFrom));
        }
        return predicates;
    }

    @Override
    public Page<Inspection> findRecentInspectionsPage(
            Long companyId, Long facilityId, String facilityTypeCategory, Collection<InspectionStatus> statuses,
            String query, Collection<Long> matchingCreatorIds, Pageable pageable) {

        CriteriaBuilder cb = em.getCriteriaBuilder();

        CriteriaQuery<Inspection> selectQuery = cb.createQuery(Inspection.class);
        Root<Inspection> root = selectQuery.from(Inspection.class);
        Join<Inspection, Facility> facility = root.join("facility");
        // 시설물명은 companyId/facilityId 등의 predicate에만 쓰이고 응답 매핑은 서비스가
        // facilityRepository.findAllById로 배치 재조회하므로, 여기서 fetch join으로 전체 Facility를
        // 함께 로딩할 필요가 없다(중복 로딩 방지, code review P3 반영).

        selectQuery.select(root)
                .where(buildRecentPredicates(cb, root, facility, companyId, facilityId, facilityTypeCategory,
                                statuses, query, matchingCreatorIds)
                        .toArray(new Predicate[0]))
                // 대시보드 기존 최근 점검 정렬(findRecentByFacilityIds)과 동일 기준 — 기본 호출(필터 없음)의
                // 결과 순서가 오늘의 위젯과 100% 일치해야 하므로 Pageable.getSort()는 쓰지 않는다.
                //
                // #1667 — performed_at을 id보다 먼저 tie-break로 확장(inspection_date desc, performed_at
                // desc nulls last, id desc). 표준 JPA Criteria API에는 nulls-last 지정 수단이 없어(Order
                // 인터페이스에 nullPrecedence가 없다 — Hibernate 전용 확장 없이는 불가), performedAt이
                // null이면 1, 아니면 0인 보조 정렬키를 asc로 먼저 적용해 null 그룹을 뒤로 미는 표준 우회를
                // 쓴다(findRecentByFacilityIds/findLatestByFacilityIds와 결과 순서 계약을 유지하기 위함).
                .orderBy(cb.desc(root.get("inspectionDate")),
                        cb.asc(cb.<Integer>selectCase()
                                .when(cb.isNull(root.get("performedAt")), 1)
                                .otherwise(0)),
                        cb.desc(root.get("performedAt")),
                        cb.desc(root.get("id")));

        List<Inspection> content = em.createQuery(selectQuery)
                .setFirstResult((int) pageable.getOffset())
                .setMaxResults(pageable.getPageSize())
                .getResultList();

        CriteriaQuery<Long> countQuery = cb.createQuery(Long.class);
        Root<Inspection> countRoot = countQuery.from(Inspection.class);
        Join<Inspection, Facility> countFacility = countRoot.join("facility");
        countQuery.select(cb.count(countRoot))
                .where(buildRecentPredicates(cb, countRoot, countFacility, companyId, facilityId,
                                facilityTypeCategory, statuses, query, matchingCreatorIds)
                        .toArray(new Predicate[0]));

        Long total = em.createQuery(countQuery).getSingleResult();

        return PageableExecutionUtils.getPage(content, pageable, () -> total);
    }

    private List<Predicate> buildRecentPredicates(
            CriteriaBuilder cb, Root<Inspection> root, Join<Inspection, Facility> facility,
            Long companyId, Long facilityId, String facilityTypeCategory, Collection<InspectionStatus> statuses,
            String query, Collection<Long> matchingCreatorIds) {
        List<Predicate> predicates = new ArrayList<>();
        predicates.add(cb.equal(facility.get("companyId"), companyId));
        if (facilityId != null) {
            predicates.add(cb.equal(root.get("facilityId"), facilityId));
        }
        if (StringUtils.hasText(facilityTypeCategory)) {
            // facility.type은 "건물"처럼 단순값이거나(레거시) #731 등록 모달이 저장하는
            // "건물-긴급-1개월"류 컴파운드 값일 수 있어, 접두(prefix) LIKE로 종류 카테고리만 매칭한다.
            // 사용자가 카테고리 문자열에 LIKE 와일드카드(%, _)를 리터럴로 넣어도 매칭 오작동이 없도록
            // 이스케이프한 뒤, 실제 접두 매칭용 "%" 는 이스케이프하지 않은 채로 뒤에 붙인다.
            predicates.add(cb.like(facility.get("type"), escapeLikeWildcards(facilityTypeCategory.trim()) + "%",
                    LIKE_ESCAPE_CHAR));
        }
        if (statuses != null && !statuses.isEmpty()) {
            predicates.add(root.get("status").in(statuses));
        }
        if (StringUtils.hasText(query)) {
            // query는 호출부(DashboardService.escapeLikeWildcards)에서 이미 이스케이프해 넘어온다 —
            // findIdsByCompanyIdAndNameContaining(UserRepository)에 넘기는 값과 동일 이스케이프를
            // 공유해야 하므로 여기서 다시 이스케이프하지 않는다.
            String pattern = "%" + query.trim().toLowerCase() + "%";
            Predicate nameMatch = cb.like(cb.lower(facility.get("name")), pattern, LIKE_ESCAPE_CHAR);
            predicates.add(matchingCreatorIds == null || matchingCreatorIds.isEmpty()
                    ? nameMatch
                    : cb.or(nameMatch, root.get("createdBy").in(matchingCreatorIds)));
        }
        return predicates;
    }

    private static final char LIKE_ESCAPE_CHAR = '\\';

    // AdminUserService.normalizeKeyword와 동일 컨벤션 — 백슬래시부터 먼저 이스케이프해야 뒤이어
    // 삽입하는 이스케이프 문자와 충돌하지 않는다.
    private static String escapeLikeWildcards(String raw) {
        return raw.replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }
}
