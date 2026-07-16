package com.hajacheck.core.defect.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.hajacheck.auth.entity.User;
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
import org.springframework.test.context.ActiveProfiles;

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

    private Long seedOwner(String email) {
        User owner = User.createCompanyOwner(email, "소유자", "$2a$10$testtesttesttesttesttes");
        em.persist(owner);
        em.flush();
        return owner.getId();
    }

    private Long seedFacility(Long ownerId, String name) {
        Facility facility = Facility.builder().ownerId(ownerId).name(name).type("BUILDING").build();
        em.persist(facility);
        em.flush();
        return facility.getId();
    }

    private Long seedInspection(Long facilityId, Long createdBy, int roundNo) {
        Inspection inspection = Inspection.builder()
                .facilityId(facilityId)
                .createdBy(createdBy)
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
    void findTop10ByInspectionIdInAndStatusAndDeletedFalseOrderByGradeDescCreatedAtDesc_등급내림차순() {
        Long ownerId = seedOwner("owner-a@haja.com");
        Long facilityId = seedFacility(ownerId, "테스트빌딩");
        Long inspectionId = seedInspection(facilityId, ownerId, 1);
        defectRepository.save(newDefect(inspectionId, DefectGrade.C, DefectStatus.ACTION_PENDING, false));
        defectRepository.save(newDefect(inspectionId, DefectGrade.E, DefectStatus.ACTION_PENDING, false));
        defectRepository.save(newDefect(inspectionId, DefectGrade.D, DefectStatus.ACTION_PENDING, false));
        // 다른 상태(RESOLVED)와 삭제된 결함은 우선순위 목록에서 제외되어야 한다.
        defectRepository.save(newDefect(inspectionId, DefectGrade.E, DefectStatus.RESOLVED, false));
        defectRepository.save(newDefect(inspectionId, DefectGrade.E, DefectStatus.ACTION_PENDING, true));

        List<Defect> result = defectRepository
                .findTop10ByInspectionIdInAndStatusAndDeletedFalseOrderByGradeDescCreatedAtDesc(
                        List.of(inspectionId), DefectStatus.ACTION_PENDING);

        assertThat(result).extracting(Defect::getGrade)
                .containsExactly(DefectGrade.E, DefectGrade.D, DefectGrade.C);
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
}
