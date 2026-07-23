package com.hajacheck.core.defect.repository;

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
import com.hajacheck.core.facility.entity.Facility;
import com.hajacheck.core.inspection.entity.Inspection;
import com.hajacheck.core.inspection.entity.InspectionStatus;
import com.hajacheck.support.PostgresTestSupport;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
import java.util.Optional;

// 실 PG DDL(defects) 대조를 위해 Testcontainers PostgreSQL 사용.
// users → facilities → inspections → defects 순으로 FK 를 충족하며 시드한다(HAJA-17 대시보드 집계).
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
@ActiveProfiles("test")
class DefectRepositoryTest extends PostgresTestSupport {

    @Autowired
    private DefectRepository defectRepository;

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

    private Long seedInspection(Long facilityId, Long createdBy, int roundNo) {
        Inspection inspection = Inspection.builder()
                .facilityId(facilityId)
                .createdBy(createdBy)
                .assignedInspectorId(createdBy)
                .roundNo(roundNo)
                .inspectionDate(LocalDate.of(2026, 7, 1))
                .status(InspectionStatus.REVIEWED)
                .build();
        em.persist(inspection);
        em.flush();
        return inspection.getId();
    }

    private Defect newDefect(Long inspectionId, DefectGrade grade, DefectStatus status, boolean deleted) {
        return Defect.builder()
                .inspectionId(inspectionId)
                .type(DefectType.CRACK)
                .confidence(0.9)
                .grade(grade)
                .status(status)
                .reviewed(false)
                .deleted(deleted)
                .build();
    }

