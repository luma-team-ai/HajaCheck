package com.hajacheck.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.hajacheck.admin.dto.AdminAnalysisJobItem;
import com.hajacheck.admin.dto.AdminAnalysisJobStatus;
import com.hajacheck.auth.entity.Role;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.repository.UserRepository;
import com.hajacheck.core.analysis.dto.AnalysisStatusResponse;
import com.hajacheck.core.analysis.support.AnalysisProgressStore;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

/**
 * AdminAnalysisJobService 단위 테스트 — "완료 범위는 ANALYZED부터"(팀 결정) 상태 3분류
 * (PENDING/ANALYZING/COMPLETED) 매핑과, ANALYZING 건에 한해서만 Redis 진행률을 보강하는지 고정한다.
 * DashboardServiceTest.searchRecentInspections 테스트와 동일 패턴(findRecentInspectionsPage mock).
 */
@ExtendWith(MockitoExtension.class)
class AdminAnalysisJobServiceTest {

    private static final Long COMPANY_ID = 1L;
    private static final Long FACILITY_ID = 10L;
    private static final Long INSPECTOR_ID = 99L;

    @Mock
    private InspectionRepository inspectionRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private FacilityRepository facilityRepository;
    @Mock
    private AnalysisProgressStore analysisProgressStore;

    @InjectMocks
    private AdminAnalysisJobService adminAnalysisJobService;

    private Inspection inspection(Long id, InspectionStatus status) {
        Inspection inspection = Inspection.builder()
                .facilityId(FACILITY_ID).createdBy(INSPECTOR_ID).assignedInspectorId(INSPECTOR_ID)
                .roundNo(1).inspectionDate(LocalDate.of(2026, 7, 1)).status(status).build();
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

    @Test
    void list_companyId없으면_FORBIDDEN이고_아무레포지토리도호출하지않는다() {
        assertThatThrownBy(() -> adminAnalysisJobService.list(null, null, PageRequest.of(0, 10)))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));

