package com.hajacheck.core.statistics.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hajacheck.auth.service.CompanyScopeGuard;
import com.hajacheck.core.defect.entity.DefectGrade;
import com.hajacheck.core.defect.entity.DefectStatus;
import com.hajacheck.core.defect.entity.DefectType;
import com.hajacheck.core.defect.repository.DefectRepository;
import com.hajacheck.core.defect.repository.DefectStatusCountProjection;
import com.hajacheck.core.defect.repository.GradeCountProjection;
import com.hajacheck.core.defect.repository.InspectionDefectCountProjection;
import com.hajacheck.core.defect.repository.InspectionGradeCountProjection;
import com.hajacheck.core.defect.repository.InspectionTypeCountProjection;
import com.hajacheck.core.facility.entity.Facility;
import com.hajacheck.core.facility.repository.FacilityRepository;
import com.hajacheck.core.inspection.entity.Inspection;
import com.hajacheck.core.inspection.entity.InspectionStatus;
import com.hajacheck.core.inspection.repository.InspectionRepository;
import com.hajacheck.core.statistics.dto.FacilitySummaryResponse;
import com.hajacheck.core.statistics.dto.MonthlyDefectTrendResponse;
import com.hajacheck.core.statistics.dto.StatisticsKpiSummaryResponse;
import java.lang.reflect.Field;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StatisticsServiceTest {

    private static final Long USER_ID = 10L;
    private static final Long COMPANY_ID = 20L;

    @Mock
    private CompanyScopeGuard companyScopeGuard;
    @Mock
    private FacilityRepository facilityRepository;
    @Mock
    private InspectionRepository inspectionRepository;
    @Mock
    private DefectRepository defectRepository;

    @InjectMocks
    private StatisticsService statisticsService;

    @Test
    void getSummary_회사스코프시설물의기존하자만집계한다() {
        Facility facility = facility(1L, "건물-정기-4개월");
        Inspection inspection = inspection(100L, facility.getId(), YearMonth.now(ZoneId.of("Asia/Seoul")).atDay(1));
        when(facilityRepository.findByCompanyId(COMPANY_ID)).thenReturn(List.of(facility));
        when(inspectionRepository.findByFacilityIdIn(List.of(facility.getId()))).thenReturn(List.of(inspection));
        when(defectRepository.countGroupByInspectionId(List.of(inspection.getId()))).thenReturn(List.of(defectCount(100L, 5L)));
        when(defectRepository.countGroupByStatus(List.of(inspection.getId()))).thenReturn(List.of(
                statusCount(DefectStatus.RESOLVED, 2L),
                statusCount(DefectStatus.CONFIRMED, 3L)));
        when(defectRepository.findResolvedWithActionDateByInspectionIds(
                List.of(inspection.getId()), DefectStatus.RESOLVED)).thenReturn(List.of());

        StatisticsKpiSummaryResponse result = statisticsService.getSummary(USER_ID, COMPANY_ID, "6m", "all");

        assertThat(result.totalDefects()).isEqualTo(5L);
        assertThat(result.progressingDefects()).isEqualTo(3L);
        assertThat(result.actionCompletionRate()).isEqualTo(40.0);
        verify(companyScopeGuard).requireEffectiveMembership(USER_ID, COMPANY_ID);
    }

    @Test
    void getMonthlyTrend_하자가없는월은0으로반환한다() {
        Facility facility = facility(1L, "건물");
        YearMonth thisMonth = YearMonth.now(ZoneId.of("Asia/Seoul"));
        Inspection inspection = inspection(100L, facility.getId(), thisMonth.atDay(1));
        when(facilityRepository.findByCompanyId(COMPANY_ID)).thenReturn(List.of(facility));
        when(inspectionRepository.findByFacilityIdIn(List.of(facility.getId()))).thenReturn(List.of(inspection));
        when(defectRepository.countGroupByInspectionId(List.of(inspection.getId()))).thenReturn(List.of(defectCount(100L, 7L)));

        List<MonthlyDefectTrendResponse> result =
                statisticsService.getMonthlyTrend(USER_ID, COMPANY_ID, "3m", null);

        assertThat(result).hasSize(3);
        assertThat(result.get(2).month()).isEqualTo(thisMonth.toString());
        assertThat(result.get(2).defectCount()).isEqualTo(7L);
        assertThat(result.get(0).defectCount()).isZero();
    }

    @Test
    void getFacilitySummary_시설물은유지하고집계가능값만채운다() {
        Facility facility = facility(1L, "교량-정밀-12개월");
        Inspection inspection = inspection(100L, facility.getId(), YearMonth.now(ZoneId.of("Asia/Seoul")).atDay(2));
        when(facilityRepository.findByCompanyId(COMPANY_ID)).thenReturn(List.of(facility));
        when(inspectionRepository.findByFacilityIdIn(List.of(facility.getId()))).thenReturn(List.of(inspection));
        when(defectRepository.countGroupByInspectionId(List.of(inspection.getId()))).thenReturn(List.of(defectCount(100L, 2L)));
        when(defectRepository.countGroupByInspectionIdAndGrade(List.of(inspection.getId()))).thenReturn(List.of(
                inspectionGradeCount(100L, DefectGrade.C, 1L),
                inspectionGradeCount(100L, DefectGrade.E, 1L)));

        List<FacilitySummaryResponse> result =
                statisticsService.getFacilitySummary(USER_ID, COMPANY_ID, "6m", "all");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).facilityName()).isEqualTo("테스트시설");
        assertThat(result.get(0).totalDefects()).isEqualTo(2L);
        assertThat(result.get(0).latestGrade()).isEqualTo("E");
        assertThat(result.get(0).lastInspectedAt()).isEqualTo(inspection.getInspectionDate().toString());
    }

    @Test
    void getDefectTypeDistribution_프론트계약3종만반환한다() {
        Facility facility = facility(1L, "건물");
        Inspection inspection = inspection(100L, facility.getId(), YearMonth.now(ZoneId.of("Asia/Seoul")).atDay(1));
        when(facilityRepository.findByCompanyId(COMPANY_ID)).thenReturn(List.of(facility));
        when(inspectionRepository.findByFacilityIdIn(List.of(facility.getId()))).thenReturn(List.of(inspection));
        when(defectRepository.countGroupByType(List.of(inspection.getId()))).thenReturn(List.of(
                typeCount(DefectType.CRACK, 3L),
                typeCount(DefectType.PAINT_DAMAGE, 2L)));

        var result = statisticsService.getDefectTypeDistribution(USER_ID, COMPANY_ID, "6m", "all");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).type()).isEqualTo("균열");
        assertThat(result.get(0).count()).isEqualTo(3L);
    }

    private static Facility facility(Long id, String type) {
        Facility facility = Facility.builder()
                .companyId(COMPANY_ID)
                .name("테스트시설")
                .type(type)
                .build();
        setId(facility, id);
        return facility;
    }

    private static Inspection inspection(Long id, Long facilityId, LocalDate date) {
        Inspection inspection = Inspection.builder()
                .facilityId(facilityId)
                .createdBy(USER_ID)
                .assignedInspectorId(USER_ID)
                .roundNo(1)
                .inspectionDate(date)
                .status(InspectionStatus.REVIEWED)
                .build();
        setId(inspection, id);
        return inspection;
    }

    private static void setId(Object target, Long id) {
        try {
            Field field = target.getClass().getDeclaredField("id");
            field.setAccessible(true);
            field.set(target, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static InspectionDefectCountProjection defectCount(Long inspectionId, long count) {
        return new InspectionDefectCountProjection() {
            public Long getInspectionId() {
                return inspectionId;
            }

            public long getCnt() {
                return count;
            }
        };
    }

    private static DefectStatusCountProjection statusCount(DefectStatus status, Long count) {
        return new DefectStatusCountProjection() {
            public DefectStatus getStatus() {
                return status;
            }

            public Long getCnt() {
                return count;
            }
        };
    }

    private static InspectionGradeCountProjection inspectionGradeCount(
            Long inspectionId, DefectGrade grade, Long count) {
        return new InspectionGradeCountProjection() {
            public Long getInspectionId() {
                return inspectionId;
            }

            public DefectGrade getGrade() {
                return grade;
            }

            public long getCnt() {
                return count;
            }
        };
    }

    private static InspectionTypeCountProjection typeCount(DefectType type, Long count) {
        return new InspectionTypeCountProjection() {
            public DefectType getType() {
                return type;
            }

            public Long getCnt() {
                return count;
            }
        };
    }
}