    @Test
    void save_저장후_createdAt과id채워짐() {
        Long ownerId = seedOwner("owner-a@haja.com");
        Long facilityId = seedFacility(ownerId, "테스트빌딩");
        Long inspectionId = seedInspection(facilityId, ownerId, 1);

        Defect saved = defectRepository.save(
                newDefect(inspectionId, DefectGrade.C, DefectStatus.DETECTED, false));

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getType()).isEqualTo(DefectType.CRACK);
    }

    @Test
    void countGroupByGrade_삭제제외하고등급별집계() {
        Long ownerId = seedOwner("owner-a@haja.com");
        Long facilityId = seedFacility(ownerId, "테스트빌딩");
        Long inspectionId = seedInspection(facilityId, ownerId, 1);
        defectRepository.save(newDefect(inspectionId, DefectGrade.E, DefectStatus.ACTION_PENDING, false));
        defectRepository.save(newDefect(inspectionId, DefectGrade.E, DefectStatus.RESOLVED, false));
        defectRepository.save(newDefect(inspectionId, DefectGrade.A, DefectStatus.DETECTED, false));
        // 삭제된 결함은 집계에서 제외되어야 한다.
        defectRepository.save(newDefect(inspectionId, DefectGrade.E, DefectStatus.RESOLVED, true));

        List<GradeCountProjection> result = defectRepository.countGroupByGrade(List.of(inspectionId));

        assertThat(result)
                .filteredOn(p -> p.getGrade() == DefectGrade.E)
                .extracting(GradeCountProjection::getCnt)
                .containsExactly(2L);
        assertThat(result)
                .filteredOn(p -> p.getGrade() == DefectGrade.A)
                .extracting(GradeCountProjection::getCnt)
                .containsExactly(1L);
    }

    @Test
    void findPendingPriorityDefects_미분류는최하단_등급E부터A까지내림차순() {
        // #327 회귀 방지 — PostgreSQL은 "ORDER BY ... DESC" 시 기본이 NULLS FIRST라, 파생 쿼리
        // 시절엔 미분류(grade=null) 하자가 등급 E보다 위(최상단)로 노출됐다. nulls last 적용 후
        // 정상 순서(E→D→C→B→A→미분류)를 검증한다.
        Long ownerId = seedOwner("owner-a@haja.com");
        Long facilityId = seedFacility(ownerId, "테스트빌딩");
        Long inspectionId = seedInspection(facilityId, ownerId, 1);
        defectRepository.save(newDefect(inspectionId, DefectGrade.C, DefectStatus.ACTION_PENDING, false));
        defectRepository.save(newDefect(inspectionId, null, DefectStatus.ACTION_PENDING, false)); // 미분류
        defectRepository.save(newDefect(inspectionId, DefectGrade.E, DefectStatus.ACTION_PENDING, false));
        defectRepository.save(newDefect(inspectionId, DefectGrade.D, DefectStatus.ACTION_PENDING, false));
        defectRepository.save(newDefect(inspectionId, DefectGrade.B, DefectStatus.ACTION_PENDING, false));
        defectRepository.save(newDefect(inspectionId, DefectGrade.A, DefectStatus.ACTION_PENDING, false));
        // 다른 상태(RESOLVED)와 삭제된 결함은 우선순위 목록에서 제외되어야 한다.
        defectRepository.save(newDefect(inspectionId, DefectGrade.E, DefectStatus.RESOLVED, false));
        defectRepository.save(newDefect(inspectionId, DefectGrade.E, DefectStatus.ACTION_PENDING, true));

        List<Defect> result = defectRepository.findPendingPriorityDefects(
                List.of(inspectionId), DefectStatus.ACTION_PENDING, PageRequest.of(0, 10));

        assertThat(result).extracting(Defect::getGrade)
                .containsExactly(
                        DefectGrade.E, DefectGrade.D, DefectGrade.C, DefectGrade.B, DefectGrade.A, null);
    }

    @Test
    void findPendingPriorityDefects_동일등급내createdAt최신순() {
        Long ownerId = seedOwner("owner-a@haja.com");
        Long facilityId = seedFacility(ownerId, "테스트빌딩");
        Long inspectionId = seedInspection(facilityId, ownerId, 1);

        Defect older = defectRepository.save(
                newDefect(inspectionId, DefectGrade.E, DefectStatus.ACTION_PENDING, false));
        Defect newer = defectRepository.save(
                newDefect(inspectionId, DefectGrade.E, DefectStatus.ACTION_PENDING, false));
        em.flush();

        // @CreatedDate 는 persist 시점에 auditing 이 "now" 로 덮어써 저장 순서만으로는 createdAt
        // 역전 여부를 신뢰성 있게 검증하기 어렵다 — 저장 후 네이티브 UPDATE 로 명시적으로 지정한다.
        LocalDateTime base = LocalDateTime.of(2026, 7, 1, 0, 0);
        updateCreatedAt(older.getId(), base);
        updateCreatedAt(newer.getId(), base.plusMinutes(10));
        em.clear();

        List<Defect> result = defectRepository.findPendingPriorityDefects(
                List.of(inspectionId), DefectStatus.ACTION_PENDING, PageRequest.of(0, 10));

        assertThat(result).extracting(Defect::getId).containsExactly(newer.getId(), older.getId());
    }

    @Test
    void findPendingPriorityDefects_Top10건제한유지() {
        Long ownerId = seedOwner("owner-a@haja.com");
        Long facilityId = seedFacility(ownerId, "테스트빌딩");
        Long inspectionId = seedInspection(facilityId, ownerId, 1);
        for (int i = 0; i < 12; i++) {
            defectRepository.save(newDefect(inspectionId, DefectGrade.E, DefectStatus.ACTION_PENDING, false));
        }

        List<Defect> result = defectRepository.findPendingPriorityDefects(
                List.of(inspectionId), DefectStatus.ACTION_PENDING, PageRequest.of(0, 10));

        // @Query + Pageable 전환 후에도 findTop10 파생 쿼리와 동등한 상위 10건 제한이 유지돼야 한다.
        assertThat(result).hasSize(10);
    }

    private void updateCreatedAt(Long defectId, LocalDateTime createdAt) {
        em.getEntityManager()
                .createNativeQuery("update defects set created_at = ?1 where id = ?2")
                .setParameter(1, createdAt)
                .setParameter(2, defectId)
                .executeUpdate();
    }

    @Test
    void countByInspectionIdInAndStatusAndDeletedFalseAndCreatedAtRange_기간내만집계() {
        Long ownerId = seedOwner("owner-a@haja.com");
        Long facilityId = seedFacility(ownerId, "테스트빌딩");
        Long inspectionId = seedInspection(facilityId, ownerId, 1);
        defectRepository.save(newDefect(inspectionId, DefectGrade.D, DefectStatus.ACTION_PENDING, false));

        LocalDateTime from = LocalDateTime.now().minusDays(1);
        LocalDateTime to = LocalDateTime.now().plusDays(1);
        long inRange = defectRepository.countByInspectionIdInAndStatusAndDeletedFalseAndCreatedAtRange(
                List.of(inspectionId), DefectStatus.ACTION_PENDING, from, to);
        long outOfRange = defectRepository.countByInspectionIdInAndStatusAndDeletedFalseAndCreatedAtRange(
                List.of(inspectionId), DefectStatus.ACTION_PENDING, from.minusDays(10), from.minusDays(5));

        assertThat(inRange).isEqualTo(1);
        assertThat(outOfRange).isEqualTo(0);
    }

    @Test
    void countByInspectionIdInAndDeletedFalseAndCreatedAtRange_주경계자정하자는이번주지난주중한쪽에만집계() {
        // 리뷰 P1 회귀 방지 — 과거 "-1ns" 트릭(BETWEEN 양끝 포함을 반열림처럼 흉내)은 PG timestamp
        // (defects.created_at 은 timestamp with time zone)가 마이크로초 정밀도라 .999999999 가
        // 다음 자정으로 반올림되어 사실상 양끝 포함이 되고, 주 경계 자정(00:00:00.000000)인 하자가
        // 이번주·지난주 양쪽에 중복 집계됐다. 실 PG(Testcontainers)로만 검증 가능한 결함이라
        // Mockito 단위테스트가 아닌 이 통합테스트로 반열림 [from,to) 이 실제로 지켜지는지 확인한다.
        Long ownerId = seedOwner("owner-a@haja.com");
        Long facilityId = seedFacility(ownerId, "테스트빌딩");
        Long inspectionId = seedInspection(facilityId, ownerId, 1);

        Defect boundaryDefect = defectRepository.save(
                newDefect(inspectionId, DefectGrade.C, DefectStatus.DETECTED, false));
        em.flush();

        // @CreatedDate 는 persist 시점에 auditing 이 "now" 로 덮어써 builder/reflection 으로 미리
        // 지정할 수 없다 — 저장 후 네이티브 UPDATE 로 주 경계 자정 값을 강제한다.
        LocalDate weekStart = LocalDate.of(2026, 1, 5).with(DayOfWeek.MONDAY);
        LocalDateTime weekBoundary = weekStart.atStartOfDay();
        em.getEntityManager()
                .createNativeQuery("update defects set created_at = ?1 where id = ?2")
                .setParameter(1, weekBoundary)
                .setParameter(2, boundaryDefect.getId())
                .executeUpdate();
        em.flush();
        em.clear();

        long thisWeekCount = defectRepository.countByInspectionIdInAndDeletedFalseAndCreatedAtRange(
                List.of(inspectionId), weekBoundary, weekBoundary.plusWeeks(1));
        long lastWeekCount = defectRepository.countByInspectionIdInAndDeletedFalseAndCreatedAtRange(
                List.of(inspectionId), weekBoundary.minusWeeks(1), weekBoundary);

        assertThat(thisWeekCount).isEqualTo(1); // 경계 자정은 from(inclusive) 쪽인 "이번주"에만 집계
        assertThat(lastWeekCount).isEqualTo(0); // "지난주" 쪽 to 는 exclusive 라 겹치지 않음
        assertThat(thisWeekCount + lastWeekCount).isEqualTo(1); // 핵심 검증 — 이중집계 없음
    }

    @Test
    void countGroupByInspectionId_점검별결함건수집계() {
        Long ownerId = seedOwner("owner-a@haja.com");
        Long facilityId = seedFacility(ownerId, "테스트빌딩");
        Long inspectionA = seedInspection(facilityId, ownerId, 1);
        Long inspectionB = seedInspection(facilityId, ownerId, 2);
        defectRepository.save(newDefect(inspectionA, DefectGrade.B, DefectStatus.DETECTED, false));
        defectRepository.save(newDefect(inspectionA, DefectGrade.B, DefectStatus.DETECTED, false));
        defectRepository.save(newDefect(inspectionB, DefectGrade.A, DefectStatus.DETECTED, false));

        List<InspectionDefectCountProjection> result =
                defectRepository.countGroupByInspectionId(List.of(inspectionA, inspectionB));

        assertThat(result)
                .filteredOn(p -> p.getInspectionId().equals(inspectionA))
                .extracting(InspectionDefectCountProjection::getCnt)
                .containsExactly(2L);
    }

    // ── HAJA-30: 하자 목록·상세 조회 ──

    @Test
    void findPageByCompanyIdAndFilters_owner스코프_본인시설하자만조회() {
        Long ownerId = seedOwner("owner-a@haja.com");
        Long strangerId = seedOwner("owner-b@haja.com");
        Long facilityId = seedFacility(ownerId, "테스트빌딩");
        Long strangerFacilityId = seedFacility(strangerId, "타인빌딩");
        Long inspectionId = seedInspection(facilityId, ownerId, 1);
        Long strangerInspectionId = seedInspection(strangerFacilityId, strangerId, 1);
        defectRepository.save(newDefect(inspectionId, DefectGrade.C, DefectStatus.DETECTED, false));
        defectRepository.save(newDefect(strangerInspectionId, DefectGrade.C, DefectStatus.DETECTED, false));

        Page<Defect> result = defectRepository.findPageByCompanyIdAndFilters(
                companyId(ownerId), null, null, null, PageRequest.of(0, 10));

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getTotalElements()).isEqualTo(1);
    }

    @Test
    void findPageByCompanyIdAndFilters_삭제된하자는제외() {
        Long ownerId = seedOwner("owner-a@haja.com");
        Long facilityId = seedFacility(ownerId, "테스트빌딩");
        Long inspectionId = seedInspection(facilityId, ownerId, 1);
        defectRepository.save(newDefect(inspectionId, DefectGrade.C, DefectStatus.DETECTED, false));
        defectRepository.save(newDefect(inspectionId, DefectGrade.C, DefectStatus.DETECTED, true));

        Page<Defect> result = defectRepository.findPageByCompanyIdAndFilters(
                companyId(ownerId), null, null, null, PageRequest.of(0, 10));

        assertThat(result.getContent()).hasSize(1);
    }

    @Test
    void findPageByCompanyIdAndFilters_유형등급상태필터적용() {
        Long ownerId = seedOwner("owner-a@haja.com");
        Long facilityId = seedFacility(ownerId, "테스트빌딩");
        Long inspectionId = seedInspection(facilityId, ownerId, 1);
        defectRepository.save(newDefect(inspectionId, DefectGrade.C, DefectStatus.DETECTED, false));
        defectRepository.save(newDefect(inspectionId, DefectGrade.E, DefectStatus.ACTION_PENDING, false));

        // 등급 필터는 "이상"(임계값) 의미다 — grade=E(최고 등급)를 주면 E 자기 자신만 매치되므로
        // 임계값과 정확 일치가 동일한 결과라 이 케이스만으로는 회귀를 못 잡는다. 임계값 전용
        // 회귀 방지는 아래 findPageByCompanyIdAndFilters_등급필터는이상_임계값의미 에서 별도 검증한다.
        Page<Defect> gradeFiltered = defectRepository.findPageByCompanyIdAndFilters(
                companyId(ownerId), null, DefectGrade.E, null, PageRequest.of(0, 10));
        Page<Defect> statusFiltered = defectRepository.findPageByCompanyIdAndFilters(
                companyId(ownerId), null, null, DefectStatus.DETECTED, PageRequest.of(0, 10));
        Page<Defect> typeFiltered = defectRepository.findPageByCompanyIdAndFilters(
                companyId(ownerId), DefectType.CRACK, null, null, PageRequest.of(0, 10));

        assertThat(gradeFiltered.getContent()).extracting(Defect::getGrade).containsExactly(DefectGrade.E);
        assertThat(statusFiltered.getContent()).extracting(Defect::getStatus)
                .containsExactly(DefectStatus.DETECTED);
        assertThat(typeFiltered.getContent()).hasSize(2); // 둘 다 CRACK(newDefect 고정값)이라 전부 매치
    }

    @Test
    void findPageByCompanyIdAndFilters_등급필터는이상_임계값의미() {
        // PR #372 code-reviewer P2 — "등급: X 이상" UI 라벨과 달리 백엔드가 grade == X 정확 일치만
        // 반환해 X보다 심각한 등급이 결과에서 누락되던 결함의 회귀 방지 테스트. PG named enum
        // (defect_grade_type)의 실제 DB측 >= 비교(A<B<C<D<E 선언순)를 Testcontainers로 검증한다.
        Long ownerId = seedOwner("owner-a@haja.com");
        Long facilityId = seedFacility(ownerId, "테스트빌딩");
        Long inspectionId = seedInspection(facilityId, ownerId, 1);
        defectRepository.save(newDefect(inspectionId, DefectGrade.A, DefectStatus.DETECTED, false));
        defectRepository.save(newDefect(inspectionId, DefectGrade.B, DefectStatus.DETECTED, false));
        defectRepository.save(newDefect(inspectionId, DefectGrade.C, DefectStatus.DETECTED, false));
        defectRepository.save(newDefect(inspectionId, DefectGrade.D, DefectStatus.DETECTED, false));
        defectRepository.save(newDefect(inspectionId, DefectGrade.E, DefectStatus.DETECTED, false));

        // 중간값(C) 필터 — C·D·E는 포함, A·B는 제외.
        Page<Defect> cOrAbove = defectRepository.findPageByCompanyIdAndFilters(
                companyId(ownerId), null, DefectGrade.C, null, PageRequest.of(0, 10));
        // 최저 등급(A) 필터 — 전부 포함(임계값이 사실상 무필터와 동일).
        Page<Defect> aOrAbove = defectRepository.findPageByCompanyIdAndFilters(
                companyId(ownerId), null, DefectGrade.A, null, PageRequest.of(0, 10));
        // 최고 등급(E) 필터 — E만 포함(정확 일치와 결과가 같아지는 경계).
        Page<Defect> eOrAbove = defectRepository.findPageByCompanyIdAndFilters(
                companyId(ownerId), null, DefectGrade.E, null, PageRequest.of(0, 10));

        assertThat(cOrAbove.getContent()).extracting(Defect::getGrade)
                .containsExactlyInAnyOrder(DefectGrade.C, DefectGrade.D, DefectGrade.E);
        assertThat(aOrAbove.getContent()).extracting(Defect::getGrade)
                .containsExactlyInAnyOrder(DefectGrade.A, DefectGrade.B, DefectGrade.C, DefectGrade.D, DefectGrade.E);
        assertThat(eOrAbove.getContent()).extracting(Defect::getGrade).containsExactly(DefectGrade.E);
    }

    @Test
    void findPageByCompanyIdAndFilters_최신순정렬() {
        Long ownerId = seedOwner("owner-a@haja.com");
        Long facilityId = seedFacility(ownerId, "테스트빌딩");
        Long inspectionId = seedInspection(facilityId, ownerId, 1);
        Defect older = defectRepository.save(
                newDefect(inspectionId, DefectGrade.C, DefectStatus.DETECTED, false));
        Defect newer = defectRepository.save(
                newDefect(inspectionId, DefectGrade.C, DefectStatus.DETECTED, false));
        em.flush();
        LocalDateTime base = LocalDateTime.of(2026, 7, 1, 0, 0);
        updateCreatedAt(older.getId(), base);
        updateCreatedAt(newer.getId(), base.plusMinutes(10));
        em.clear();

        Page<Defect> result = defectRepository.findPageByCompanyIdAndFilters(
                companyId(ownerId), null, null, null, PageRequest.of(0, 10));

        assertThat(result.getContent()).extracting(Defect::getId)
                .containsExactly(newer.getId(), older.getId());
    }

    @Test
    void findByIdAndCompanyId_본인소유하자_조회됨() {
        Long ownerId = seedOwner("owner-a@haja.com");
        Long facilityId = seedFacility(ownerId, "테스트빌딩");
        Long inspectionId = seedInspection(facilityId, ownerId, 1);
        Defect saved = defectRepository.save(
                newDefect(inspectionId, DefectGrade.C, DefectStatus.DETECTED, false));
        // 저장 직후 같은 영속성 컨텍스트에서 곧바로 조회하면, join fetch로 가져온 연관관계를 Hibernate가
        // 이미 관리 중인(managed) 엔티티에 재적용하지 않아 inspection이 null로 남는다 — flush+clear로
        // 컨텍스트를 비워 이후 조회가 DB에서 fresh하게 join fetch되도록 한다.
        em.flush();
        em.clear();

        Optional<Defect> result = defectRepository.findByIdAndCompanyId(saved.getId(), companyId(ownerId));

        assertThat(result).isPresent();
        assertThat(result.get().getInspection().getFacility().getId()).isEqualTo(facilityId);
    }

    @Test
    void findByIdAndCompanyId_타인소유하자_빈값() {
        Long ownerId = seedOwner("owner-a@haja.com");
        Long strangerId = seedOwner("owner-b@haja.com");
        Long facilityId = seedFacility(ownerId, "테스트빌딩");
        Long inspectionId = seedInspection(facilityId, ownerId, 1);
        Defect saved = defectRepository.save(
                newDefect(inspectionId, DefectGrade.C, DefectStatus.DETECTED, false));

        Optional<Defect> result = defectRepository.findByIdAndCompanyId(saved.getId(), companyId(strangerId));

        assertThat(result).isEmpty();
    }

    @Test
    void findByIdAndCompanyId_삭제된하자_빈값() {
        Long ownerId = seedOwner("owner-a@haja.com");
        Long facilityId = seedFacility(ownerId, "테스트빌딩");
        Long inspectionId = seedInspection(facilityId, ownerId, 1);
        Defect saved = defectRepository.save(
                newDefect(inspectionId, DefectGrade.C, DefectStatus.DETECTED, true));

        Optional<Defect> result = defectRepository.findByIdAndCompanyId(saved.getId(), companyId(ownerId));

        assertThat(result).isEmpty();
    }
}