        verifyNoInteractions(inspectionRepository, userRepository, facilityRepository, analysisProgressStore);
    }

    @Test
    void list_상태필터없으면_빈Set으로위임한다() {
        Page<Inspection> emptyPage = new PageImpl<>(List.of(), PageRequest.of(0, 10), 0);
        when(inspectionRepository.findRecentInspectionsPage(
                eq(COMPANY_ID), isNull(), isNull(), eq(Set.of()), isNull(), eq(List.of()), eq(PageRequest.of(0, 10))))
                .thenReturn(emptyPage);

        PageResponse<AdminAnalysisJobItem> result =
                adminAnalysisJobService.list(COMPANY_ID, null, PageRequest.of(0, 10));

        assertThat(result.content()).isEmpty();
        verify(inspectionRepository).findRecentInspectionsPage(
                eq(COMPANY_ID), isNull(), isNull(), eq(Set.of()), isNull(), eq(List.of()), eq(PageRequest.of(0, 10)));
    }

    @Test
    void list_ANALYZING상태는_진행률을Redis에서보강한다() {
        Inspection analyzing = inspection(500L, InspectionStatus.ANALYZING);
        Page<Inspection> page = new PageImpl<>(List.of(analyzing), PageRequest.of(0, 10), 1);
        when(inspectionRepository.findRecentInspectionsPage(
                eq(COMPANY_ID), isNull(), isNull(), eq(AdminAnalysisJobStatus.ANALYZING.toInspectionStatuses()),
                isNull(), eq(List.of()), eq(PageRequest.of(0, 10))))
                .thenReturn(page);
        when(facilityRepository.findAllById(List.of(FACILITY_ID)))
                .thenReturn(List.of(facility(FACILITY_ID, "테스트빌딩")));
        User inspector = User.createByAdmin("inspector@haja.com", "김검사", Role.INSPECTOR, "hash", COMPANY_ID);
        setId(inspector, INSPECTOR_ID);
        when(userRepository.findAllById(List.of(INSPECTOR_ID))).thenReturn(List.of(inspector));
        when(analysisProgressStore.find(500L))
                .thenReturn(Optional.of(statusResponse(500L, 42)));

        PageResponse<AdminAnalysisJobItem> result =
                adminAnalysisJobService.list(COMPANY_ID, AdminAnalysisJobStatus.ANALYZING, PageRequest.of(0, 10));

        assertThat(result.content()).hasSize(1);
        AdminAnalysisJobItem item = result.content().get(0);
        assertThat(item.jobId()).isEqualTo(500L);
        assertThat(item.facilityName()).isEqualTo("테스트빌딩");
        assertThat(item.inspectorName()).isEqualTo("김검사");
        assertThat(item.status()).isEqualTo(AdminAnalysisJobStatus.ANALYZING);
        assertThat(item.progressPercent()).isEqualTo(42);
    }

    @Test
    void list_ANALYZED이상은_완료로분류되고_진행률조회를아예하지않는다() {
        Inspection analyzed = inspection(501L, InspectionStatus.ANALYZED);
        Page<Inspection> page = new PageImpl<>(List.of(analyzed), PageRequest.of(0, 10), 1);
        when(inspectionRepository.findRecentInspectionsPage(
                eq(COMPANY_ID), isNull(), isNull(), eq(AdminAnalysisJobStatus.COMPLETED.toInspectionStatuses()),
                isNull(), eq(List.of()), eq(PageRequest.of(0, 10))))
                .thenReturn(page);
        when(facilityRepository.findAllById(List.of(FACILITY_ID)))
                .thenReturn(List.of(facility(FACILITY_ID, "테스트빌딩")));
        User inspector = User.createByAdmin("inspector@haja.com", "김검사", Role.INSPECTOR, "hash", COMPANY_ID);
        setId(inspector, INSPECTOR_ID);
        when(userRepository.findAllById(List.of(INSPECTOR_ID))).thenReturn(List.of(inspector));

        PageResponse<AdminAnalysisJobItem> result =
                adminAnalysisJobService.list(COMPANY_ID, AdminAnalysisJobStatus.COMPLETED, PageRequest.of(0, 10));

        assertThat(result.content()).hasSize(1);
        assertThat(result.content().get(0).status()).isEqualTo(AdminAnalysisJobStatus.COMPLETED);
        assertThat(result.content().get(0).progressPercent()).isNull();
        verify(analysisProgressStore, never()).find(501L);
    }

    @Test
    void list_Redis캐시가없으면_진행률은null이지만_행자체는반환한다() {
        Inspection analyzing = inspection(502L, InspectionStatus.ANALYZING);
        Page<Inspection> page = new PageImpl<>(List.of(analyzing), PageRequest.of(0, 10), 1);
        when(inspectionRepository.findRecentInspectionsPage(
                eq(COMPANY_ID), isNull(), isNull(), eq(AdminAnalysisJobStatus.ANALYZING.toInspectionStatuses()),
                isNull(), eq(List.of()), eq(PageRequest.of(0, 10))))
                .thenReturn(page);
        when(facilityRepository.findAllById(List.of(FACILITY_ID)))
                .thenReturn(List.of(facility(FACILITY_ID, "테스트빌딩")));
        User inspector = User.createByAdmin("inspector@haja.com", "김검사", Role.INSPECTOR, "hash", COMPANY_ID);
        setId(inspector, INSPECTOR_ID);
        when(userRepository.findAllById(List.of(INSPECTOR_ID))).thenReturn(List.of(inspector));
        when(analysisProgressStore.find(502L)).thenReturn(Optional.empty());

        PageResponse<AdminAnalysisJobItem> result =
                adminAnalysisJobService.list(COMPANY_ID, AdminAnalysisJobStatus.ANALYZING, PageRequest.of(0, 10));

        assertThat(result.content()).hasSize(1);
        assertThat(result.content().get(0).progressPercent()).isNull();
    }

    private Facility facility(Long id, String name) {
        Facility facility = Facility.builder().companyId(COMPANY_ID).name(name).type("BUILDING").build();
        setId(facility, id);
        return facility;
    }

    private AnalysisStatusResponse statusResponse(Long inspectionId, int progressPercent) {
        return new AnalysisStatusResponse(
                inspectionId, "aiDetection", progressPercent, 3, 1, List.of(), 0, 0, Map.of(), 0, 0, false, null);
    }
}
