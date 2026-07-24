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
import org.springframework.data.domain.Page;
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
        Long companyId = em.find(User.class, ownerId).getCompanyId();
        Facility facility = Facility.builder().companyId(companyId).name(name).type("BUILDING").build();
        em.persist(facility);
        em.flush();
        return facility.getId();
    }

    private Long companyId(Long ownerId) {
        return em.find(User.class, ownerId).getCompanyId();
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
    void findByFacilityCompanyIdAndStatus_회사소유시설물전체에서_상태회차만_반환한다() {
        // 코드 리뷰 P2 4차/10차(회사별 분석 동시 실행 상한) — i.facility.companyId 암묵적 조인이
        // Facility 목록을 먼저 조회하지 않고도 회사 전체(여러 시설물) 범위로 모으는지, 타사 데이터가
        // 섞이지 않는지 고정한다. (호출부가 하트비트 isStuck으로 고착 유령을 제외하려고 count가 아닌
        // 목록을 받는다.)
        Long ownerA = seedOwner("owner-a@haja.com");
        Long ownerB = seedOwner("owner-b@haja.com");
        Long companyA = em.find(User.class, ownerA).getCompanyId();
        Long facilityA1 = seedFacility(ownerA, "A시설1");
        Long facilityA2 = seedFacility(ownerA, "A시설2");
        Long facilityB = seedFacility(ownerB, "B시설");
        inspectionRepository.save(
                newInspection(facilityA1, ownerA, ownerA, 1, LocalDate.of(2026, 7, 1), InspectionStatus.ANALYZING));
        inspectionRepository.save(
                newInspection(facilityA2, ownerA, ownerA, 1, LocalDate.of(2026, 7, 2), InspectionStatus.ANALYZING));
        inspectionRepository.save(
                newInspection(facilityA1, ownerA, ownerA, 2, LocalDate.of(2026, 7, 3), InspectionStatus.UPLOADING));
        // 타사(B)가 동시에 ANALYZING이어도 회사A 결과에 섞이면 안 된다.
        inspectionRepository.save(
                newInspection(facilityB, ownerB, ownerB, 1, LocalDate.of(2026, 7, 1), InspectionStatus.ANALYZING));

        List<Inspection> analyzing =
                inspectionRepository.findByFacilityCompanyIdAndStatus(companyA, InspectionStatus.ANALYZING);

        assertThat(analyzing).hasSize(2)
                .allMatch(i -> i.getStatus() == InspectionStatus.ANALYZING);
    }

    @Test
    void findByStatus_상태로_전체회차를_반환한다() {
        // 코드 리뷰 P2 10차(리퍼) — 회사 무관 전역 ANALYZING 조회. 리퍼가 이 목록을 훑어 고착을 복원한다.
        Long ownerA = seedOwner("owner-a@haja.com");
        Long ownerB = seedOwner("owner-b@haja.com");
        Long facilityA = seedFacility(ownerA, "A시설");
        Long facilityB = seedFacility(ownerB, "B시설");
        inspectionRepository.save(
                newInspection(facilityA, ownerA, ownerA, 1, LocalDate.of(2026, 7, 1), InspectionStatus.ANALYZING));
        inspectionRepository.save(
                newInspection(facilityB, ownerB, ownerB, 1, LocalDate.of(2026, 7, 1), InspectionStatus.ANALYZING));
        inspectionRepository.save(
                newInspection(facilityA, ownerA, ownerA, 2, LocalDate.of(2026, 7, 2), InspectionStatus.ANALYZED));

        List<Inspection> analyzing = inspectionRepository.findByStatus(InspectionStatus.ANALYZING);

        assertThat(analyzing).hasSize(2)
                .allMatch(i -> i.getStatus() == InspectionStatus.ANALYZING);
    }

    @Test
    void startAnalyzingIfNotRunning_허용소스상태에서만_1행영향_그외는0행() {
        // 코드 리뷰 P1 10차(불변식 고정) — 원자적 조건부 UPDATE가 "허용 소스 상태 집합"을 강제하는지
        // 직접 고정한다. 허용(CREATED/UPLOADING/ANALYZED)은 1행(ANALYZING 선점 성공), 그 외
        // (REVIEWED/REPORTED/이미 ANALYZING)는 0행이어야 한다 — 사전 체크와 무관하게 이 UPDATE
        // 자체가 REVIEWED/REPORTED 재분석 진입을 원자적으로 막는다는 게 핵심.
        Long ownerId = seedOwner("owner-a@haja.com");
        Long facilityId = seedFacility(ownerId, "테스트빌딩");
        java.util.EnumSet<InspectionStatus> allowed = java.util.EnumSet.of(
                InspectionStatus.CREATED, InspectionStatus.UPLOADING, InspectionStatus.ANALYZED);
        int roundNo = 1;

        for (InspectionStatus allowedSource : List.of(
                InspectionStatus.CREATED, InspectionStatus.UPLOADING, InspectionStatus.ANALYZED)) {
            Inspection insp = inspectionRepository.save(newInspection(
                    facilityId, ownerId, ownerId, roundNo++, LocalDate.of(2026, 7, 1), allowedSource));
            assertThat(inspectionRepository.startAnalyzingIfNotRunning(insp.getId(), InspectionStatus.ANALYZING, allowed))
                    .as("허용 소스 상태 %s 는 선점 성공(1행)", allowedSource)
                    .isEqualTo(1);
        }

        for (InspectionStatus blockedSource : List.of(
                InspectionStatus.REVIEWED, InspectionStatus.REPORTED, InspectionStatus.ANALYZING)) {
            Inspection insp = inspectionRepository.save(newInspection(
                    facilityId, ownerId, ownerId, roundNo++, LocalDate.of(2026, 7, 1), blockedSource));
            assertThat(inspectionRepository.startAnalyzingIfNotRunning(insp.getId(), InspectionStatus.ANALYZING, allowed))
                    .as("허용되지 않은 소스 상태 %s 는 선점 거부(0행)", blockedSource)
                    .isZero();
        }
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

    // ── HAJA-393/#725: 하자 목록·상세 화면 개편 — GET /api/inspections ──

    @Test
    void findPageByCompanyIdAndFilters_owner스코프_본인회사점검만조회() {
        Long ownerId = seedOwner("owner-a@haja.com");
        Long strangerId = seedOwner("owner-b@haja.com");
        Long facilityId = seedFacility(ownerId, "테스트빌딩");
        Long strangerFacilityId = seedFacility(strangerId, "타인빌딩");
        inspectionRepository.save(
                newInspection(facilityId, ownerId, ownerId, 1, LocalDate.of(2026, 7, 1), InspectionStatus.CREATED));
        inspectionRepository.save(newInspection(
                strangerFacilityId, strangerId, strangerId, 1, LocalDate.of(2026, 7, 1), InspectionStatus.CREATED));

        Page<Inspection> result = inspectionRepository.findPageByCompanyIdAndFilters(
                companyId(ownerId), null, null, PageRequest.of(0, 10));

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getTotalElements()).isEqualTo(1);
        assertThat(result.getContent().get(0).getFacilityId()).isEqualTo(facilityId);
    }

    @Test
    void findPageByCompanyIdAndFilters_시설물필터적용() {
        Long ownerId = seedOwner("owner-a@haja.com");
        Long facilityA = seedFacility(ownerId, "A시설");
        Long facilityB = seedFacility(ownerId, "B시설");
        inspectionRepository.save(
                newInspection(facilityA, ownerId, ownerId, 1, LocalDate.of(2026, 7, 1), InspectionStatus.CREATED));
        inspectionRepository.save(
                newInspection(facilityB, ownerId, ownerId, 1, LocalDate.of(2026, 7, 1), InspectionStatus.CREATED));

        Page<Inspection> result = inspectionRepository.findPageByCompanyIdAndFilters(
                companyId(ownerId), facilityA, null, PageRequest.of(0, 10));

        assertThat(result.getContent()).extracting(Inspection::getFacilityId).containsExactly(facilityA);
    }

    @Test
    void findPageByCompanyIdAndFilters_상태필터적용() {
        // status(PG named enum: inspection_status_type) 필터가 없을 때도 예외 없이 동작해야 한다 —
        // JPQL ":param is null or col = :param" 패턴이 이 타입의 null 바인딩에서 던지는
        // "could not determine data type of parameter" 회귀를 Criteria API 전환으로 우회했는지 검증.
        Long ownerId = seedOwner("owner-a@haja.com");
        Long facilityId = seedFacility(ownerId, "테스트빌딩");
        inspectionRepository.save(
                newInspection(facilityId, ownerId, ownerId, 1, LocalDate.of(2026, 7, 1), InspectionStatus.ANALYZED));
        inspectionRepository.save(
                newInspection(facilityId, ownerId, ownerId, 2, LocalDate.of(2026, 7, 2), InspectionStatus.REVIEWED));

        Page<Inspection> statusFiltered = inspectionRepository.findPageByCompanyIdAndFilters(
                companyId(ownerId), null, InspectionStatus.ANALYZED, PageRequest.of(0, 10));
        Page<Inspection> unfiltered = inspectionRepository.findPageByCompanyIdAndFilters(
                companyId(ownerId), null, null, PageRequest.of(0, 10));

        assertThat(statusFiltered.getContent()).extracting(Inspection::getStatus)
                .containsExactly(InspectionStatus.ANALYZED);
        assertThat(unfiltered.getContent()).hasSize(2);
    }

    @Test
    void findPageByCompanyIdAndFilters_점검일최신순_동일일자면id내림차순() {
        Long ownerId = seedOwner("owner-a@haja.com");
        Long facilityId = seedFacility(ownerId, "테스트빌딩");
        Inspection older = inspectionRepository.save(
                newInspection(facilityId, ownerId, ownerId, 1, LocalDate.of(2026, 7, 1), InspectionStatus.CREATED));
        Inspection newer = inspectionRepository.save(
                newInspection(facilityId, ownerId, ownerId, 2, LocalDate.of(2026, 7, 10), InspectionStatus.CREATED));
        Inspection sameDaySecond = inspectionRepository.save(
                newInspection(facilityId, ownerId, ownerId, 3, LocalDate.of(2026, 7, 10), InspectionStatus.CREATED));

        Page<Inspection> result = inspectionRepository.findPageByCompanyIdAndFilters(
                companyId(ownerId), null, null, PageRequest.of(0, 10));

        assertThat(result.getContent()).extracting(Inspection::getId)
                .containsExactly(sameDaySecond.getId(), newer.getId(), older.getId());
    }

    // ── 시설물 현황 목록(#540 ⑥, HAJA-378) 최근 점검일 배치 조회 ──

    @Test
    void findLatestByFacilityIds_시설물별최신점검1건씩만반환() {
        Long ownerId = seedOwner("owner-a@haja.com");
        Long facilityA = seedFacility(ownerId, "A시설");
        Long facilityB = seedFacility(ownerId, "B시설");
        inspectionRepository.save(
                newInspection(facilityA, ownerId, ownerId, 1, LocalDate.of(2026, 6, 1), InspectionStatus.CREATED));
        Inspection latestA = inspectionRepository.save(
                newInspection(facilityA, ownerId, ownerId, 2, LocalDate.of(2026, 7, 10), InspectionStatus.CREATED));
        Inspection onlyB = inspectionRepository.save(
                newInspection(facilityB, ownerId, ownerId, 1, LocalDate.of(2026, 7, 1), InspectionStatus.CREATED));

        List<Inspection> result =
                inspectionRepository.findLatestByFacilityIds(List.of(facilityA, facilityB));

        assertThat(result).hasSize(2)
                .extracting(Inspection::getId)
                .containsExactlyInAnyOrder(latestA.getId(), onlyB.getId());
    }

    @Test
    void findLatestByFacilityIds_동일날짜면id가큰최신등록건반환() {
        Long ownerId = seedOwner("owner-a@haja.com");
        Long facilityId = seedFacility(ownerId, "테스트빌딩");
        inspectionRepository.save(
                newInspection(facilityId, ownerId, ownerId, 1, LocalDate.of(2026, 7, 10), InspectionStatus.CREATED));
        Inspection newerSameDate = inspectionRepository.save(
                newInspection(facilityId, ownerId, ownerId, 2, LocalDate.of(2026, 7, 10), InspectionStatus.CREATED));

        List<Inspection> result = inspectionRepository.findLatestByFacilityIds(List.of(facilityId));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getId()).isEqualTo(newerSameDate.getId());
    }

    @Test
    void findLatestByFacilityIds_점검이력없는시설물은결과에없음() {
        Long ownerId = seedOwner("owner-a@haja.com");
        Long facilityWithNoInspection = seedFacility(ownerId, "점검이력없음");

        List<Inspection> result =
                inspectionRepository.findLatestByFacilityIds(List.of(facilityWithNoInspection));

        assertThat(result).isEmpty();
    }
}
