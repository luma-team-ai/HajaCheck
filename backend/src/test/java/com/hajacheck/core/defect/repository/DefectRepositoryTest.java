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
