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
}
