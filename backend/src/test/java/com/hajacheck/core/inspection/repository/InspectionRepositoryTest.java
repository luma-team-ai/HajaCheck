package com.hajacheck.core.inspection.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.hajacheck.auth.entity.Company;
import com.hajacheck.auth.entity.CompanyMembership;
import com.hajacheck.auth.entity.Role;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.entity.UserStatus;
import com.hajacheck.core.defect.entity.Defect;
import com.hajacheck.core.defect.entity.DefectGrade;
import com.hajacheck.core.defect.entity.DefectStatus;
import com.hajacheck.core.defect.entity.DefectType;
import com.hajacheck.core.defect.repository.DefectRepository;
import com.hajacheck.core.facility.entity.Facility;
import com.hajacheck.core.inspection.entity.Inspection;
import com.hajacheck.core.inspection.entity.InspectionStatus;
import com.hajacheck.core.inspection.entity.InspectionType;
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
    private DefectRepository defectRepository;

    @Autowired
    private TestEntityManager em;

    // HAJA-25 배정 검증 트리거(trg_inspections_check_assigned_inspector_company)는
    // assigned_inspector_id가 승인+검증된 회사에 속한 INSPECTOR/ADMIN 역할이면서 유효한
    // APPROVED 멤버십을 가질 것을 요구한다. 이 픽스처는 owner를 그대로 담당자로도 재사용하므로
    // 역할을 INSPECTOR로 두고 승인된 회사·멤버십을 함께 시드한다.
    private Long seedOwner(String email) {
        return seedOwnerWithName(email, "소유자");
    }

    // 담당자명 검색 테스트(findRecentInspectionsPage)용 — 이름을 지정해 시드해야 매칭/비매칭을 구분할 수 있다.
    private Long seedOwnerWithName(String email, String name) {
        User owner = User.builder()
                .email(email)
                .name(name)
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

    // 담당자명 검색 테스트(findRecentInspectionsPage)용 — seedOwnerWithName은 매번 새 회사를 만들어서
    // "같은 회사 소속의 다른 이름을 가진 두 번째 담당자"를 표현할 수 없다. 기존 회사에 소속만 추가한다
    // (assigned_inspector_id 트리거가 "created_by의 승인된 회사"에 속한 INSPECTOR/ADMIN만 허용하므로,
    // 검색 대상 담당자와 점검을 만든 사람이 같은 회사여야 한다).
    private Long seedCompanyMember(Long companyId, String email, String name) {
        User member = User.builder()
                .email(email)
                .name(name)
                .role(Role.INSPECTOR)
                .passwordHash("$2a$10$testtesttesttesttesttes")
                .status(UserStatus.ACTIVE)
                .companyId(companyId)
                .build();
        em.persist(member);
        em.flush();
        em.persist(CompanyMembership.approvedOwner(companyId, member.getId()));
        em.flush();
        return member.getId();
    }

    private Long seedFacility(Long ownerId, String name) {
        Long companyId = em.find(User.class, ownerId).getCompanyId();
        Facility facility = Facility.builder().companyId(companyId).name(name).type("BUILDING").build();
        em.persist(facility);
        em.flush();
        return facility.getId();
    }

    // 마이페이지 "내 점검 이력"(#844) 시나리오 — 담당자(assignedInspectorId)와 등록자(createdBy)가
    // 서로 다른 사람인 케이스를 만들려면, HAJA-25 배정 검증 트리거가 요구하는 대로 "같은 회사"에
    // 속한 두 번째 INSPECTOR 멤버가 필요하다(InspectionAssignedInspectorCompanyBoundaryTest와 동일 헬퍼).
    private Long seedApprovedMember(Long companyId, String email) {
        User member = User.builder()
                .email(email)
                .name("동료")
                .role(Role.INSPECTOR)
                .passwordHash("$2a$10$testtesttesttesttesttes")
                .status(UserStatus.ACTIVE)
                .build();
        em.persist(member);
        em.flush();
        em.persist(CompanyMembership.approvedOwner(companyId, member.getId()));
        member.assignToCompany(companyId);
        em.flush();
        return member.getId();
    }

    // 시설물 종류 필터(#접두 매칭) 테스트 전용 — 레거시 단순값/#731 컴파운드값을 임의로 지정한다.
    private Long seedFacilityWithType(Long ownerId, String name, String type) {
        Long companyId = em.find(User.class, ownerId).getCompanyId();
        Facility facility = Facility.builder().companyId(companyId).name(name).type(type).build();
        em.persist(facility);
        em.flush();
        return facility.getId();
    }

    private Long companyId(Long ownerId) {
        return em.find(User.class, ownerId).getCompanyId();
    }

    private Inspection newInspection(Long facilityId, Long createdBy, Long assignedInspectorId, int roundNo,
                                      LocalDate inspectionDate, InspectionStatus status) {
        return newInspection(
                facilityId, createdBy, assignedInspectorId, roundNo, inspectionDate, status, InspectionType.REGULAR);
    }

    private Inspection newInspection(Long facilityId, Long createdBy, Long assignedInspectorId, int roundNo,
                                     LocalDate inspectionDate, InspectionStatus status, InspectionType type) {
        return Inspection.builder()
                .facilityId(facilityId)
                .createdBy(createdBy)
                .assignedInspectorId(assignedInspectorId)
                .roundNo(roundNo)
                .inspectionDate(inspectionDate)
                .status(status)
                .type(type)
                .build();
    }

    // #1667 — performed_at tie-break 테스트 전용. 나머지 필드는 newInspection과 동일 기본값을 쓴다.
    private Inspection newInspectionWithPerformedAt(
            Long facilityId, Long createdBy, Long assignedInspectorId, int roundNo,
            LocalDate inspectionDate, java.time.LocalDateTime performedAt) {
        return Inspection.builder()
                .facilityId(facilityId)
                .createdBy(createdBy)
                .assignedInspectorId(assignedInspectorId)
                .roundNo(roundNo)
                .inspectionDate(inspectionDate)
                .status(InspectionStatus.CREATED)
                .type(InspectionType.REGULAR)
                .performedAt(performedAt)
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
    void findMaxInspectionDateByFacilityIdAndStatus_확정회차만집계하고미확정최신회차는무시한다() {
        // #1591 리뷰 P2 — 다음 점검일 재계산의 "이 회차가 최신인가" 판정 기준. status 조건이 빠지면
        // 아직 분석 중인 3회차가 max를 끌어올려, 뒤늦게 확정된 2회차의 정당한 재계산이 스킵된다
        // (FacilityService#isStaleInspectionDate 참고). 그래서 REPORTED만 집계하는지를 실 DB로 고정한다.
        Long ownerId = seedOwner("owner-a@haja.com");
        Long facilityId = seedFacility(ownerId, "A시설");
        inspectionRepository.save(
                newInspection(facilityId, ownerId, ownerId, 1, LocalDate.of(2025, 7, 10), InspectionStatus.REPORTED));
        inspectionRepository.save(
                newInspection(facilityId, ownerId, ownerId, 2, LocalDate.of(2026, 1, 10), InspectionStatus.REPORTED));
        // 3회차는 생성만 됐고 아직 확정 전 — 이 집계에 잡히면 안 된다.
        inspectionRepository.save(
                newInspection(facilityId, ownerId, ownerId, 3, LocalDate.of(2026, 7, 10), InspectionStatus.ANALYZING));
        em.flush();
        em.clear();

        assertThat(inspectionRepository.findMaxInspectionDateByFacilityIdAndStatus(
                facilityId, InspectionStatus.REPORTED))
                .contains(LocalDate.of(2026, 1, 10));
        // 상태를 안 보는 기존 쿼리는 미확정 3회차까지 집계한다 — 두 쿼리가 실제로 다르다는 대조.
        assertThat(inspectionRepository.findMaxInspectionDateByFacilityId(facilityId))
                .contains(LocalDate.of(2026, 7, 10));
    }

    @Test
    void findMaxInspectionDateByFacilityIdAndStatus_확정회차가없으면_empty() {
        Long ownerId = seedOwner("owner-a@haja.com");
        Long facilityId = seedFacility(ownerId, "A시설");
        inspectionRepository.save(
                newInspection(facilityId, ownerId, ownerId, 1, LocalDate.of(2026, 7, 10), InspectionStatus.ANALYZING));
        em.flush();
        em.clear();

        assertThat(inspectionRepository.findMaxInspectionDateByFacilityIdAndStatus(
                facilityId, InspectionStatus.REPORTED))
                .isEmpty();
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
            assertThat(inspectionRepository.startAnalyzingIfNotRunning(insp.getId(), InspectionStatus.ANALYZING, allowed, false))
                    .as("허용 소스 상태 %s 는 선점 성공(1행)", allowedSource)
                    .isEqualTo(1);
        }

        for (InspectionStatus blockedSource : List.of(
                InspectionStatus.REVIEWED, InspectionStatus.REPORTED, InspectionStatus.ANALYZING)) {
            Inspection insp = inspectionRepository.save(newInspection(
                    facilityId, ownerId, ownerId, roundNo++, LocalDate.of(2026, 7, 1), blockedSource));
            assertThat(inspectionRepository.startAnalyzingIfNotRunning(insp.getId(), InspectionStatus.ANALYZING, allowed, false))
                    .as("허용되지 않은 소스 상태 %s 는 선점 거부(0행)", blockedSource)
                    .isZero();
        }
    }

    @Test
    void startAnalyzingIfNotRunning_비삭제하자가있으면_허용소스상태여도0행() {
        // 코드 리뷰 P1(머신 검수 2차) — 사전 체크(hasExistingDefects)와 이 원자적 UPDATE 사이에
        // createManualDefect로 하자가 끼어드는 TOCTOU를 막기 위해, WHERE 자체에 "비삭제 하자 없음"을
        // 강제한다. UPLOADING(허용 소스 상태)이라도 비삭제 하자가 이미 있으면 선점은 실패(0행)해야
        // 하고, 그 하자가 소프트삭제(deleted=true)된 것뿐이면 다시 선점 가능(1행)해야 한다.
        Long ownerId = seedOwner("owner-a@haja.com");
        Long facilityId = seedFacility(ownerId, "테스트빌딩");
        java.util.EnumSet<InspectionStatus> allowed = java.util.EnumSet.of(
                InspectionStatus.CREATED, InspectionStatus.UPLOADING, InspectionStatus.ANALYZED);

        Inspection withDefect = inspectionRepository.save(
                newInspection(facilityId, ownerId, ownerId, 1, LocalDate.of(2026, 7, 1), InspectionStatus.UPLOADING));
        em.persist(Defect.builder()
                .inspectionId(withDefect.getId())
                .type(DefectType.CRACK)
                .confidence(1.0)
                .build());
        em.flush();

        assertThat(inspectionRepository.startAnalyzingIfNotRunning(withDefect.getId(), InspectionStatus.ANALYZING, allowed, false))
                .as("비삭제 하자가 있으면 허용 소스 상태여도 선점 실패(0행)")
                .isZero();

        Inspection withOnlyDeletedDefect = inspectionRepository.save(
                newInspection(facilityId, ownerId, ownerId, 2, LocalDate.of(2026, 7, 1), InspectionStatus.UPLOADING));
        Defect deleted = Defect.builder()
                .inspectionId(withOnlyDeletedDefect.getId())
                .type(DefectType.CRACK)
                .confidence(1.0)
                .build();
        deleted.softDelete();
        em.persist(deleted);
        em.flush();

        assertThat(inspectionRepository.startAnalyzingIfNotRunning(withOnlyDeletedDefect.getId(), InspectionStatus.ANALYZING, allowed, false))
                .as("남은 하자가 전부 소프트삭제 상태면 선점 성공(1행)")
                .isEqualTo(1);
    }

    @Test
    void startAnalyzingIfNotRunning_allowExistingDefects_true면_비삭제하자있어도선점성공(){
        // 증분 분석(V42, #1654) — InspectionAnalysisService가 "ANALYZED 회차 + 미분석 사진 존재"를
        // 이미 판단했을 때만 allowExistingDefects=true를 넘긴다. 이 원자적 UPDATE가 실제 방어선이므로,
        // 서비스 계층 판단이 여기 그대로 반영돼 "비삭제 하자 없음" 조건이 건너뛰어져야 선점이 성공한다
        // (그래야 증분 분석이 실제로 시작될 수 있다) — false(기본)일 때는 여전히 거부돼야 한다.
        Long ownerId = seedOwner("owner-a@haja.com");
        Long facilityId = seedFacility(ownerId, "테스트빌딩");
        java.util.EnumSet<InspectionStatus> allowed = java.util.EnumSet.of(
                InspectionStatus.CREATED, InspectionStatus.UPLOADING, InspectionStatus.ANALYZED);

        Inspection withDefect = inspectionRepository.save(
                newInspection(facilityId, ownerId, ownerId, 1, LocalDate.of(2026, 7, 1), InspectionStatus.ANALYZED));
        em.persist(Defect.builder()
                .inspectionId(withDefect.getId())
                .type(DefectType.CRACK)
                .confidence(1.0)
                .build());
        em.flush();

        assertThat(inspectionRepository.startAnalyzingIfNotRunning(withDefect.getId(), InspectionStatus.ANALYZING, allowed, false))
                .as("allowExistingDefects=false(기본)면 비삭제 하자 존재만으로 여전히 거부(0행)")
                .isZero();
        assertThat(inspectionRepository.startAnalyzingIfNotRunning(withDefect.getId(), InspectionStatus.ANALYZING, allowed, true))
                .as("allowExistingDefects=true면 비삭제 하자가 있어도 선점 성공(1행) — 증분 분석 허용")
                .isEqualTo(1);
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

    @Test
    void findRecentByFacilityIds_동일날짜역순입력이어도_performedAt이늦은회차가먼저나온다() {
        // #1667 — round 1(id 작음)이 실제로는 오후에 촬영됐고 round 2(id 큼)는 오전에 촬영된, id 순서와
        // 실제 수행 순서가 어긋난 "역순 입력" 시나리오. id desc tie-break였다면 round 2가 먼저 나왔을
        // 것이나, performed_at desc가 id보다 우선하므로 round 1이 먼저 나온다.
        Long ownerId = seedOwner("owner-a@haja.com");
        Long facilityId = seedFacility(ownerId, "테스트빌딩");
        inspectionRepository.save(newInspectionWithPerformedAt(
                facilityId, ownerId, ownerId, 1, LocalDate.of(2026, 8, 18),
                java.time.LocalDateTime.of(2026, 8, 18, 15, 0)));
        inspectionRepository.save(newInspectionWithPerformedAt(
                facilityId, ownerId, ownerId, 2, LocalDate.of(2026, 8, 18),
                java.time.LocalDateTime.of(2026, 8, 18, 9, 0)));

        List<Inspection> result =
                inspectionRepository.findRecentByFacilityIds(List.of(facilityId), PageRequest.of(0, 10));

        assertThat(result).extracting(Inspection::getRoundNo).containsExactly(1, 2);
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
                companyId(ownerId), null, null, null, null, null, PageRequest.of(0, 10));

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
                companyId(ownerId), facilityA, null, null, null, null, PageRequest.of(0, 10));

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
                companyId(ownerId), null, InspectionStatus.ANALYZED, null, null, null, PageRequest.of(0, 10));
        Page<Inspection> unfiltered = inspectionRepository.findPageByCompanyIdAndFilters(
                companyId(ownerId), null, null, null, null, null, PageRequest.of(0, 10));

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
                companyId(ownerId), null, null, null, null, null, PageRequest.of(0, 10));

        assertThat(result.getContent()).extracting(Inspection::getId)
                .containsExactly(sameDaySecond.getId(), newer.getId(), older.getId());
    }

    @Test
    void findPageByCompanyIdAndFilters_동일일자_performedAt이id보다우선하는tie_break() {
        // #1667 P3 — findRecentInspectionsPage와 동일 정렬 계약(recentInspectionOrderBy 공용)이 이
        // 메서드에도 적용됐는지 직접 고정한다. id 생성 순서(4→5→6)와 performed_at 실제 순서가 어긋나도
        // 순수 id desc가 아니라 performed_at desc nulls last가 우선해야 한다.
        Long ownerId = seedOwner("owner-a@haja.com");
        Long facilityId = seedFacility(ownerId, "테스트빌딩");
        Inspection earlierPerformed = inspectionRepository.save(newInspectionWithPerformedAt(
                facilityId, ownerId, ownerId, 1, LocalDate.of(2026, 7, 20),
                java.time.LocalDateTime.of(2026, 7, 20, 9, 0)));
        Inspection laterPerformed = inspectionRepository.save(newInspectionWithPerformedAt(
                facilityId, ownerId, ownerId, 2, LocalDate.of(2026, 7, 20),
                java.time.LocalDateTime.of(2026, 7, 20, 15, 0)));
        Inspection noPerformedAt = inspectionRepository.save(
                newInspection(facilityId, ownerId, ownerId, 3, LocalDate.of(2026, 7, 20), InspectionStatus.CREATED));

        Page<Inspection> result = inspectionRepository.findPageByCompanyIdAndFilters(
                companyId(ownerId), null, null, null, null, null, PageRequest.of(0, 10));

        assertThat(result.getContent()).extracting(Inspection::getId)
                .containsExactly(laterPerformed.getId(), earlierPerformed.getId(), noPerformedAt.getId());
    }

    // ── #878(HAJA-452): 하자 조건(자연어) 필터 확장 — EXISTS 서브쿼리 ──

    @Test
    void findPageByCompanyIdAndFilters_하자유형필터_해당유형하자가진점검만포함() {
        Long ownerId = seedOwner("owner-a@haja.com");
        Long facilityId = seedFacility(ownerId, "테스트빌딩");
        Inspection withCrack = inspectionRepository.save(
                newInspection(facilityId, ownerId, ownerId, 1, LocalDate.of(2026, 7, 1), InspectionStatus.ANALYZED));
        Inspection withSpalling = inspectionRepository.save(
                newInspection(facilityId, ownerId, ownerId, 2, LocalDate.of(2026, 7, 2), InspectionStatus.ANALYZED));
        em.persist(Defect.builder().inspectionId(withCrack.getId()).type(DefectType.CRACK).confidence(0.9).build());
        em.persist(Defect.builder().inspectionId(withSpalling.getId()).type(DefectType.SPALLING).confidence(0.9)
                .build());
        em.flush();

        Page<Inspection> result = inspectionRepository.findPageByCompanyIdAndFilters(
                companyId(ownerId), null, null, List.of(DefectType.CRACK), null, null, PageRequest.of(0, 10));

        assertThat(result.getContent()).extracting(Inspection::getId).containsExactly(withCrack.getId());
    }

    @Test
    void findPageByCompanyIdAndFilters_하자등급복수필터_배열내OR매칭() {
        Long ownerId = seedOwner("owner-a@haja.com");
        Long facilityId = seedFacility(ownerId, "테스트빌딩");
        Inspection gradeD = inspectionRepository.save(
                newInspection(facilityId, ownerId, ownerId, 1, LocalDate.of(2026, 7, 1), InspectionStatus.ANALYZED));
        Inspection gradeE = inspectionRepository.save(
                newInspection(facilityId, ownerId, ownerId, 2, LocalDate.of(2026, 7, 2), InspectionStatus.ANALYZED));
        Inspection gradeA = inspectionRepository.save(
                newInspection(facilityId, ownerId, ownerId, 3, LocalDate.of(2026, 7, 3), InspectionStatus.ANALYZED));
        em.persist(Defect.builder().inspectionId(gradeD.getId()).type(DefectType.CRACK).confidence(0.9)
                .grade(DefectGrade.D).build());
        em.persist(Defect.builder().inspectionId(gradeE.getId()).type(DefectType.CRACK).confidence(0.9)
                .grade(DefectGrade.E).build());
        em.persist(Defect.builder().inspectionId(gradeA.getId()).type(DefectType.CRACK).confidence(0.9)
                .grade(DefectGrade.A).build());
        em.flush();

        Page<Inspection> result = inspectionRepository.findPageByCompanyIdAndFilters(
                companyId(ownerId), null, null, null, List.of(DefectGrade.D, DefectGrade.E), null,
                PageRequest.of(0, 10));

        assertThat(result.getContent()).extracting(Inspection::getId)
                .containsExactlyInAnyOrder(gradeD.getId(), gradeE.getId());
    }

    @Test
    void findPageByCompanyIdAndFilters_복수조건AND_같은하자가전부만족해야매칭_다른하자로나뉘면미매칭() {
        // 계약 §"GET /api/inspections — 하자 조건(자연어) 필터 확장" — type+grade는 "하나의 하자"가
        // 동시에 만족해야 매칭이다. 서로 다른 하자가 조건을 나눠 만족하는 점검은 매칭되면 안 된다.
        Long ownerId = seedOwner("owner-a@haja.com");
        Long facilityId = seedFacility(ownerId, "테스트빌딩");
        Inspection sameDefectMatches = inspectionRepository.save(
                newInspection(facilityId, ownerId, ownerId, 1, LocalDate.of(2026, 7, 1), InspectionStatus.ANALYZED));
        Inspection splitAcrossDefects = inspectionRepository.save(
                newInspection(facilityId, ownerId, ownerId, 2, LocalDate.of(2026, 7, 2), InspectionStatus.ANALYZED));
        // 하나의 하자가 CRACK이면서 동시에 D등급 — 매칭돼야 함.
        em.persist(Defect.builder().inspectionId(sameDefectMatches.getId()).type(DefectType.CRACK).confidence(0.9)
                .grade(DefectGrade.D).build());
        // CRACK이지만 등급은 A(불일치) + SPALLING이면서 D등급(불일치) — 어느 하자도 둘 다 만족 못함.
        em.persist(Defect.builder().inspectionId(splitAcrossDefects.getId()).type(DefectType.CRACK).confidence(0.9)
                .grade(DefectGrade.A).build());
        em.persist(Defect.builder().inspectionId(splitAcrossDefects.getId()).type(DefectType.SPALLING)
                .confidence(0.9).grade(DefectGrade.D).build());
        em.flush();

        Page<Inspection> result = inspectionRepository.findPageByCompanyIdAndFilters(
                companyId(ownerId), null, null, List.of(DefectType.CRACK), List.of(DefectGrade.D), null,
                PageRequest.of(0, 10));

        assertThat(result.getContent()).extracting(Inspection::getId)
                .containsExactly(sameDefectMatches.getId());
    }

    @Test
    void findPageByCompanyIdAndFilters_매칭하자여러개있어도점검중복없이1건() {
        // EXISTS 서브쿼리(JOIN 아님) — 한 점검에 조건을 만족하는 하자가 여러 개여도 결과에 점검이
        // 중복되지 않아야 한다(점검 단위 페이지네이션 유지).
        Long ownerId = seedOwner("owner-a@haja.com");
        Long facilityId = seedFacility(ownerId, "테스트빌딩");
        Inspection inspection = inspectionRepository.save(
                newInspection(facilityId, ownerId, ownerId, 1, LocalDate.of(2026, 7, 1), InspectionStatus.ANALYZED));
        em.persist(Defect.builder().inspectionId(inspection.getId()).type(DefectType.CRACK).confidence(0.9)
                .grade(DefectGrade.D).build());
        em.persist(Defect.builder().inspectionId(inspection.getId()).type(DefectType.CRACK).confidence(0.8)
                .grade(DefectGrade.E).build());
        em.flush();

        Page<Inspection> result = inspectionRepository.findPageByCompanyIdAndFilters(
                companyId(ownerId), null, null, List.of(DefectType.CRACK), null, null, PageRequest.of(0, 10));

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getTotalElements()).isEqualTo(1);
    }

    @Test
    void findPageByCompanyIdAndFilters_소프트삭제된하자는매칭제외() {
        Long ownerId = seedOwner("owner-a@haja.com");
        Long facilityId = seedFacility(ownerId, "테스트빌딩");
        Inspection inspection = inspectionRepository.save(
                newInspection(facilityId, ownerId, ownerId, 1, LocalDate.of(2026, 7, 1), InspectionStatus.ANALYZED));
        Defect deleted = Defect.builder().inspectionId(inspection.getId()).type(DefectType.CRACK).confidence(0.9)
                .build();
        deleted.softDelete();
        em.persist(deleted);
        em.flush();

        Page<Inspection> result = inspectionRepository.findPageByCompanyIdAndFilters(
                companyId(ownerId), null, null, List.of(DefectType.CRACK), null, null, PageRequest.of(0, 10));

        assertThat(result.getContent()).isEmpty();
        assertThat(result.getTotalElements()).isZero();
    }

    @Test
    void findPageByCompanyIdAndFilters_미매칭조건_빈페이지() {
        Long ownerId = seedOwner("owner-a@haja.com");
        Long facilityId = seedFacility(ownerId, "테스트빌딩");
        Inspection inspection = inspectionRepository.save(
                newInspection(facilityId, ownerId, ownerId, 1, LocalDate.of(2026, 7, 1), InspectionStatus.ANALYZED));
        em.persist(Defect.builder().inspectionId(inspection.getId()).type(DefectType.CRACK).confidence(0.9).build());
        em.flush();

        Page<Inspection> result = inspectionRepository.findPageByCompanyIdAndFilters(
                companyId(ownerId), null, null, List.of(DefectType.SPALLING), null, null, PageRequest.of(0, 10));

        assertThat(result.getContent()).isEmpty();
        assertThat(result.getTotalElements()).isZero();
    }

    @Test
    void findPageByCompanyIdAndFilters_하자조건파라미터모두없으면_기존동작과동일회귀없음() {
        Long ownerId = seedOwner("owner-a@haja.com");
        Long facilityId = seedFacility(ownerId, "테스트빌딩");
        Inspection noDefects = inspectionRepository.save(
                newInspection(facilityId, ownerId, ownerId, 1, LocalDate.of(2026, 7, 1), InspectionStatus.CREATED));
        Inspection withDefect = inspectionRepository.save(
                newInspection(facilityId, ownerId, ownerId, 2, LocalDate.of(2026, 7, 2), InspectionStatus.ANALYZED));
        em.persist(Defect.builder().inspectionId(withDefect.getId()).type(DefectType.CRACK).confidence(0.9).build());
        em.flush();

        Page<Inspection> result = inspectionRepository.findPageByCompanyIdAndFilters(
                companyId(ownerId), null, null, List.of(), List.of(), List.of(), PageRequest.of(0, 10));

        assertThat(result.getContent()).hasSize(2);
        assertThat(result.getContent()).extracting(Inspection::getId)
                .containsExactlyInAnyOrder(noDefects.getId(), withDefect.getId());
    }

    @Test
    void findPageByCompanyIdAndFilters_점검축과하자프로파일과전체하자건수양쪽범위_AND결합() {
        Long ownerId = seedOwner("owner-filter-combined@haja.com");
        Long facilityId = seedFacility(ownerId, "복합필터시설");
        Inspection matching = inspectionRepository.save(newInspection(
                facilityId, ownerId, ownerId, 1, LocalDate.of(2026, 6, 15),
                InspectionStatus.REVIEWED, InspectionType.REGULAR));
        Inspection wrongCount = inspectionRepository.save(newInspection(
                facilityId, ownerId, ownerId, 2, LocalDate.of(2026, 6, 20),
                InspectionStatus.REVIEWED, InspectionType.REGULAR));

        em.persist(Defect.builder().inspectionId(matching.getId()).type(DefectType.CRACK).grade(DefectGrade.D)
                .status(DefectStatus.CONFIRMED).confidence(0.9).build());
        em.persist(Defect.builder().inspectionId(matching.getId()).type(DefectType.SPALLING).grade(DefectGrade.A)
                .status(DefectStatus.DETECTED).confidence(0.9).build());
        em.persist(Defect.builder().inspectionId(matching.getId()).type(DefectType.PAINT_DAMAGE).grade(DefectGrade.B)
                .status(DefectStatus.RESOLVED).confidence(0.9).build());
        Defect deleted = Defect.builder().inspectionId(matching.getId()).type(DefectType.REBAR_EXPOSURE)
                .grade(DefectGrade.E).status(DefectStatus.CONFIRMED).confidence(0.9).build();
        deleted.softDelete();
        em.persist(deleted);

        em.persist(Defect.builder().inspectionId(wrongCount.getId()).type(DefectType.CRACK).grade(DefectGrade.D)
                .status(DefectStatus.CONFIRMED).confidence(0.9).build());
        em.flush();

        InspectionSearchCriteria criteria = new InspectionSearchCriteria(
                companyId(ownerId), null,
                List.of(InspectionStatus.REVIEWED, InspectionStatus.REPORTED),
                List.of(InspectionType.REGULAR),
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30),
                1, 2, 2L, 3L,
                List.of(DefectType.CRACK), List.of(DefectGrade.D), List.of(DefectStatus.CONFIRMED));

        Page<Inspection> result =
                inspectionRepository.findPageByCompanyIdAndFilters(criteria, PageRequest.of(0, 10));

        assertThat(result.getContent()).extracting(Inspection::getId).containsExactly(matching.getId());
        assertThat(result.getTotalElements()).isEqualTo(1);
    }

    @Test
    void findPageByCompanyIdAndFilters_전체하자0건_논리삭제하자는집계제외() {
        Long ownerId = seedOwner("owner-count-zero@haja.com");
        Long facilityId = seedFacility(ownerId, "하자0건시설");
        Inspection noDefect = inspectionRepository.save(newInspection(
                facilityId, ownerId, ownerId, 1, LocalDate.of(2026, 7, 1), InspectionStatus.CREATED));
        Inspection deletedOnly = inspectionRepository.save(newInspection(
                facilityId, ownerId, ownerId, 2, LocalDate.of(2026, 7, 2), InspectionStatus.CREATED));
        Inspection liveDefect = inspectionRepository.save(newInspection(
                facilityId, ownerId, ownerId, 3, LocalDate.of(2026, 7, 3), InspectionStatus.CREATED));

        Defect deleted = Defect.builder().inspectionId(deletedOnly.getId()).type(DefectType.CRACK)
                .confidence(0.9).build();
        deleted.softDelete();
        em.persist(deleted);
        em.persist(Defect.builder().inspectionId(liveDefect.getId()).type(DefectType.CRACK).confidence(0.9).build());
        em.flush();

        InspectionSearchCriteria criteria = new InspectionSearchCriteria(
                companyId(ownerId), null, null, null, null, null,
                null, null, 0L, 0L, null, null, null);

        Page<Inspection> result =
                inspectionRepository.findPageByCompanyIdAndFilters(criteria, PageRequest.of(0, 10));

        assertThat(result.getContent()).extracting(Inspection::getId)
                .containsExactlyInAnyOrder(noDefect.getId(), deletedOnly.getId());
        assertThat(result.getTotalElements()).isEqualTo(2);
    }

    // ── 대시보드 "최근 점검 전체보기"(신규) — findRecentInspectionsPage ──

    @Test
    void findRecentInspectionsPage_필터없으면_기존findRecentByFacilityIds와동일순서() {
        // 스펙 요구사항: 파라미터 없이 호출하면 기존 위젯(findRecentByFacilityIds, 상위 10건)과
        // 내용·순서가 동일해야 한다. 신규 엔드포인트는 별도 메서드지만, 같은 정렬 기준(점검일desc,
        // performed_at desc nulls last, id desc — #1667) 위에서 같은 결과 집합을 내야 한다는 계약을
        // 여기서 고정한다.
        Long ownerId = seedOwner("owner-a@haja.com");
        Long facilityId = seedFacility(ownerId, "테스트빌딩");
        inspectionRepository.save(
                newInspection(facilityId, ownerId, ownerId, 1, LocalDate.of(2026, 7, 1), InspectionStatus.CREATED));
        inspectionRepository.save(
                newInspection(facilityId, ownerId, ownerId, 2, LocalDate.of(2026, 7, 10), InspectionStatus.CREATED));
        inspectionRepository.save(
                newInspection(facilityId, ownerId, ownerId, 3, LocalDate.of(2026, 7, 5), InspectionStatus.CREATED));

        // #1667 — 동일 날짜(7/20) + performed_at 상이 시나리오. id 생성 순서(4→5→6)와 performed_at
        // 실제 수행 순서가 완전히 어긋나도록 심는다: id가 가장 큰 round6은 performed_at이 null(미디어
        // 없음), id가 중간인 round5가 가장 늦은 performed_at(최신), id가 가장 작은 round4가 그 다음.
        // 순수 id desc였다면 round6→round5→round4 순이었을 것이나, performed_at desc nulls last
        // 규칙상 round5→round4→round6 순이어야 한다 — findRecentByFacilityIds(JPQL)와
        // findRecentInspectionsPage(Criteria, selectCase 기반 nulls-last 우회)가 이 순서에 동일하게
        // 합의하는지가 이 테스트의 핵심 검증 대상이다.
        inspectionRepository.save(newInspectionWithPerformedAt(
                facilityId, ownerId, ownerId, 4, LocalDate.of(2026, 7, 20),
                java.time.LocalDateTime.of(2026, 7, 20, 9, 0)));
        inspectionRepository.save(newInspectionWithPerformedAt(
                facilityId, ownerId, ownerId, 5, LocalDate.of(2026, 7, 20),
                java.time.LocalDateTime.of(2026, 7, 20, 15, 0)));
        inspectionRepository.save(
                newInspection(facilityId, ownerId, ownerId, 6, LocalDate.of(2026, 7, 20), InspectionStatus.CREATED));

        List<Inspection> legacy =
                inspectionRepository.findRecentByFacilityIds(List.of(facilityId), PageRequest.of(0, 10));
        Page<Inspection> newEndpoint = inspectionRepository.findRecentInspectionsPage(
                companyId(ownerId), null, null, java.util.Set.of(), null, List.of(), PageRequest.of(0, 10));

        assertThat(newEndpoint.getContent()).extracting(Inspection::getId)
                .containsExactlyElementsOf(legacy.stream().map(Inspection::getId).toList());
        // 위 containsExactlyElementsOf만으로는 "두 메서드가 서로 일치"만 보장하고 "그 일치한 순서가
        // 실제로 의도한 순서"인지는 못 잡는다(둘 다 똑같이 틀렸을 가능성) — round5(round6, round4)
        // 상대 순서를 라운드 번호로 직접 고정한다.
        assertThat(legacy).extracting(Inspection::getRoundNo)
                .containsExactly(5, 4, 6, 2, 3, 1);
        assertThat(newEndpoint.getContent()).extracting(Inspection::getRoundNo)
                .containsExactly(5, 4, 6, 2, 3, 1);
    }

    @Test
    void findRecentInspectionsPage_owner스코프_타사데이터제외() {
        Long ownerId = seedOwner("owner-a@haja.com");
        Long strangerId = seedOwner("owner-b@haja.com");
        Long facilityId = seedFacility(ownerId, "테스트빌딩");
        Long strangerFacilityId = seedFacility(strangerId, "타인빌딩");
        inspectionRepository.save(
                newInspection(facilityId, ownerId, ownerId, 1, LocalDate.of(2026, 7, 1), InspectionStatus.CREATED));
        inspectionRepository.save(newInspection(
                strangerFacilityId, strangerId, strangerId, 1, LocalDate.of(2026, 7, 1), InspectionStatus.CREATED));

        Page<Inspection> result = inspectionRepository.findRecentInspectionsPage(
                companyId(ownerId), null, null, java.util.Set.of(), null, List.of(), PageRequest.of(0, 10));

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).getFacilityId()).isEqualTo(facilityId);
    }

    @Test
    void findRecentInspectionsPage_상태집합필터() {
        Long ownerId = seedOwner("owner-a@haja.com");
        Long facilityId = seedFacility(ownerId, "테스트빌딩");
        inspectionRepository.save(
                newInspection(facilityId, ownerId, ownerId, 1, LocalDate.of(2026, 7, 1), InspectionStatus.CREATED));
        inspectionRepository.save(
                newInspection(facilityId, ownerId, ownerId, 2, LocalDate.of(2026, 7, 2), InspectionStatus.UPLOADING));
        inspectionRepository.save(
                newInspection(facilityId, ownerId, ownerId, 3, LocalDate.of(2026, 7, 3), InspectionStatus.REVIEWED));

        // "분석중" 라벨은 CREATED/UPLOADING/ANALYZING 을 아우른다(RECENT_STATUS_LABEL_GROUPS).
        Page<Inspection> result = inspectionRepository.findRecentInspectionsPage(
                companyId(ownerId), null, null,
                java.util.EnumSet.of(InspectionStatus.CREATED, InspectionStatus.UPLOADING, InspectionStatus.ANALYZING),
                null, List.of(), PageRequest.of(0, 10));

        assertThat(result.getContent()).extracting(Inspection::getStatus)
                .containsExactlyInAnyOrder(InspectionStatus.CREATED, InspectionStatus.UPLOADING);
    }

    @Test
    void findRecentInspectionsPage_시설물필터적용() {
        Long ownerId = seedOwner("owner-a@haja.com");
        Long facilityA = seedFacility(ownerId, "A시설");
        Long facilityB = seedFacility(ownerId, "B시설");
        inspectionRepository.save(
                newInspection(facilityA, ownerId, ownerId, 1, LocalDate.of(2026, 7, 1), InspectionStatus.CREATED));
        inspectionRepository.save(
                newInspection(facilityB, ownerId, ownerId, 1, LocalDate.of(2026, 7, 1), InspectionStatus.CREATED));

        Page<Inspection> result = inspectionRepository.findRecentInspectionsPage(
                companyId(ownerId), facilityA, null, java.util.Set.of(), null, List.of(), PageRequest.of(0, 10));

        assertThat(result.getContent()).extracting(Inspection::getFacilityId).containsExactly(facilityA);
    }

    @Test
    void findRecentInspectionsPage_시설물종류필터_접두매칭으로컴파운드값도포함() {
        // facility.type은 레거시 단순값("건물")과 #731 등록 모달의 컴파운드값("건물-긴급-1개월")이
        // 공존할 수 있다 — "건물" 카테고리로 필터링하면 둘 다 포함되고 "교량"류는 제외돼야 한다.
        Long ownerId = seedOwner("owner-a@haja.com");
        Long buildingSimple = seedFacilityWithType(ownerId, "레거시빌딩", "건물");
        Long buildingCompound = seedFacilityWithType(ownerId, "신규빌딩", "건물-긴급-1개월");
        Long bridge = seedFacilityWithType(ownerId, "한강대교", "교량-정기-4개월");
        inspectionRepository.save(newInspection(
                buildingSimple, ownerId, ownerId, 1, LocalDate.of(2026, 7, 1), InspectionStatus.CREATED));
        inspectionRepository.save(newInspection(
                buildingCompound, ownerId, ownerId, 1, LocalDate.of(2026, 7, 2), InspectionStatus.CREATED));
        inspectionRepository.save(
                newInspection(bridge, ownerId, ownerId, 1, LocalDate.of(2026, 7, 3), InspectionStatus.CREATED));

        Page<Inspection> result = inspectionRepository.findRecentInspectionsPage(
                companyId(ownerId), null, "건물", java.util.Set.of(), null, List.of(), PageRequest.of(0, 10));

        assertThat(result.getContent()).extracting(Inspection::getFacilityId)
                .containsExactlyInAnyOrder(buildingSimple, buildingCompound);
    }

    @Test
    void findRecentInspectionsPage_시설물종류필터_리터럴퍼센트문자는LIKE와일드카드로해석되지않음() {
        // code review P2 — facilityTypeCategory에 LIKE 와일드카드(%, _)를 리터럴로 넣어도 "전체 매칭"으로
        // 새지 않아야 한다. 실제 facility.type 값 중 어느 것도 리터럴 "%"로 시작하지 않으므로, 이스케이프가
        // 제대로 되면 결과가 0건이어야 한다(이스케이프 누락 시 모든 시설물이 매칭돼 회귀한다).
        Long ownerId = seedOwner("owner-a@haja.com");
        Long facilityId = seedFacilityWithType(ownerId, "테스트빌딩", "건물");
        inspectionRepository.save(
                newInspection(facilityId, ownerId, ownerId, 1, LocalDate.of(2026, 7, 1), InspectionStatus.CREATED));

        Page<Inspection> result = inspectionRepository.findRecentInspectionsPage(
                companyId(ownerId), null, "%", java.util.Set.of(), null, List.of(), PageRequest.of(0, 10));

        assertThat(result.getContent()).isEmpty();
    }

    @Test
    void findRecentInspectionsPage_텍스트검색_시설물명매칭() {
        Long ownerId = seedOwner("owner-a@haja.com");
        Long facilityA = seedFacility(ownerId, "강남빌딩");
        Long facilityB = seedFacility(ownerId, "서초타워");
        inspectionRepository.save(
                newInspection(facilityA, ownerId, ownerId, 1, LocalDate.of(2026, 7, 1), InspectionStatus.CREATED));
        inspectionRepository.save(
                newInspection(facilityB, ownerId, ownerId, 1, LocalDate.of(2026, 7, 1), InspectionStatus.CREATED));

        Page<Inspection> result = inspectionRepository.findRecentInspectionsPage(
                companyId(ownerId), null, null, java.util.Set.of(), "강남", List.of(), PageRequest.of(0, 10));

        assertThat(result.getContent()).extracting(Inspection::getFacilityId).containsExactly(facilityA);
    }

    @Test
    void findRecentInspectionsPage_텍스트검색_리터럴퍼센트문자는LIKE와일드카드로해석되지않음() {
        // query는 서비스단(DashboardService.escapeLikeWildcards)에서 이스케이프된 값이 넘어온다고 가정 —
        // 여기서는 레포지토리가 그 이스케이프된 값을 escape 절과 함께 올바르게 처리하는지만 검증한다.
        // 이스케이프된 "%"(=\\%)는 리터럴 문자 그대로 취급돼야 하므로, 이름에 "%"가 없는 시설물은
        // 매칭되지 않아야 한다(이스케이프 누락 시 모든 시설물이 매칭돼 회귀한다).
        Long ownerId = seedOwner("owner-a@haja.com");
        Long facilityId = seedFacility(ownerId, "강남빌딩");
        inspectionRepository.save(
                newInspection(facilityId, ownerId, ownerId, 1, LocalDate.of(2026, 7, 1), InspectionStatus.CREATED));

        Page<Inspection> result = inspectionRepository.findRecentInspectionsPage(
                companyId(ownerId), null, null, java.util.Set.of(), "\\%", List.of(), PageRequest.of(0, 10));

        assertThat(result.getContent()).isEmpty();
    }

    @Test
    void findRecentInspectionsPage_텍스트검색_담당자명매칭id로OR결합() {
        // 서비스가 UserRepository.findIdsByCompanyIdAndNameContaining 으로 미리 찾은 담당자(createdBy) id
        // 목록을 넘겨준다고 가정 — 이 레포지토리 메서드는 그 id 목록과 시설물명 LIKE 를 OR 로 결합만 한다.
        Long ownerId = seedOwner("owner-a@haja.com");
        Long companyId = companyId(ownerId);
        Long inspectorUserId = seedCompanyMember(companyId, "inspector-kim@haja.com", "김검사");
        Long facilityId = seedFacility(ownerId, "무관한이름빌딩");
        // createdBy=ownerId(점검 생성자), assignedInspectorId=inspectorUserId("김검사") — 검색 대상은
        // createdBy(대시보드 "담당자" 표시 필드, RecentInspectionResponse 관례)이므로 inspectorUserId를
        // createdBy에 둔다. assignedInspectorId 트리거를 만족시키려 ownerId를 담당자로 배정한다.
        Inspection byInspector = inspectionRepository.save(newInspection(
                facilityId, inspectorUserId, ownerId, 1, LocalDate.of(2026, 7, 1), InspectionStatus.CREATED));
        inspectionRepository.save(
                newInspection(facilityId, ownerId, ownerId, 2, LocalDate.of(2026, 7, 2), InspectionStatus.CREATED));

        Page<Inspection> result = inspectionRepository.findRecentInspectionsPage(
                companyId(ownerId), null, null, java.util.Set.of(), "김검사", List.of(inspectorUserId),
                PageRequest.of(0, 10));

        assertThat(result.getContent()).extracting(Inspection::getId).containsExactly(byInspector.getId());
    }

    @Test
    void findRecentInspectionsPage_매칭없으면_빈페이지_totalElements0() {
        Long ownerId = seedOwner("owner-a@haja.com");
        Long facilityId = seedFacility(ownerId, "테스트빌딩");
        inspectionRepository.save(
                newInspection(facilityId, ownerId, ownerId, 1, LocalDate.of(2026, 7, 1), InspectionStatus.CREATED));

        Page<Inspection> result = inspectionRepository.findRecentInspectionsPage(
                companyId(ownerId), null, null, java.util.Set.of(), "존재하지않는검색어", List.of(), PageRequest.of(0, 10));

        assertThat(result.getContent()).isEmpty();
        assertThat(result.getTotalElements()).isZero();
    }

    @Test
    void findRecentInspectionsPage_페이지네이션_size와offset적용() {
        Long ownerId = seedOwner("owner-a@haja.com");
        Long facilityId = seedFacility(ownerId, "테스트빌딩");
        for (int i = 1; i <= 5; i++) {
            inspectionRepository.save(newInspection(
                    facilityId, ownerId, ownerId, i, LocalDate.of(2026, 7, i), InspectionStatus.CREATED));
        }

        Page<Inspection> firstPage = inspectionRepository.findRecentInspectionsPage(
                companyId(ownerId), null, null, java.util.Set.of(), null, List.of(), PageRequest.of(0, 2));
        Page<Inspection> secondPage = inspectionRepository.findRecentInspectionsPage(
                companyId(ownerId), null, null, java.util.Set.of(), null, List.of(), PageRequest.of(1, 2));

        assertThat(firstPage.getContent()).extracting(Inspection::getRoundNo).containsExactly(5, 4);
        assertThat(secondPage.getContent()).extracting(Inspection::getRoundNo).containsExactly(3, 2);
        assertThat(firstPage.getTotalElements()).isEqualTo(5);
        assertThat(firstPage.getTotalPages()).isEqualTo(3);
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
    void findLatestByFacilityIds_동일날짜역순입력이어도_performedAt이늦은회차가최신선정() {
        // #1667 — 회차1(id 작음, 먼저 생성)이 실제로는 오후에 촬영/보고됐고, 회차2(id 큼, 나중에 생성)는
        // 오전에 촬영됐다("역순 입력": id 순서와 실제 수행 순서가 어긋난다). id desc tie-break였다면
        // id가 큰 회차2가 최신으로 잘못 선정됐을 상황 — performed_at desc가 우선하므로 회차1이 최신이다.
        Long ownerId = seedOwner("owner-a@haja.com");
        Long facilityId = seedFacility(ownerId, "테스트빌딩");
        Inspection performedLater = inspectionRepository.save(newInspectionWithPerformedAt(
                facilityId, ownerId, ownerId, 1, LocalDate.of(2026, 8, 18),
                java.time.LocalDateTime.of(2026, 8, 18, 15, 0)));
        inspectionRepository.save(newInspectionWithPerformedAt(
                facilityId, ownerId, ownerId, 2, LocalDate.of(2026, 8, 18),
                java.time.LocalDateTime.of(2026, 8, 18, 9, 0)));

        List<Inspection> result = inspectionRepository.findLatestByFacilityIds(List.of(facilityId));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getId()).isEqualTo(performedLater.getId());
    }

    @Test
    void findLatestByFacilityIds_동일날짜performedAt없는회차는_nulls_last로밀려_있는회차가우선() {
        // #1667 — id가 작은 회차1에 performed_at이 세팅돼 있고, id가 큰 회차2는 아직 미디어가 없어
        // performed_at이 null이다. 순수 id desc였다면 회차2가 최신으로 선정됐겠지만, nulls last
        // 규칙상 performed_at이 있는 회차1이 우선한다.
        Long ownerId = seedOwner("owner-a@haja.com");
        Long facilityId = seedFacility(ownerId, "테스트빌딩");
        Inspection withPerformedAt = inspectionRepository.save(newInspectionWithPerformedAt(
                facilityId, ownerId, ownerId, 1, LocalDate.of(2026, 8, 18),
                java.time.LocalDateTime.of(2026, 8, 18, 9, 0)));
        inspectionRepository.save(
                newInspection(facilityId, ownerId, ownerId, 2, LocalDate.of(2026, 8, 18), InspectionStatus.CREATED));

        List<Inspection> result = inspectionRepository.findLatestByFacilityIds(List.of(facilityId));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getId()).isEqualTo(withPerformedAt.getId());
    }

    @Test
    void findLatestByFacilityIds_점검이력없는시설물은결과에없음() {
        Long ownerId = seedOwner("owner-a@haja.com");
        Long facilityWithNoInspection = seedFacility(ownerId, "점검이력없음");

        List<Inspection> result =
                inspectionRepository.findLatestByFacilityIds(List.of(facilityWithNoInspection));

        assertThat(result).isEmpty();
    }

    // ── 점검 수행 시각 자동 세팅 원자적 UPDATE(V43, #1667, 코드 리뷰 P1-1) ──
    // 엔티티 read-modify-write(dirty checking) 대신 조건부 UPDATE로 교체한 이유는 lost update 방지다 —
    // 이 DB 레벨 WHERE 조건(performed_at is null or performed_at > :candidate)이 실제로 "더 이른 값만
    // 갱신"을 강제하는지를 여기서 직접 고정한다(MediaWriterTest는 호출 인자만 검증하는 순수 단위 테스트).

    @Test
    void applyPerformedAtIfEarlier_null이면_후보값그대로세팅되고1건갱신된다() {
        Long ownerId = seedOwner("owner-a@haja.com");
        Long facilityId = seedFacility(ownerId, "테스트빌딩");
        Inspection inspection = inspectionRepository.save(
                newInspection(facilityId, ownerId, ownerId, 1, LocalDate.of(2026, 8, 18), InspectionStatus.CREATED));
        java.time.LocalDateTime candidate = java.time.LocalDateTime.of(2026, 8, 18, 9, 0);

        int updated = inspectionRepository.applyPerformedAtIfEarlier(inspection.getId(), candidate);
        em.flush();
        em.clear();

        assertThat(updated).isEqualTo(1);
        assertThat(inspectionRepository.findById(inspection.getId()).orElseThrow().getPerformedAt())
                .isEqualTo(candidate);
    }

    @Test
    void applyPerformedAtIfEarlier_기존값보다늦은후보는_갱신되지않고0건반환된다() {
        Long ownerId = seedOwner("owner-a@haja.com");
        Long facilityId = seedFacility(ownerId, "테스트빌딩");
        java.time.LocalDateTime existing = java.time.LocalDateTime.of(2026, 8, 18, 9, 0);
        Inspection inspection = inspectionRepository.save(newInspectionWithPerformedAt(
                facilityId, ownerId, ownerId, 1, LocalDate.of(2026, 8, 18), existing));
        java.time.LocalDateTime laterCandidate = java.time.LocalDateTime.of(2026, 8, 18, 15, 0);

        int updated = inspectionRepository.applyPerformedAtIfEarlier(inspection.getId(), laterCandidate);
        em.flush();
        em.clear();

        assertThat(updated).isEqualTo(0);
        assertThat(inspectionRepository.findById(inspection.getId()).orElseThrow().getPerformedAt())
                .isEqualTo(existing);
    }

    @Test
    void applyPerformedAtIfEarlier_기존값보다이른후보는_갱신되고1건반환된다() {
        Long ownerId = seedOwner("owner-a@haja.com");
        Long facilityId = seedFacility(ownerId, "테스트빌딩");
        java.time.LocalDateTime existing = java.time.LocalDateTime.of(2026, 8, 18, 15, 0);
        Inspection inspection = inspectionRepository.save(newInspectionWithPerformedAt(
                facilityId, ownerId, ownerId, 1, LocalDate.of(2026, 8, 18), existing));
        java.time.LocalDateTime earlierCandidate = java.time.LocalDateTime.of(2026, 8, 18, 9, 0);

        int updated = inspectionRepository.applyPerformedAtIfEarlier(inspection.getId(), earlierCandidate);
        em.flush();
        em.clear();

        assertThat(updated).isEqualTo(1);
        assertThat(inspectionRepository.findById(inspection.getId()).orElseThrow().getPerformedAt())
                .isEqualTo(earlierCandidate);
    }

    @Test
    void applyPerformedAtIfEarlier_기존값과동일한후보는_갱신되지않고0건반환된다() {
        // 동일값 재대입은 실질 변경이 없으므로 no-op — WHERE가 `>` (>=가 아님)라 갱신되지 않는다.
        Long ownerId = seedOwner("owner-a@haja.com");
        Long facilityId = seedFacility(ownerId, "테스트빌딩");
        java.time.LocalDateTime existing = java.time.LocalDateTime.of(2026, 8, 18, 9, 0);
        Inspection inspection = inspectionRepository.save(newInspectionWithPerformedAt(
                facilityId, ownerId, ownerId, 1, LocalDate.of(2026, 8, 18), existing));

        int updated = inspectionRepository.applyPerformedAtIfEarlier(inspection.getId(), existing);

        assertThat(updated).isEqualTo(0);
    }

    // ── 마이페이지 "내 점검 이력"(#844) ──

    @Test
    void countMine_담당자또는등록자인회사스코프내점검만집계() {
        Long ownerId = seedOwner("owner-a@haja.com"); // createdBy 겸 assignedInspectorId
        Long stranger = seedOwner("owner-b@haja.com");
        Long facilityId = seedFacility(ownerId, "테스트빌딩");
        Long strangerFacility = seedFacility(stranger, "타인빌딩");
        // createdBy만 본인
        inspectionRepository.save(
                newInspection(facilityId, ownerId, ownerId, 1, LocalDate.of(2026, 7, 1), InspectionStatus.CREATED));
        // 회사 밖 점검(타인 회사) — 섞이면 안 된다
        inspectionRepository.save(newInspection(
                strangerFacility, stranger, stranger, 1, LocalDate.of(2026, 7, 1), InspectionStatus.CREATED));

        long count = inspectionRepository.countMine(companyId(ownerId), ownerId);

        assertThat(count).isEqualTo(1);
    }

    @Test
    void countMineByStatusIn_상태집합에속한건만집계() {
        Long ownerId = seedOwner("owner-a@haja.com");
        Long facilityId = seedFacility(ownerId, "테스트빌딩");
        inspectionRepository.save(
                newInspection(facilityId, ownerId, ownerId, 1, LocalDate.of(2026, 7, 1), InspectionStatus.REVIEWED));
        inspectionRepository.save(
                newInspection(facilityId, ownerId, ownerId, 2, LocalDate.of(2026, 7, 2), InspectionStatus.REPORTED));
        inspectionRepository.save(
                newInspection(facilityId, ownerId, ownerId, 3, LocalDate.of(2026, 7, 3), InspectionStatus.CREATED));

        long reviewConfirmed = inspectionRepository.countMineByStatusIn(
                companyId(ownerId), ownerId,
                java.util.EnumSet.of(InspectionStatus.REVIEWED, InspectionStatus.REPORTED));

        assertThat(reviewConfirmed).isEqualTo(2);
    }

    @Test
    void findMyInspectionsPage_담당자거나등록자인점검만_회사스코프내에서반환() {
        Long ownerId = seedOwner("owner-a@haja.com");
        Long colleague = seedApprovedMember(companyId(ownerId), "colleague-a@haja.com"); // 같은 회사 동료
        Long stranger = seedOwner("owner-c@haja.com"); // 완전히 다른 회사
        Long facilityId = seedFacility(ownerId, "테스트빌딩");
        Long strangerFacility = seedFacility(stranger, "타인빌딩");
        // 본인이 담당자(assignedInspectorId), 등록자는 동료
        Inspection asInspector = inspectionRepository.save(newInspection(
                facilityId, colleague, ownerId, 1, LocalDate.of(2026, 7, 1), InspectionStatus.CREATED));
        // 본인이 등록자(createdBy)만, 담당자는 동료
        Inspection asCreator = inspectionRepository.save(newInspection(
                facilityId, ownerId, colleague, 2, LocalDate.of(2026, 7, 2), InspectionStatus.CREATED));
        // 본인과 무관한 같은 회사 점검 — 제외돼야 함
        inspectionRepository.save(newInspection(
                facilityId, colleague, colleague, 3, LocalDate.of(2026, 7, 3), InspectionStatus.CREATED));
        // 타 회사 점검(회사 밖) — 본인이 담당자여도 회사 스코프 밖이라 제외돼야 함
        // (HAJA-25 트리거상 assigned_inspector와 created_by는 같은 회사여야 하므로 stranger 본인을 등록자로 둔다)
        inspectionRepository.save(newInspection(
                strangerFacility, stranger, stranger, 1, LocalDate.of(2026, 7, 1), InspectionStatus.CREATED));

        Page<Inspection> result = inspectionRepository.findMyInspectionsPage(
                ownerId, companyId(ownerId), null, PageRequest.of(0, 10));

        assertThat(result.getTotalElements()).isEqualTo(2);
        assertThat(result.getContent()).extracting(Inspection::getId)
                .containsExactlyInAnyOrder(asInspector.getId(), asCreator.getId());
    }

    @Test
    void findMyInspectionsPage_동일일자_performedAt이id보다우선하는tie_break() {
        // #1667 P3 — recentInspectionOrderBy 공용화로 findPageByCompanyIdAndFilters/
        // findRecentInspectionsPage와 이 메서드가 동일 정렬 계약을 유지하는지 고정한다.
        Long ownerId = seedOwner("owner-a@haja.com");
        Long facilityId = seedFacility(ownerId, "테스트빌딩");
        Inspection earlierPerformed = inspectionRepository.save(newInspectionWithPerformedAt(
                facilityId, ownerId, ownerId, 1, LocalDate.of(2026, 7, 20),
                java.time.LocalDateTime.of(2026, 7, 20, 9, 0)));
        Inspection laterPerformed = inspectionRepository.save(newInspectionWithPerformedAt(
                facilityId, ownerId, ownerId, 2, LocalDate.of(2026, 7, 20),
                java.time.LocalDateTime.of(2026, 7, 20, 15, 0)));
        Inspection noPerformedAt = inspectionRepository.save(
                newInspection(facilityId, ownerId, ownerId, 3, LocalDate.of(2026, 7, 20), InspectionStatus.CREATED));

        Page<Inspection> result = inspectionRepository.findMyInspectionsPage(
                ownerId, companyId(ownerId), null, PageRequest.of(0, 10));

        assertThat(result.getContent()).extracting(Inspection::getId)
                .containsExactly(laterPerformed.getId(), earlierPerformed.getId(), noPerformedAt.getId());
    }

    @Test
    void findMyInspectionsPage_기간필터_periodFrom이후만포함() {
        Long ownerId = seedOwner("owner-a@haja.com");
        Long facilityId = seedFacility(ownerId, "테스트빌딩");
        Inspection older = inspectionRepository.save(
                newInspection(facilityId, ownerId, ownerId, 1, LocalDate.of(2026, 1, 1), InspectionStatus.CREATED));
        Inspection newer = inspectionRepository.save(
                newInspection(facilityId, ownerId, ownerId, 2, LocalDate.of(2026, 7, 10), InspectionStatus.CREATED));

        Page<Inspection> result = inspectionRepository.findMyInspectionsPage(
                ownerId, companyId(ownerId), LocalDate.of(2026, 6, 1), PageRequest.of(0, 10));

        assertThat(result.getContent()).extracting(Inspection::getId).containsExactly(newer.getId());
        assertThat(result.getTotalElements()).isEqualTo(1);
        assertThat(older).isNotNull(); // 사용 안 하면 실수로 지워질 수 있어 참조를 명시
    }

    @Test
    void findMyInspectionsPage_정렬은점검일최신순_동일일자면id내림차순() {
        Long ownerId = seedOwner("owner-a@haja.com");
        Long facilityId = seedFacility(ownerId, "테스트빌딩");
        Inspection first = inspectionRepository.save(
                newInspection(facilityId, ownerId, ownerId, 1, LocalDate.of(2026, 7, 10), InspectionStatus.CREATED));
        Inspection second = inspectionRepository.save(
                newInspection(facilityId, ownerId, ownerId, 2, LocalDate.of(2026, 7, 10), InspectionStatus.CREATED));

        Page<Inspection> result = inspectionRepository.findMyInspectionsPage(
                ownerId, companyId(ownerId), null, PageRequest.of(0, 10));

        assertThat(result.getContent()).extracting(Inspection::getId)
                .containsExactly(second.getId(), first.getId());
    }
}
