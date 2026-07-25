package com.hajacheck.core.inspection.repository;

import com.hajacheck.core.facility.entity.Facility;
import com.hajacheck.core.inspection.entity.Inspection;
import com.hajacheck.core.inspection.entity.InspectionStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.support.PageableExecutionUtils;

/**
 * 점검 목록 조회(HAJA-393/#725) — JPQL {@code :param is null or col = :param} 패턴은 PostgreSQL
 * named enum(inspection_status_type) 파라미터를 null로 바인딩할 때 "could not determine data type of
 * parameter" 예외를 일으킨다(DefectRepositoryImpl과 동일한 이유). 필터가 없으면 predicate 자체를
 * 생성하지 않는 Criteria API 방식으로 우회한다.
 */
@RequiredArgsConstructor
public class InspectionRepositoryImpl implements InspectionRepositoryCustom {

    private final EntityManager em;

    @Override
    public Page<Inspection> findPageByCompanyIdAndFilters(
            Long companyId, Long facilityId, InspectionStatus status, Pageable pageable) {

        CriteriaBuilder cb = em.getCriteriaBuilder();

        CriteriaQuery<Inspection> query = cb.createQuery(Inspection.class);
        Root<Inspection> root = query.from(Inspection.class);
        Join<Inspection, Facility> facility = root.join("facility");
        root.fetch("facility");

        query.select(root)
                .where(buildPredicates(cb, root, facility, companyId, facilityId, status).toArray(new Predicate[0]))
                .orderBy(cb.desc(root.get("inspectionDate")), cb.desc(root.get("id")));

        List<Inspection> content = em.createQuery(query)
                .setFirstResult((int) pageable.getOffset())
                .setMaxResults(pageable.getPageSize())
                .getResultList();

        CriteriaQuery<Long> countQuery = cb.createQuery(Long.class);
        Root<Inspection> countRoot = countQuery.from(Inspection.class);
        Join<Inspection, Facility> countFacility = countRoot.join("facility");
        countQuery.select(cb.count(countRoot))
                .where(buildPredicates(cb, countRoot, countFacility, companyId, facilityId, status)
                        .toArray(new Predicate[0]));

        Long total = em.createQuery(countQuery).getSingleResult();

        return PageableExecutionUtils.getPage(content, pageable, () -> total);
    }

    private List<Predicate> buildPredicates(
            CriteriaBuilder cb, Root<Inspection> root, Join<Inspection, Facility> facility,
            Long companyId, Long facilityId, InspectionStatus status) {
        List<Predicate> predicates = new ArrayList<>();
        predicates.add(cb.equal(facility.get("companyId"), companyId));
        if (facilityId != null) {
            predicates.add(cb.equal(root.get("facilityId"), facilityId));
        }
        if (status != null) {
            predicates.add(cb.equal(root.get("status"), status));
        }
        return predicates;
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
}
