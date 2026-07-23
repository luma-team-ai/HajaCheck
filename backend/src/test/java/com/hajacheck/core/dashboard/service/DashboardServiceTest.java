package com.hajacheck.core.dashboard.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.repository.UserRepository;
import com.hajacheck.auth.service.CompanyScopeGuard;
import com.hajacheck.core.dashboard.dto.DashboardSummaryResponse;
import com.hajacheck.core.dashboard.dto.GradeDistributionResponse;
import com.hajacheck.core.dashboard.dto.PendingPriorityResponse;
import com.hajacheck.core.dashboard.dto.RecentInspectionResponse;
import com.hajacheck.core.dashboard.dto.UpcomingInspectionResponse;
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
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import java.lang.reflect.Field;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

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
    @Mock
    private CompanyScopeGuard companyScopeGuard;

    @InjectMocks
    private DashboardService dashboardService;

    private static final Long OWNER_ID = 1L;
    private static final Long USER_ID = 101L;
    private static final Long OTHER_OWNER_ID = 2L;
    private static final Long FACILITY_ID = 10L;

    private Facility facility(Long id, Long ownerId, String name) {
        Facility facility = Facility.builder().companyId(ownerId).name(name).type("BUILDING").build();
        setId(facility, "id", id);
        return facility;
    }

    private Facility facilityWithDueAt(Long id, Long ownerId, String name, LocalDate nextInspectionDueAt) {
        Facility facility = Facility.builder()
                .companyId(ownerId).name(name).type("BUILDING")
                .inspectionCycleMonths(6).nextInspectionDueAt(nextInspectionDueAt)
                .build();
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
        when(facilityRepository.findByCompanyId(OWNER_ID)).thenReturn(List.of());
        when(facilityRepository.countByCompanyId(OWNER_ID)).thenReturn(0L);
        when(facilityRepository.countByCompanyIdAndCreatedAtBefore(eq(OWNER_ID), any())).thenReturn(0L);

        DashboardSummaryResponse response = dashboardService.getSummary(USER_ID, OWNER_ID);

        assertThat(response.totalFacilities()).isZero();
        assertThat(response.monthlyAnalyzed()).isZero();
        assertThat(response.pendingReview()).isZero();
        assertThat(response.pendingAction()).isZero();
        verify(inspectionRepository, never()).countByFacilityIdInAndStatusIn(anyCollection(), anyCollection());
    }

    @Test
    void getSummary_소유시설물기준으로만집계() {
        when(facilityRepository.findByCompanyId(OWNER_ID)).thenReturn(List.of(facility(FACILITY_ID, OWNER_ID, "A")));
        when(facilityRepository.countByCompanyId(OWNER_ID)).thenReturn(3L);
        when(facilityRepository.countByCompanyIdAndCreatedAtBefore(eq(OWNER_ID), any())).thenReturn(2L);
        when(inspectionRepository.findByFacilityIdIn(List.of(FACILITY_ID))).thenReturn(List.of());
        when(inspectionRepository.countByFacilityIdInAndStatusInAndInspectionDateRange(
                eq(List.of(FACILITY_ID)), anyCollection(), any(), any())).thenReturn(4L);
        when(inspectionRepository.countByFacilityIdInAndStatusIn(eq(List.of(FACILITY_ID)), anyCollection()))
                .thenReturn(7L);

        DashboardSummaryResponse response = dashboardService.getSummary(USER_ID, OWNER_ID);

        assertThat(response.totalFacilities()).isEqualTo(3L);
        assertThat(response.totalFacilitiesChangeRate()).isEqualTo(50.0); // (3-2)/2*100
        assertThat(response.monthlyAnalyzed()).isEqualTo(4L);
        assertThat(response.pendingReview()).isEqualTo(7L);
    }

    @Test
    void getSummary_이전달0건이면_증가율100퍼센트() {
        when(facilityRepository.findByCompanyId(OWNER_ID)).thenReturn(List.of());
        when(facilityRepository.countByCompanyId(OWNER_ID)).thenReturn(5L);
        when(facilityRepository.countByCompanyIdAndCreatedAtBefore(eq(OWNER_ID), any())).thenReturn(0L);

        DashboardSummaryResponse response = dashboardService.getSummary(USER_ID, OWNER_ID);

        assertThat(response.totalFacilitiesChangeRate()).isEqualTo(100.0);
    }

    @Test
    void getGradeDistribution_등급별percent계산_5개등급모두반환() {
        when(facilityRepository.findByCompanyId(OWNER_ID)).thenReturn(List.of(facility(FACILITY_ID, OWNER_ID, "A")));
        Inspection insp = inspection(100L, FACILITY_ID, OWNER_ID, LocalDate.now(), InspectionStatus.REVIEWED);
        when(inspectionRepository.findByFacilityIdIn(List.of(FACILITY_ID))).thenReturn(List.of(insp));
        when(defectRepository.countGroupByGrade(List.of(100L))).thenReturn(List.of(
                gradeCount(DefectGrade.A, 3L),
                gradeCount(DefectGrade.E, 1L)));

        List<GradeDistributionResponse> result =
                dashboardService.getGradeDistribution(USER_ID, OWNER_ID);

        assertThat(result).hasSize(5);
        assertThat(result).extracting(GradeDistributionResponse::grade)
                .containsExactly("A", "B", "C", "D", "E");
        assertThat(result.get(0).percent()).isEqualTo(75.0); // 3/4*100
        assertThat(result.get(4).percent()).isEqualTo(25.0); // 1/4*100
        assertThat(result.get(1).percent()).isZero();
    }

    @Test
    void getGradeDistribution_하자0건이면_빈목록() {
        // 점검은 있으나 하자가 0건인 경우. 0% 5건을 반환하면 프론트 빈 상태 가드(items.length===0)가
        // 발동하지 못하고, 합계 0% 라 DASH-01 V2("합계 100% 검증")도 깨진다(#347).
        when(facilityRepository.findByCompanyId(OWNER_ID)).thenReturn(List.of(facility(FACILITY_ID, OWNER_ID, "A")));
        Inspection insp = inspection(100L, FACILITY_ID, OWNER_ID, LocalDate.now(), InspectionStatus.REVIEWED);
        when(inspectionRepository.findByFacilityIdIn(List.of(FACILITY_ID))).thenReturn(List.of(insp));
        when(defectRepository.countGroupByGrade(List.of(100L))).thenReturn(List.of());

        List<GradeDistributionResponse> result =
                dashboardService.getGradeDistribution(USER_ID, OWNER_ID);

        assertThat(result).isEmpty();
    }

    @Test
    void getGradeDistribution_소유시설물없으면_빈목록() {
        when(facilityRepository.findByCompanyId(OWNER_ID)).thenReturn(List.of());

        List<GradeDistributionResponse> result =
                dashboardService.getGradeDistribution(USER_ID, OWNER_ID);

        assertThat(result).isEmpty();
    }

    @Test
    void getGradeDistribution_미분류하자만있으면_빈목록() {
        // countGroupByGrade 는 "grade is not null" 조건이라 미분류(grade=null) 하자만 있으면
        // 집계가 비어 total==0 이 된다. 등급 막대에 그릴 것이 없으므로 빈 목록이 맞다(#347).
        when(facilityRepository.findByCompanyId(OWNER_ID)).thenReturn(List.of(facility(FACILITY_ID, OWNER_ID, "A")));
        Inspection insp = inspection(100L, FACILITY_ID, OWNER_ID, LocalDate.now(), InspectionStatus.ANALYZED);
        when(inspectionRepository.findByFacilityIdIn(List.of(FACILITY_ID))).thenReturn(List.of(insp));
        when(defectRepository.countGroupByGrade(List.of(100L))).thenReturn(List.of());

        List<GradeDistributionResponse> result =
                dashboardService.getGradeDistribution(USER_ID, OWNER_ID);

        assertThat(result).isEmpty();
    }

    @Test
    void getPendingPriority_타인소유시설물의점검은조회대상에서제외() {
        // owner 소유 facility 만 findByCompanyId 로 반환되므로, defectRepository 조회에는
        // owner 소유 facility 로부터 얻은 inspectionId 만 전달돼야 한다(cross-owner IDOR 방지).
        when(facilityRepository.findByCompanyId(OWNER_ID))
                .thenReturn(List.of(facility(FACILITY_ID, OWNER_ID, "내시설")));
        Inspection myInspection =
                inspection(200L, FACILITY_ID, OWNER_ID, LocalDate.now(), InspectionStatus.REVIEWED);
        when(inspectionRepository.findByFacilityIdIn(List.of(FACILITY_ID))).thenReturn(List.of(myInspection));
        Defect pending = defect(300L, 200L, DefectGrade.E, DefectStatus.ACTION_PENDING);
        when(defectRepository.findPendingPriorityDefects(
                List.of(200L), DefectStatus.ACTION_PENDING, PageRequest.of(0, 10)))
                .thenReturn(List.of(pending));

        List<PendingPriorityResponse> result =
                dashboardService.getPendingPriority(USER_ID, OWNER_ID);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).location()).isEqualTo("내시설");
        assertThat(result.get(0).grade()).isEqualTo("E");
        // 타인(OTHER_OWNER_ID) 소유 시설물은 findByCompanyId(OWNER_ID) 결과에 없으므로
        // defectRepository 조회 인자에도 해당 시설물의 inspectionId 가 절대 섞이지 않는다.
        verify(defectRepository).findPendingPriorityDefects(
                List.of(200L), DefectStatus.ACTION_PENDING, PageRequest.of(0, 10));
    }

    @Test
    void getPendingPriority_소유시설물없으면_빈목록() {
        when(facilityRepository.findByCompanyId(OWNER_ID)).thenReturn(List.of());

        List<PendingPriorityResponse> result =
                dashboardService.getPendingPriority(USER_ID, OWNER_ID);

        assertThat(result).isEmpty();
        verify(defectRepository, never())
                .findPendingPriorityDefects(any(), any(), any());
    }

    @Test
    void getRecentInspections_시설물명_담당자명_결함수조합() {
        when(facilityRepository.findByCompanyId(OWNER_ID)).thenReturn(List.of(facility(FACILITY_ID, OWNER_ID, "내시설")));
        Inspection insp =
                inspection(400L, FACILITY_ID, 99L, LocalDate.of(2026, 7, 10), InspectionStatus.REPORTED);
        // 건수 제한이 Pageable 로 넘어가므로(#351) PageRequest.of(0, RECENT_LIMIT=10) 을 그대로 단언한다
        // — 상수가 다시 죽는(호출부와 어긋나는) 회귀를 여기서 잡는다.
        when(inspectionRepository.findRecentByFacilityIds(List.of(FACILITY_ID), PageRequest.of(0, 10)))
                .thenReturn(List.of(insp));
        when(defectRepository.countGroupByInspectionId(List.of(400L)))
                .thenReturn(List.of(inspectionCount(400L, 6L)));
        User creator = User.createCompanyOwner("inspector@haja.com", "김검사", "$2a$10$testtesttesttesttesttes");
        setId(creator, "id", 99L);
        when(userRepository.findAllById(List.of(99L))).thenReturn(List.of(creator));

        List<RecentInspectionResponse> result =
                dashboardService.getRecentInspections(USER_ID, OWNER_ID);

        assertThat(result).hasSize(1);
        RecentInspectionResponse item = result.get(0);
        assertThat(item.facilityName()).isEqualTo("내시설");
        assertThat(item.inspector()).isEqualTo("김검사");
        assertThat(item.defectCount()).isEqualTo(6L);
        assertThat(item.status()).isEqualTo("완료");
    }

    @Test
    void getUpcomingInspections_dDay산출_오름차순유지() {
        java.time.ZoneId kst = java.time.ZoneId.of("Asia/Seoul");
        LocalDate today = LocalDate.now(kst);
        Facility soon = facilityWithDueAt(FACILITY_ID, OWNER_ID, "3일후시설", today.plusDays(3));
        Facility later = facilityWithDueAt(20L, OWNER_ID, "10일후시설", today.plusDays(10));
        when(facilityRepository.findUpcomingByCompanyId(
                eq(OWNER_ID), eq(today), eq(today.plusDays(30)), eq(PageRequest.of(0, 5))))
                .thenReturn(List.of(soon, later));

        List<UpcomingInspectionResponse> result =
                dashboardService.getUpcomingInspections(USER_ID, OWNER_ID, 30, 5);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).facilityName()).isEqualTo("3일후시설");
        assertThat(result.get(0).dDay()).isEqualTo(3L);
        assertThat(result.get(0).inspectionCycleMonths()).isEqualTo(6);
        assertThat(result.get(1).facilityName()).isEqualTo("10일후시설");
        assertThat(result.get(1).dDay()).isEqualTo(10L);
    }

    @Test
    void getUpcomingInspections_대상없으면_빈목록() {
        when(facilityRepository.findUpcomingByCompanyId(eq(OWNER_ID), any(), any(), any()))
                .thenReturn(List.of());

        List<UpcomingInspectionResponse> result =
                dashboardService.getUpcomingInspections(USER_ID, OWNER_ID, 30, 5);

        assertThat(result).isEmpty();
    }

    @Test
    void getUpcomingInspections_limit이repository로그대로전달() {
        when(facilityRepository.findUpcomingByCompanyId(eq(OWNER_ID), any(), any(), eq(PageRequest.of(0, 3))))
                .thenReturn(List.of());

        dashboardService.getUpcomingInspections(USER_ID, OWNER_ID, 7, 3);

        verify(facilityRepository).findUpcomingByCompanyId(eq(OWNER_ID), any(), any(), eq(PageRequest.of(0, 3)));
    }

    @Test
    void getUpcomingInspections_limit이상한초과하면_50건으로캡() {
        // DashboardService.UPCOMING_INSPECTIONS_MAX_LIMIT(50) 방어로직(Math.min(limit, 50))이
        // 실제로 Pageable 에 반영되는지 — limit=100 요청이 그대로 repository 에 전달되면 과다조회다.
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        when(facilityRepository.findUpcomingByCompanyId(eq(OWNER_ID), any(), any(), any()))
                .thenReturn(List.of());

        dashboardService.getUpcomingInspections(USER_ID, OWNER_ID, 30, 100);

        verify(facilityRepository).findUpcomingByCompanyId(eq(OWNER_ID), any(), any(), pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(50);
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
    @Test
    void getSummary_회사없는사용자_FORBIDDEN예외() {
        doThrow(new BusinessException(ErrorCode.FORBIDDEN))
                .when(companyScopeGuard).requireEffectiveMembership(USER_ID, null);
        assertThatThrownBy(() -> dashboardService.getSummary(USER_ID, null))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.FORBIDDEN));
    }
}
