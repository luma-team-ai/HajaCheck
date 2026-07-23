package com.hajacheck.core.defect.repository;

import com.hajacheck.core.defect.entity.Defect;
import com.hajacheck.core.defect.entity.DefectGrade;
import com.hajacheck.core.defect.entity.DefectStatus;
import com.hajacheck.core.defect.entity.DefectType;
import com.hajacheck.core.facility.entity.Facility;
import com.hajacheck.core.inspection.entity.Inspection;
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
 * 하자 목록 조회(HAJA-30) — JPQL {@code :param is null or col = :param} 패턴은 PostgreSQL named
 * enum(defect_type/defect_grade_type/defect_status_type) 파라미터를 null로 바인딩할 때
 * "could not determine data type of parameter" 예외를 일으킨다(Hibernate가 null 바인딩 시
 * 타입 OID를 드라이버에 전달하지 못함). 필터가 없으면 predicate 자체를 생성하지 않는 Criteria API
 * 방식으로 우회한다.
 */
@RequiredArgsConstructor
public class DefectRepositoryImpl implements DefectRepositoryCustom {

    private final EntityManager em;

    @Override
    public Page<Defect> findPageByCompanyIdAndFilters(
            Long companyId, DefectType type, DefectGrade grade, DefectStatus status, Pageable pageable) {

        CriteriaBuilder cb = em.getCriteriaBuilder();

        CriteriaQuery<Defect> query = cb.createQuery(Defect.class);
        Root<Defect> root = query.from(Defect.class);
        Join<Defect, Inspection> inspection = root.join("inspection");
        Join<Inspection, Facility> facility = inspection.join("facility");
        root.fetch("inspection").fetch("facility");

        query.select(root)
                .where(buildPredicates(cb, root, facility, companyId, type, grade, status).toArray(new Predicate[0]))
                .orderBy(cb.desc(root.get("createdAt")));

        List<Defect> content = em.createQuery(query)
                .setFirstResult((int) pageable.getOffset())
                .setMaxResults(pageable.getPageSize())
                .getResultList();

        CriteriaQuery<Long> countQuery = cb.createQuery(Long.class);
        Root<Defect> countRoot = countQuery.from(Defect.class);
        Join<Defect, Inspection> countInspection = countRoot.join("inspection");
        Join<Inspection, Facility> countFacility = countInspection.join("facility");
        countQuery.select(cb.count(countRoot))
                .where(buildPredicates(cb, countRoot, countFacility, companyId, type, grade, status)
                        .toArray(new Predicate[0]));

        Long total = em.createQuery(countQuery).getSingleResult();

        return PageableExecutionUtils.getPage(content, pageable, () -> total);
    }

    private List<Predicate> buildPredicates(
            CriteriaBuilder cb, Root<Defect> root, Join<Inspection, Facility> facility,
            Long companyId, DefectType type, DefectGrade grade, DefectStatus status) {
        List<Predicate> predicates = new ArrayList<>();
        predicates.add(cb.equal(facility.get("companyId"), companyId));
        predicates.add(cb.isFalse(root.get("deleted")));
        if (type != null) {
            predicates.add(cb.equal(root.get("type"), type));
        }
        if (grade != null) {
            // "등급: X 이상" 필터(임계값) — X 등급 및 X보다 심각한(선언순 이후) 등급까지 전부 포함.
            // PG named enum(defect_grade_type) 은 DDL 선언순(A<B<C<D<E)으로 네이티브 비교되므로
            // Comparable 경로(greaterThanOrEqualTo)로 바인딩하면 그대로 임계값 비교가 성립한다.
            predicates.add(cb.greaterThanOrEqualTo(root.<DefectGrade>get("grade"), grade));
        }
        if (status != null) {
            predicates.add(cb.equal(root.get("status"), status));
        }
        return predicates;
    }
}
