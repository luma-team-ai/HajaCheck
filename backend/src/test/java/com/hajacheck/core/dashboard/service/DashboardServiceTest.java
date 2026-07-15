package com.hajacheck.core.dashboard.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.repository.UserRepository;
import com.hajacheck.core.dashboard.dto.DashboardSummaryResponse;
import com.hajacheck.core.dashboard.dto.GradeDistributionResponse;
import com.hajacheck.core.dashboard.dto.PendingPriorityResponse;
import com.hajacheck.core.dashboard.dto.RecentInspectionResponse;
import com.hajacheck.core.defect.entity.Defect;
import com.hajacheck.core.defect.entity.DefectGrade;
import com.hajacheck.core.defect.entity.DefectStatus;
import com.hajacheck.core.defect.entity.DefectType;
import com.hajacheck.core.defect.repository.DefectRepository;
import com.hajacheck.core.defect.repository.GradeCountProjection;
import com.hajacheck.core.defect.repository.InspectionDefectCountProjection;
import com.hajacheck.core.facility.entity.Facility;
import com.hajacheck.core.facility.repository.FacilityRepository;
import com.hajacheck.core.inspection.entity.Inspection;
import com.hajacheck.core.inspection.entity.InspectionStatus;
import com.hajacheck.core.inspection.repository.InspectionRepository;
import java.lang.reflect.Field;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DashboardServiceTest {

    @Mock
    private FacilityRepository facilityRepository;

    @Mock
    private InspectionRepository inspectionRepository;

    @Mock
    private DefectRepository defectRepository;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private DashboardService dashboardService;

    private static final Long OWNER_ID = 1L;
    private static final Long OTHER_OWNER_ID = 2L;
    private static final Long FACILITY_ID = 10L;

    private Facility facility(Long id, Long ownerId, String name) {
        Facility facility = Facility.builder().ownerId(ownerId).name(name).type("BUILDING").build();
        setId(facility, "id", id);
        return facility;
    }

    private Inspection inspection(Long id, Long facilityId, Long createdBy, LocalDate date, InspectionStatus status) {
        Inspection inspection = Inspection.builder()
                .facilityId(facilityId).createdBy(createdBy).roundNo(1).inspectionDate(date).status(status).build();
        setId(inspection, "id", id);
        return inspection;
    }

    private Defect defect(Long id, Long inspectionId, DefectGrade grade, DefectStatus status) {
        Defect defect = Defect.builder()
                .inspectionId(inspectionId).type(DefectType.CRACK).confidence(0.9)
                .grade(grade).status(status).build();
        setId(defect, "id", id);
        return defect;
    }

    private void setId(Object target, String fieldName, Long value) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void getSummary_소유시설물없으면_전부0() {
        when(facilityRepository.findByOwnerId(OWNER_ID)).thenReturn(List.of());
        when(facilityRepository.countByOwnerId(OWNER_ID)).thenReturn(0L);
        when(facilityRepository.countByOwnerIdAndCreatedAtBefore(eq(OWNER_ID), any())).thenReturn(0L);

        DashboardSummaryResponse response = dashboardService.getSummary(OWNER_ID);

        assertThat(response.totalFacilities()).isZero();
        assertThat(response.monthlyAnalyzed()).isZero();
        assertThat(response.pendingReview()).isZero();
        assertThat(response.pendingAction()).isZero();
        verify(inspectionRepository, never()).countByFacilityIdInAndStatusIn(anyCollection(), anyCollection());
    }

    @Test
    void getSummary_소유시설물기준으로만집계() {
        when(facilityRepository.findByOwnerId(OWNER_ID)).thenReturn(List.of(facility(FACILITY_ID, OWNER_ID, "A")));
        when(facilityRepository.countByOwnerId(OWNER_ID)).thenReturn(3L);
        when(facilityRepository.countByOwnerIdAndCreatedAtBefore(eq(OWNER_ID), any())).thenReturn(2L);
        when(inspectionRepository.findByFacilityIdIn(List.of(FACILITY_ID))).thenReturn(List.of());
        when(inspectionRepository.countByFacilityIdInAndStatusInAndInspectionDateRange(
                eq(List.of(FACILITY_ID)), anyCollection(), any(), any())).thenReturn(4L);
        when(inspectionRepository.countByFacilityIdInAndStatusIn(eq(List.of(FACILITY_ID)), anyCollection()))
                .thenReturn(7L);

        DashboardSummaryResponse response = dashboardService.getSummary(OWNER_ID);

        assertThat(response.totalFacilities()).isEqualTo(3L);
        assertThat(response.totalFacilitiesChangeRate()).isEqualTo(50.0); // (3-2)/2*100
        assertThat(response.monthlyAnalyzed()).isEqualTo(4L);
        assertThat(response.pendingReview()).isEqualTo(7L);
    }

    @Test
    void getSummary_이전달0건이면_증가율100퍼센트() {
        when(facilityRepository.findByOwnerId(OWNER_ID)).thenReturn(List.of());
        when(facilityRepository.countByOwnerId(OWNER_ID)).thenReturn(5L);
        when(facilityRepository.countByOwnerIdAndCreatedAtBefore(eq(OWNER_ID), any())).thenReturn(0L);

        DashboardSummaryResponse response = dashboardService.getSummary(OWNER_ID);

        assertThat(response.totalFacilitiesChangeRate()).isEqualTo(100.0);
    }

    @Test
    void getGradeDistribution_등급별percent계산_5개등급모두반환() {
        when(facilityRepository.findByOwnerId(OWNER_ID)).thenReturn(List.of(facility(FACILITY_ID, OWNER_ID, "A")));
        Inspection insp = inspection(100L, FACILITY_ID, OWNER_ID, LocalDate.now(), InspectionStatus.REVIEWED);
        when(inspectionRepository.findByFacilityIdIn(List.of(FACILITY_ID))).thenReturn(List.of(insp));
        when(defectRepository.countGroupByGrade(List.of(100L))).thenReturn(List.of(
                gradeCount(DefectGrade.A, 3L),
                gradeCount(DefectGrade.E, 1L)));

        List<GradeDistributionResponse> result = dashboardService.getGradeDistribution(OWNER_ID);

        assertThat(result).hasSize(5);
        assertThat(result).extracting(GradeDistributionResponse::grade)
                .containsExactly("A", "B", "C", "D", "E");
        assertThat(result.get(0).percent()).isEqualTo(75.0); // 3/4*100
        assertThat(result.get(4).percent()).isEqualTo(25.0); // 1/4*100
        assertThat(result.get(1).percent()).isZero();
    }

    @Test
    void getPendingPriority_타인소유시설물의점검은조회대상에서제외() {
        // owner 소유 facility 만 findByOwnerId 로 반환되므로, defectRepository 조회에는
        // owner 소유 facility 로부터 얻은 inspectionId 만 전달돼야 한다(cross-owner IDOR 방지).
        when(facilityRepository.findByOwnerId(OWNER_ID))
                .thenReturn(List.of(facility(FACILITY_ID, OWNER_ID, "내시설")));
        Inspection myInspection =
                inspection(200L, FACILITY_ID, OWNER_ID, LocalDate.now(), InspectionStatus.REVIEWED);
        when(inspectionRepository.findByFacilityIdIn(List.of(FACILITY_ID))).thenReturn(List.of(myInspection));
        Defect pending = defect(300L, 200L, DefectGrade.E, DefectStatus.ACTION_PENDING);
        when(defectRepository.findTop10ByInspectionIdInAndStatusAndDeletedFalseOrderByGradeDescCreatedAtDesc(
                List.of(200L), DefectStatus.ACTION_PENDING)).thenReturn(List.of(pending));

        List<PendingPriorityResponse> result = dashboardService.getPendingPriority(OWNER_ID);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).location()).isEqualTo("내시설");
        assertThat(result.get(0).grade()).isEqualTo("E");
        // 타인(OTHER_OWNER_ID) 소유 시설물은 findByOwnerId(OWNER_ID) 결과에 없으므로
        // defectRepository 조회 인자에도 해당 시설물의 inspectionId 가 절대 섞이지 않는다.
        verify(defectRepository).findTop10ByInspectionIdInAndStatusAndDeletedFalseOrderByGradeDescCreatedAtDesc(
                List.of(200L), DefectStatus.ACTION_PENDING);
    }

    @Test
    void getPendingPriority_소유시설물없으면_빈목록() {
        when(facilityRepository.findByOwnerId(OWNER_ID)).thenReturn(List.of());

        List<PendingPriorityResponse> result = dashboardService.getPendingPriority(OWNER_ID);

        assertThat(result).isEmpty();
        verify(defectRepository, never())
                .findTop10ByInspectionIdInAndStatusAndDeletedFalseOrderByGradeDescCreatedAtDesc(any(), any());
    }

    @Test
    void getRecentInspections_시설물명_담당자명_결함수조합() {
        when(facilityRepository.findByOwnerId(OWNER_ID)).thenReturn(List.of(facility(FACILITY_ID, OWNER_ID, "내시설")));
        Inspection insp =
                inspection(400L, FACILITY_ID, 99L, LocalDate.of(2026, 7, 10), InspectionStatus.REPORTED);
        when(inspectionRepository.findTop10ByFacilityIdInOrderByInspectionDateDescIdDesc(List.of(FACILITY_ID)))
                .thenReturn(List.of(insp));
        when(defectRepository.countGroupByInspectionId(List.of(400L)))
                .thenReturn(List.of(inspectionCount(400L, 6L)));
        User creator = User.createCompanyOwner("inspector@haja.com", "김검사", "$2a$10$testtesttesttesttesttes");
        setId(creator, "id", 99L);
        when(userRepository.findAllById(List.of(99L))).thenReturn(List.of(creator));

        List<RecentInspectionResponse> result = dashboardService.getRecentInspections(OWNER_ID);

        assertThat(result).hasSize(1);
        RecentInspectionResponse item = result.get(0);
        assertThat(item.facilityName()).isEqualTo("내시설");
        assertThat(item.inspector()).isEqualTo("김검사");
        assertThat(item.defectCount()).isEqualTo(6L);
        assertThat(item.status()).isEqualTo("완료");
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

    private InspectionDefectCountProjection inspectionCount(Long inspectionId, long cnt) {
        return new InspectionDefectCountProjection() {
            @Override
            public Long getInspectionId() {
                return inspectionId;
            }

            @Override
            public long getCnt() {
                return cnt;
            }
        };
    }
}
