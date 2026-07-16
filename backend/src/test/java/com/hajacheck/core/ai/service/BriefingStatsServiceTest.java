package com.hajacheck.core.ai.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hajacheck.core.ai.dto.BriefingStatsRequest;
import com.hajacheck.core.defect.entity.DefectGrade;
import com.hajacheck.core.defect.entity.DefectType;
import com.hajacheck.core.defect.repository.DefectRepository;
import com.hajacheck.core.defect.repository.DefectTypeCountProjection;
import com.hajacheck.core.defect.repository.GradeCountProjection;
import com.hajacheck.core.facility.entity.Facility;
import com.hajacheck.core.facility.repository.FacilityRepository;
import com.hajacheck.core.inspection.entity.Inspection;
import com.hajacheck.core.inspection.entity.InspectionStatus;
import com.hajacheck.core.inspection.repository.InspectionRepository;
import java.lang.reflect.Field;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * BriefingStatsService 단위테스트(#248 / HAJA-197) — DashboardServiceTest 와 동일하게
 * Mockito 로 repository 를 목킹해 소유 범위 집계·주간 경계·grade→critical 파생·top type 선정을 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class BriefingStatsServiceTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Mock
    private FacilityRepository facilityRepository;

    @Mock
    private InspectionRepository inspectionRepository;

    @Mock
    private DefectRepository defectRepository;

    @InjectMocks
    private BriefingStatsService briefingStatsService;

    private static final Long OWNER_ID = 1L;
    private static final Long FACILITY_ID = 10L;
    private static final Long INSPECTION_ID = 100L;

    private Facility facility(Long id, Long ownerId, String name) {
        Facility facility = Facility.builder().ownerId(ownerId).name(name).type("BUILDING").build();
        setId(facility, id);
        return facility;
    }

    private Inspection inspection(Long id, Long facilityId, InspectionStatus status) {
        Inspection inspection = Inspection.builder()
                .facilityId(facilityId).createdBy(OWNER_ID).roundNo(1)
                .inspectionDate(LocalDate.now(KST)).status(status).build();
        setId(inspection, id);
        return inspection;
    }

    private void setId(Object target, Long value) {
        try {
            Field field = target.getClass().getDeclaredField("id");
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private GradeCountProjection gradeCount(DefectGrade grade, long cnt) {
        return new GradeCountProjection() {
            @Override
            public DefectGrade getGrade() {
                return grade;
            }

            @Override
            public long getCnt() {
                return cnt;
            }
        };
    }

    private DefectTypeCountProjection typeCount(DefectType type, long cnt) {
        return new DefectTypeCountProjection() {
            @Override
            public DefectType getType() {
                return type;
            }

            @Override
            public long getCnt() {
                return cnt;
            }
        };
    }

    @Test
    void buildStats_소유시설물없으면_전부0이고빈문자열topType() {
        when(facilityRepository.findByOwnerId(OWNER_ID)).thenReturn(List.of());
        when(facilityRepository.countByOwnerId(OWNER_ID)).thenReturn(0L);

        BriefingStatsRequest stats = briefingStatsService.buildStats(OWNER_ID);

        assertThat(stats.totalFacilities()).isZero();
        assertThat(stats.monthlyAnalysis()).isZero();
        assertThat(stats.pendingReview()).isZero();
        assertThat(stats.pendingAction()).isZero();
        assertThat(stats.thisWeekDefects()).isZero();
        assertThat(stats.lastWeekDefects()).isZero();
        assertThat(stats.criticalDefects()).isZero();
        assertThat(stats.topDefectType()).isEmpty();
        assertThat(stats.gradeDistribution()).containsEntry("A", 0L).containsEntry("E", 0L);
        verify(inspectionRepository, never()).countByFacilityIdInAndStatusIn(anyCollection(), anyCollection());
        verify(defectRepository, never())
                .countByInspectionIdInAndDeletedFalseAndCreatedAtBetween(anyCollection(), any(), any());
    }

    @Test
    void buildStats_소유범위기준으로집계_월간분석및조치대기() {
        when(facilityRepository.findByOwnerId(OWNER_ID))
                .thenReturn(List.of(facility(FACILITY_ID, OWNER_ID, "내시설")));
        when(facilityRepository.countByOwnerId(OWNER_ID)).thenReturn(1L);
        when(inspectionRepository.findByFacilityIdIn(List.of(FACILITY_ID)))
                .thenReturn(List.of(inspection(INSPECTION_ID, FACILITY_ID, InspectionStatus.REVIEWED)));
        when(inspectionRepository.countByFacilityIdInAndStatusInAndInspectionDateRange(
                eq(List.of(FACILITY_ID)), anyCollection(), any(), any())).thenReturn(4L);
        when(inspectionRepository.countByFacilityIdInAndStatusIn(eq(List.of(FACILITY_ID)), anyCollection()))
                .thenReturn(2L);
        when(defectRepository.countByInspectionIdInAndStatusAndDeletedFalse(eq(List.of(INSPECTION_ID)), any()))
                .thenReturn(3L);
        when(defectRepository.countByInspectionIdInAndDeletedFalseAndCreatedAtBetween(
                eq(List.of(INSPECTION_ID)), any(), any())).thenReturn(0L);
        when(defectRepository.countGroupByGrade(List.of(INSPECTION_ID))).thenReturn(List.of());
        when(defectRepository.countGroupByTypeOrderByCntDesc(List.of(INSPECTION_ID))).thenReturn(List.of());

        BriefingStatsRequest stats = briefingStatsService.buildStats(OWNER_ID);

        assertThat(stats.totalFacilities()).isEqualTo(1L);
        assertThat(stats.monthlyAnalysis()).isEqualTo(4L);
        assertThat(stats.pendingReview()).isEqualTo(2L);
        assertThat(stats.pendingAction()).isEqualTo(3L);
    }

    @Test
    void buildStats_주간경계는KST월요일기준_이번주지난주각각조회() {
        when(facilityRepository.findByOwnerId(OWNER_ID))
                .thenReturn(List.of(facility(FACILITY_ID, OWNER_ID, "내시설")));
        when(facilityRepository.countByOwnerId(OWNER_ID)).thenReturn(1L);
        when(inspectionRepository.findByFacilityIdIn(List.of(FACILITY_ID)))
                .thenReturn(List.of(inspection(INSPECTION_ID, FACILITY_ID, InspectionStatus.REVIEWED)));
        when(inspectionRepository.countByFacilityIdInAndStatusInAndInspectionDateRange(
                eq(List.of(FACILITY_ID)), anyCollection(), any(), any())).thenReturn(0L);
        when(inspectionRepository.countByFacilityIdInAndStatusIn(eq(List.of(FACILITY_ID)), anyCollection()))
                .thenReturn(0L);
        when(defectRepository.countByInspectionIdInAndStatusAndDeletedFalse(eq(List.of(INSPECTION_ID)), any()))
                .thenReturn(0L);
        when(defectRepository.countGroupByGrade(List.of(INSPECTION_ID))).thenReturn(List.of());
        when(defectRepository.countGroupByTypeOrderByCntDesc(List.of(INSPECTION_ID))).thenReturn(List.of());

        LocalDate thisWeekStart = LocalDate.now(KST).with(DayOfWeek.MONDAY);
        LocalDate nextWeekStart = thisWeekStart.plusWeeks(1);
        LocalDate lastWeekStart = thisWeekStart.minusWeeks(1);
        LocalDateTime thisWeekFrom = thisWeekStart.atStartOfDay();
        LocalDateTime thisWeekTo = nextWeekStart.atStartOfDay().minusNanos(1);
        LocalDateTime lastWeekFrom = lastWeekStart.atStartOfDay();
        LocalDateTime lastWeekTo = thisWeekStart.atStartOfDay().minusNanos(1);

        when(defectRepository.countByInspectionIdInAndDeletedFalseAndCreatedAtBetween(
                List.of(INSPECTION_ID), thisWeekFrom, thisWeekTo)).thenReturn(5L);
        when(defectRepository.countByInspectionIdInAndDeletedFalseAndCreatedAtBetween(
                List.of(INSPECTION_ID), lastWeekFrom, lastWeekTo)).thenReturn(8L);

        BriefingStatsRequest stats = briefingStatsService.buildStats(OWNER_ID);

        assertThat(stats.thisWeekDefects()).isEqualTo(5L);
        assertThat(stats.lastWeekDefects()).isEqualTo(8L);
        verify(defectRepository).countByInspectionIdInAndDeletedFalseAndCreatedAtBetween(
                List.of(INSPECTION_ID), thisWeekFrom, thisWeekTo);
        verify(defectRepository).countByInspectionIdInAndDeletedFalseAndCreatedAtBetween(
                List.of(INSPECTION_ID), lastWeekFrom, lastWeekTo);
    }

    @Test
    void buildStats_등급분포에서_D_E합산으로criticalDefects산출() {
        when(facilityRepository.findByOwnerId(OWNER_ID))
                .thenReturn(List.of(facility(FACILITY_ID, OWNER_ID, "내시설")));
        when(facilityRepository.countByOwnerId(OWNER_ID)).thenReturn(1L);
        when(inspectionRepository.findByFacilityIdIn(List.of(FACILITY_ID)))
                .thenReturn(List.of(inspection(INSPECTION_ID, FACILITY_ID, InspectionStatus.REVIEWED)));
        when(inspectionRepository.countByFacilityIdInAndStatusInAndInspectionDateRange(
                eq(List.of(FACILITY_ID)), anyCollection(), any(), any())).thenReturn(0L);
        when(inspectionRepository.countByFacilityIdInAndStatusIn(eq(List.of(FACILITY_ID)), anyCollection()))
                .thenReturn(0L);
        when(defectRepository.countByInspectionIdInAndStatusAndDeletedFalse(eq(List.of(INSPECTION_ID)), any()))
                .thenReturn(0L);
        when(defectRepository.countByInspectionIdInAndDeletedFalseAndCreatedAtBetween(
                eq(List.of(INSPECTION_ID)), any(), any())).thenReturn(0L);
        when(defectRepository.countGroupByGrade(List.of(INSPECTION_ID))).thenReturn(List.of(
                gradeCount(DefectGrade.A, 1L),
                gradeCount(DefectGrade.D, 2L),
                gradeCount(DefectGrade.E, 3L)));
        when(defectRepository.countGroupByTypeOrderByCntDesc(List.of(INSPECTION_ID))).thenReturn(List.of());

        BriefingStatsRequest stats = briefingStatsService.buildStats(OWNER_ID);

        assertThat(stats.criticalDefects()).isEqualTo(5L); // D(2) + E(3)
        assertThat(stats.gradeDistribution())
                .containsEntry("A", 1L).containsEntry("B", 0L).containsEntry("C", 0L)
                .containsEntry("D", 2L).containsEntry("E", 3L);
    }

    @Test
    void buildStats_top타입은카운트내림차순첫번째_한글라벨로변환() {
        when(facilityRepository.findByOwnerId(OWNER_ID))
                .thenReturn(List.of(facility(FACILITY_ID, OWNER_ID, "내시설")));
        when(facilityRepository.countByOwnerId(OWNER_ID)).thenReturn(1L);
        when(inspectionRepository.findByFacilityIdIn(List.of(FACILITY_ID)))
                .thenReturn(List.of(inspection(INSPECTION_ID, FACILITY_ID, InspectionStatus.REVIEWED)));
        when(inspectionRepository.countByFacilityIdInAndStatusInAndInspectionDateRange(
                eq(List.of(FACILITY_ID)), anyCollection(), any(), any())).thenReturn(0L);
        when(inspectionRepository.countByFacilityIdInAndStatusIn(eq(List.of(FACILITY_ID)), anyCollection()))
                .thenReturn(0L);
        when(defectRepository.countByInspectionIdInAndStatusAndDeletedFalse(eq(List.of(INSPECTION_ID)), any()))
                .thenReturn(0L);
        when(defectRepository.countByInspectionIdInAndDeletedFalseAndCreatedAtBetween(
                eq(List.of(INSPECTION_ID)), any(), any())).thenReturn(0L);
        when(defectRepository.countGroupByGrade(List.of(INSPECTION_ID))).thenReturn(List.of());
        when(defectRepository.countGroupByTypeOrderByCntDesc(List.of(INSPECTION_ID))).thenReturn(List.of(
                typeCount(DefectType.CRACK, 10L),
                typeCount(DefectType.SPALLING, 4L)));

        BriefingStatsRequest stats = briefingStatsService.buildStats(OWNER_ID);

        assertThat(stats.topDefectType()).isEqualTo("균열");
    }

    @Test
    void buildStats_소유시설물있지만점검없으면_주간및top타입조회스킵() {
        when(facilityRepository.findByOwnerId(OWNER_ID))
                .thenReturn(List.of(facility(FACILITY_ID, OWNER_ID, "내시설")));
        when(facilityRepository.countByOwnerId(OWNER_ID)).thenReturn(1L);
        when(inspectionRepository.findByFacilityIdIn(List.of(FACILITY_ID))).thenReturn(List.of());
        when(inspectionRepository.countByFacilityIdInAndStatusInAndInspectionDateRange(
                eq(List.of(FACILITY_ID)), anyCollection(), any(), any())).thenReturn(0L);
        when(inspectionRepository.countByFacilityIdInAndStatusIn(eq(List.of(FACILITY_ID)), anyCollection()))
                .thenReturn(0L);

        BriefingStatsRequest stats = briefingStatsService.buildStats(OWNER_ID);

        assertThat(stats.pendingAction()).isZero();
        assertThat(stats.thisWeekDefects()).isZero();
        assertThat(stats.lastWeekDefects()).isZero();
        assertThat(stats.topDefectType()).isEmpty();
        verify(defectRepository, never())
                .countByInspectionIdInAndStatusAndDeletedFalse(anyCollection(), any());
        verify(defectRepository, never())
                .countByInspectionIdInAndDeletedFalseAndCreatedAtBetween(anyCollection(), any(), any());
        verify(defectRepository, never()).countGroupByTypeOrderByCntDesc(anyCollection());
    }
}
