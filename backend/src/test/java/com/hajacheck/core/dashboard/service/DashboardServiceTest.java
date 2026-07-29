package com.hajacheck.core.dashboard.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
import com.hajacheck.global.common.PageResponse;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import java.lang.reflect.Field;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
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
        when(inspectionRepository.countByFacilityIdInAndStatusInAndInspectionDateRange(
                eq(List.of(FACILITY_ID)), anyCollection(), any(), any())).thenReturn(4L);
        when(inspectionRepository.countByFacilityIdInAndStatusIn(
                eq(List.of(FACILITY_ID)), eq(EnumSet.of(InspectionStatus.ANALYZED))))
                .thenReturn(7L);
        when(inspectionRepository.countByFacilityIdInAndStatusIn(
                eq(List.of(FACILITY_ID)), eq(EnumSet.of(InspectionStatus.REVIEWED))))
                .thenReturn(5L);

        DashboardSummaryResponse response = dashboardService.getSummary(USER_ID, OWNER_ID);

        assertThat(response.totalFacilities()).isEqualTo(3L);
        assertThat(response.totalFacilitiesChangeRate()).isEqualTo(50.0); // (3-2)/2*100
        assertThat(response.monthlyAnalyzed()).isEqualTo(4L);
        assertThat(response.pendingReview()).isEqualTo(7L);
        // HAJA-499 — pendingAction은 하자 ACTION_PENDING이 아니라 점검 REVIEWED(검수확정) 건수다.
        assertThat(response.pendingAction()).isEqualTo(5L);
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
        Defect pending = defect(300L, 200L, DefectGrade.E, DefectStatus.CONFIRMED);
        when(defectRepository.findPendingPriorityDefects(
                List.of(200L), DefectStatus.CONFIRMED, PageRequest.of(0, 10)))
                .thenReturn(List.of(pending));

        List<PendingPriorityResponse> result =
                dashboardService.getPendingPriority(USER_ID, OWNER_ID);

        assertThat(result).hasSize(1);
        // location은 이름·유형·주소를 " · "로 이어붙인다(Figma "이름+세부정보" 정합) — 이 fixture는
        // type("BUILDING")만 채워져 있고 address는 없어(null은 필터링) "내시설 · BUILDING"이 된다.
        assertThat(result.get(0).location()).isEqualTo("내시설 · BUILDING");
        assertThat(result.get(0).grade()).isEqualTo("E");
        // 타인(OTHER_OWNER_ID) 소유 시설물은 findByCompanyId(OWNER_ID) 결과에 없으므로
        // defectRepository 조회 인자에도 해당 시설물의 inspectionId 가 절대 섞이지 않는다.
        verify(defectRepository).findPendingPriorityDefects(
                List.of(200L), DefectStatus.CONFIRMED, PageRequest.of(0, 10));
    }

    @Test
    void getPendingPriority_이름유형주소모두있으면_3파트를이어붙인다() {
        Facility facility = Facility.builder()
                .companyId(OWNER_ID).name("여의도 파크센터").type("BUILDING").address("서울시 영등포구").build();
        setId(facility, "id", FACILITY_ID);
        when(facilityRepository.findByCompanyId(OWNER_ID)).thenReturn(List.of(facility));
        Inspection myInspection =
                inspection(200L, FACILITY_ID, OWNER_ID, LocalDate.now(), InspectionStatus.REVIEWED);
        when(inspectionRepository.findByFacilityIdIn(List.of(FACILITY_ID))).thenReturn(List.of(myInspection));
        Defect pending = defect(300L, 200L, DefectGrade.E, DefectStatus.CONFIRMED);
        when(defectRepository.findPendingPriorityDefects(
                List.of(200L), DefectStatus.CONFIRMED, PageRequest.of(0, 10)))
                .thenReturn(List.of(pending));

        List<PendingPriorityResponse> result = dashboardService.getPendingPriority(USER_ID, OWNER_ID);

        assertThat(result.get(0).location()).isEqualTo("여의도 파크센터 · BUILDING · 서울시 영등포구");
    }

    @Test
    void getPendingPriority_시설물매핑실패하면_location은대시로표시된다() {
        // facilityIdByInspectionId가 가리키는 시설물이 facilityById에 없는(이론상 거의 불가능하나
        // 방어 로직 검증 목적의) 경계 케이스 — locationOf(null)이 "-"를 반환하는지 확인한다.
        when(facilityRepository.findByCompanyId(OWNER_ID))
                .thenReturn(List.of(facility(FACILITY_ID, OWNER_ID, "내시설")));
        Inspection orphanInspection =
                inspection(200L, 999L, OWNER_ID, LocalDate.now(), InspectionStatus.REVIEWED);
        when(inspectionRepository.findByFacilityIdIn(List.of(FACILITY_ID))).thenReturn(List.of(orphanInspection));
        Defect pending = defect(300L, 200L, DefectGrade.E, DefectStatus.CONFIRMED);
        when(defectRepository.findPendingPriorityDefects(
                List.of(200L), DefectStatus.CONFIRMED, PageRequest.of(0, 10)))
                .thenReturn(List.of(pending));

        List<PendingPriorityResponse> result = dashboardService.getPendingPriority(USER_ID, OWNER_ID);

        assertThat(result.get(0).location()).isEqualTo("-");
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
    // ── 대시보드 "최근 점검 전체보기"(신규) — searchRecentInspections ──

    @Test
    void searchRecentInspections_필터없으면_빈상태집합과빈검색조건으로위임() {
        Page<Inspection> emptyPage = new PageImpl<>(List.of(), PageRequest.of(0, 10), 0);
        when(inspectionRepository.findRecentInspectionsPage(
                eq(OWNER_ID), isNull(), isNull(), eq(Set.of()), isNull(), eq(List.of()), eq(PageRequest.of(0, 10))))
                .thenReturn(emptyPage);

        PageResponse<RecentInspectionResponse> result = dashboardService.searchRecentInspections(
                USER_ID, OWNER_ID, null, null, null, null, PageRequest.of(0, 10));

        assertThat(result.content()).isEmpty();
        assertThat(result.totalElements()).isZero();
        assertThat(result.page()).isZero();
    }

    @Test
    void searchRecentInspections_상태라벨_분석중은3개raw상태집합으로변환() {
        Inspection insp = inspection(500L, FACILITY_ID, 99L, LocalDate.of(2026, 7, 1), InspectionStatus.UPLOADING);
        Page<Inspection> page = new PageImpl<>(List.of(insp), PageRequest.of(0, 10), 1);
        when(inspectionRepository.findRecentInspectionsPage(
                eq(OWNER_ID), isNull(), isNull(),
                eq(EnumSet.of(InspectionStatus.CREATED, InspectionStatus.UPLOADING, InspectionStatus.ANALYZING)),
                isNull(), eq(List.of()), eq(PageRequest.of(0, 10))))
                .thenReturn(page);
        when(defectRepository.countGroupByInspectionId(List.of(500L))).thenReturn(List.of());
        when(facilityRepository.findAllById(List.of(FACILITY_ID)))
                .thenReturn(List.of(facility(FACILITY_ID, OWNER_ID, "테스트빌딩")));
        User creator = User.createCompanyOwner("inspector@haja.com", "김검사", "$2a$10$testtesttesttesttesttes");
        setId(creator, "id", 99L);
        when(userRepository.findAllById(List.of(99L))).thenReturn(List.of(creator));

        PageResponse<RecentInspectionResponse> result = dashboardService.searchRecentInspections(
                USER_ID, OWNER_ID, null, null, "분석중", null, PageRequest.of(0, 10));

        assertThat(result.content()).hasSize(1);
        RecentInspectionResponse item = result.content().get(0);
        assertThat(item.status()).isEqualTo("분석중");
        assertThat(item.facilityName()).isEqualTo("테스트빌딩");
        assertThat(item.inspector()).isEqualTo("김검사");
    }

    @Test
    void searchRecentInspections_알수없는상태라벨_INVALID_INPUT예외() {
        assertThatThrownBy(() -> dashboardService.searchRecentInspections(
                USER_ID, OWNER_ID, null, null, "존재하지않는상태", null, PageRequest.of(0, 10)))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.INVALID_INPUT));
    }

    @Test
    void searchRecentInspections_query있으면_담당자명매칭id를레포지토리에전달() {
        when(userRepository.findIdsByCompanyIdAndNameContaining(OWNER_ID, "김검사")).thenReturn(List.of(99L));
        Page<Inspection> emptyPage = new PageImpl<>(List.of(), PageRequest.of(0, 10), 0);
        when(inspectionRepository.findRecentInspectionsPage(
                eq(OWNER_ID), isNull(), isNull(), eq(Set.of()), eq("김검사"), eq(List.of(99L)),
                eq(PageRequest.of(0, 10))))
                .thenReturn(emptyPage);

        dashboardService.searchRecentInspections(USER_ID, OWNER_ID, null, null, null, "김검사", PageRequest.of(0, 10));

        verify(inspectionRepository).findRecentInspectionsPage(
                eq(OWNER_ID), isNull(), isNull(), eq(Set.of()), eq("김검사"), eq(List.of(99L)),
                eq(PageRequest.of(0, 10)));
    }

    @Test
    void searchRecentInspections_query에LIKE와일드카드포함시_이스케이프해서양쪽레포지토리에동일하게전달() {
        // code review P2 — "%"/"_"를 리터럴로 검색해도 LIKE 와일드카드로 새지 않도록 서비스가
        // 이스케이프한 뒤, userRepository(담당자명)와 inspectionRepository(시설물명) 양쪽에 동일한
        // 이스케이프 값을 넘겨야 한다(둘이 다르면 한쪽만 리터럴 취급되는 불일치가 생긴다).
        // 원본 검색어 "김%검사"(리터럴 %) → 이스케이프 후 "김\%검사"(백슬래시+%, Java 문자열
        // 리터럴로는 "김\\%검사").
        when(userRepository.findIdsByCompanyIdAndNameContaining(OWNER_ID, "김\\%검사")).thenReturn(List.of());
        Page<Inspection> emptyPage = new PageImpl<>(List.of(), PageRequest.of(0, 10), 0);
        when(inspectionRepository.findRecentInspectionsPage(
                eq(OWNER_ID), isNull(), isNull(), eq(Set.of()), eq("김\\%검사"), eq(List.of()),
                eq(PageRequest.of(0, 10))))
                .thenReturn(emptyPage);

        dashboardService.searchRecentInspections(USER_ID, OWNER_ID, null, null, null, "김%검사", PageRequest.of(0, 10));

        verify(userRepository).findIdsByCompanyIdAndNameContaining(OWNER_ID, "김\\%검사");
        verify(inspectionRepository).findRecentInspectionsPage(
                eq(OWNER_ID), isNull(), isNull(), eq(Set.of()), eq("김\\%검사"), eq(List.of()),
                eq(PageRequest.of(0, 10)));
    }

    @Test
    void searchRecentInspections_facilityId가레포지토리에그대로전달() {
        Page<Inspection> emptyPage = new PageImpl<>(List.of(), PageRequest.of(0, 10), 0);
        when(inspectionRepository.findRecentInspectionsPage(
                eq(OWNER_ID), eq(FACILITY_ID), isNull(), eq(Set.of()), isNull(), eq(List.of()),
                eq(PageRequest.of(0, 10))))
                .thenReturn(emptyPage);

        dashboardService.searchRecentInspections(
                USER_ID, OWNER_ID, FACILITY_ID, null, null, null, PageRequest.of(0, 10));

        verify(inspectionRepository).findRecentInspectionsPage(
                eq(OWNER_ID), eq(FACILITY_ID), isNull(), eq(Set.of()), isNull(), eq(List.of()),
                eq(PageRequest.of(0, 10)));
    }

    @Test
    void searchRecentInspections_facilityType이레포지토리에그대로전달() {
        Page<Inspection> emptyPage = new PageImpl<>(List.of(), PageRequest.of(0, 10), 0);
        when(inspectionRepository.findRecentInspectionsPage(
                eq(OWNER_ID), isNull(), eq("건물"), eq(Set.of()), isNull(), eq(List.of()),
                eq(PageRequest.of(0, 10))))
                .thenReturn(emptyPage);

        dashboardService.searchRecentInspections(
                USER_ID, OWNER_ID, null, "건물", null, null, PageRequest.of(0, 10));

        verify(inspectionRepository).findRecentInspectionsPage(
                eq(OWNER_ID), isNull(), eq("건물"), eq(Set.of()), isNull(), eq(List.of()),
                eq(PageRequest.of(0, 10)));
    }

    @Test
    void searchRecentInspections_size상한초과요청은100으로캡() {
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        Page<Inspection> emptyPage = new PageImpl<>(List.of(), PageRequest.of(0, 100), 0);
        when(inspectionRepository.findRecentInspectionsPage(
                eq(OWNER_ID), isNull(), isNull(), eq(Set.of()), isNull(), eq(List.of()), pageableCaptor.capture()))
                .thenReturn(emptyPage);

        dashboardService.searchRecentInspections(USER_ID, OWNER_ID, null, null, null, null, PageRequest.of(0, 500));

        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(100);
    }

    @Test
    void searchRecentInspections_page가매우커서offset이int범위초과시_예외없이빈콘텐츠반환() {
        // PR머신 검수 P3 — offset(=page*size)이 Integer.MAX_VALUE를 넘으면 setFirstResult((int) offset)
        // 캐스팅이 음수가 되어 IllegalArgumentException(500)을 유발한다. DB 조회 자체를 생략하고
        // 빈 페이지로 방어해야 한다(레포지토리 호출조차 없어야 함).
        PageResponse<RecentInspectionResponse> result = dashboardService.searchRecentInspections(
                USER_ID, OWNER_ID, null, null, null, null, PageRequest.of(21474837, 100));

        assertThat(result.content()).isEmpty();
        assertThat(result.totalElements()).isZero();
        verifyNoInteractions(inspectionRepository);
    }

    @Test
    void searchRecentInspections_회사없는사용자_FORBIDDEN예외() {
        doThrow(new BusinessException(ErrorCode.FORBIDDEN))
                .when(companyScopeGuard).requireEffectiveMembership(USER_ID, null);
        assertThatThrownBy(() -> dashboardService.searchRecentInspections(
                USER_ID, null, null, null, null, null, PageRequest.of(0, 10)))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.FORBIDDEN));
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
