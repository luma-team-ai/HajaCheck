package com.hajacheck.core.inspection.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.hajacheck.auth.entity.Company;
import com.hajacheck.auth.entity.CompanyMembership;
import com.hajacheck.auth.entity.Role;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.entity.UserStatus;
import com.hajacheck.core.facility.entity.Facility;
import com.hajacheck.core.inspection.entity.Inspection;
import com.hajacheck.core.inspection.entity.InspectionStatus;
import com.hajacheck.support.PostgresTestSupport;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

// 실 PG DDL(inspections) 대조 + facility_id/round_no unique·FK 정합 검증을 위해 Testcontainers PostgreSQL 사용.
// users → facilities → inspections 순으로 FK 를 충족하며 시드한다(HAJA-17 대시보드 집계).
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
@ActiveProfiles("test")
class InspectionRepositoryTest extends PostgresTestSupport {

    @Autowired
    private InspectionRepository inspectionRepository;

    @Autowired
    private TestEntityManager em;

    // HAJA-25 배정 검증 트리거(trg_inspections_check_assigned_inspector_company)는
    // assigned_inspector_id가 승인+검증된 회사에 속한 INSPECTOR/ADMIN 역할이면서 유효한
    // APPROVED 멤버십을 가질 것을 요구한다. 이 픽스처는 owner를 그대로 담당자로도 재사용하므로
    // 역할을 INSPECTOR로 두고 승인된 회사·멤버십을 함께 시드한다.
    private Long seedOwner(String email) {
        User owner = User.builder()
                .email(email)
                .name("소유자")
                .role(Role.INSPECTOR)
                .passwordHash("$2a$10$testtesttesttesttesttes")
                .status(UserStatus.ACTIVE)
                .build();
        em.persist(owner);
        em.flush();

        Company company = Company.createPendingReview(
                owner.getId(), "테스트회사-" + owner.getId(), "REG-" + owner.getId(), "대표자",
                "서울시 강남구", null, "https://files.example.com/registration.png", "{}");
        em.persist(company);
        em.flush();
        company.markBusinessVerified();
        company.approve(owner.getId());
        em.flush();

        em.persist(CompanyMembership.approvedOwner(company.getId(), owner.getId()));
        owner.assignToCompany(company.getId());
        em.flush();

        return owner.getId();
    }

    private Long seedFacility(Long ownerId, String name) {
        Facility facility = Facility.builder().ownerId(ownerId).name(name).type("BUILDING").build();
        em.persist(facility);
        em.flush();
        return facility.getId();
    }

    private Inspection newInspection(Long facilityId, Long createdBy, Long assignedInspectorId, int roundNo,
                                      LocalDate inspectionDate, InspectionStatus status) {
        return Inspection.builder()
                .facilityId(facilityId)
                .createdBy(createdBy)
                .assignedInspectorId(assignedInspectorId)
                .roundNo(roundNo)
                .inspectionDate(inspectionDate)
                .status(status)
                .build();
    }

    @Test
    void save_저장후_createdAt과id채워짐() {
        Long ownerId = seedOwner("owner-a@haja.com");
        Long facilityId = seedFacility(ownerId, "테스트빌딩");

        Inspection saved = inspectionRepository.save(
                newInspection(facilityId, ownerId, ownerId, 1, LocalDate.of(2026, 7, 1), InspectionStatus.CREATED));

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getStatus()).isEqualTo(InspectionStatus.CREATED);
        assertThat(saved.getAssignedInspectorId()).isEqualTo(ownerId);
    }

    @Test
    void countByFacilityIdInAndStatusIn_대상시설물의상태건만집계() {
        Long ownerA = seedOwner("owner-a@haja.com");
        Long ownerB = seedOwner("owner-b@haja.com");
        Long facilityA = seedFacility(ownerA, "A시설");
        Long facilityB = seedFacility(ownerB, "B시설");
        inspectionRepository.save(
                newInspection(facilityA, ownerA, ownerA, 1, LocalDate.of(2026, 7, 1), InspectionStatus.ANALYZED));
        inspectionRepository.save(
                newInspection(facilityA, ownerA, ownerA, 2, LocalDate.of(2026, 7, 2), InspectionStatus.REVIEWED));
        inspectionRepository.save(
                newInspection(facilityA, ownerA, ownerA, 3, LocalDate.of(2026, 7, 3), InspectionStatus.CREATED));
        // 타인(B) 소유 시설물의 점검 — facilityA 스코프 조회에 섞이면 안 된다.
        inspectionRepository.save(
                newInspection(facilityB, ownerB, ownerB, 1, LocalDate.of(2026, 7, 1), InspectionStatus.ANALYZED));

        long count = inspectionRepository.countByFacilityIdInAndStatusIn(
                List.of(facilityA), List.of(InspectionStatus.ANALYZED, InspectionStatus.REVIEWED));

        assertThat(count).isEqualTo(2);
    }

    @Test
    void countByFacilityIdInAndStatusInAndInspectionDateRange_기간내만집계() {
        Long ownerId = seedOwner("owner-a@haja.com");
        Long facilityId = seedFacility(ownerId, "테스트빌딩");
        inspectionRepository.save(
                newInspection(facilityId, ownerId, ownerId, 1, LocalDate.of(2026, 7, 5), InspectionStatus.ANALYZED));
        inspectionRepository.save(
                newInspection(facilityId, ownerId, ownerId, 2, LocalDate.of(2026, 6, 20), InspectionStatus.ANALYZED));

        long julyCount = inspectionRepository.countByFacilityIdInAndStatusInAndInspectionDateRange(
                List.of(facilityId), List.of(InspectionStatus.ANALYZED),
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 8, 1));

        assertThat(julyCount).isEqualTo(1);
    }

    @Test
    void findRecentByFacilityIds_최신순정렬_Pageable로건수제한() {
        Long ownerId = seedOwner("owner-a@haja.com");
        Long facilityId = seedFacility(ownerId, "테스트빌딩");
        inspectionRepository.save(
                newInspection(facilityId, ownerId, ownerId, 1, LocalDate.of(2026, 7, 1), InspectionStatus.CREATED));
        inspectionRepository.save(
                newInspection(facilityId, ownerId, ownerId, 2, LocalDate.of(2026, 7, 10), InspectionStatus.CREATED));
        inspectionRepository.save(
                newInspection(facilityId, ownerId, ownerId, 3, LocalDate.of(2026, 7, 5), InspectionStatus.CREATED));

        List<Inspection> result =
                inspectionRepository.findRecentByFacilityIds(List.of(facilityId), PageRequest.of(0, 10));

        assertThat(result).extracting(Inspection::getRoundNo).containsExactly(2, 3, 1);

        // Pageable 이 실제로 건수를 제한하는지 — 제한이 안 먹으면 RECENT_LIMIT 이 다시 무의미해진다(#351).
        List<Inspection> limited =
                inspectionRepository.findRecentByFacilityIds(List.of(facilityId), PageRequest.of(0, 2));

        assertThat(limited).extracting(Inspection::getRoundNo).containsExactly(2, 3);
    }
}
