package com.hajacheck.core.facility.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.repository.UserRepository;
import com.hajacheck.auth.service.CompanyScopeGuard;
import com.hajacheck.core.defect.entity.DefectGrade;
import com.hajacheck.core.defect.repository.DefectRepository;
import com.hajacheck.core.defect.repository.InspectionGradeCountProjection;
import com.hajacheck.core.facility.dto.FacilityComparisonResponse;
import com.hajacheck.core.facility.dto.FacilityComparisonResponse.ComparisonKpi;
import com.hajacheck.core.facility.dto.FacilityInspectionOverviewResponse;
import com.hajacheck.core.facility.entity.Facility;
import com.hajacheck.core.facility.repository.FacilityRepository;
import com.hajacheck.core.inspection.entity.Inspection;
import com.hajacheck.core.inspection.entity.InspectionStatus;
import com.hajacheck.core.inspection.repository.InspectionRepository;
import com.hajacheck.core.media.repository.MediaRepository;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class FacilityInspectionOverviewServiceTest {

    @Mock
    private FacilityRepository facilityRepository;
    @Mock
    private InspectionRepository inspectionRepository;
    @Mock
    private DefectRepository defectRepository;
    @Mock
    private MediaRepository mediaRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private CompanyScopeGuard companyScopeGuard;
    @Mock
    private FacilityComparisonService facilityComparisonService;

    @InjectMocks
    private FacilityInspectionOverviewService service;

    private static final Long COMPANY_ID = 1L;
    private static final Long USER_ID = 2L;
    private static final Long FACILITY_ID = 100L;
    private static final Long INSPECTOR_ID = 3L;

    private Facility facility() {
        Facility facility = Facility.builder()
                .companyId(COMPANY_ID)
                .name("테스트빌딩")
                .type("BUILDING")
                .build();
        ReflectionTestUtils.setField(facility, "id", FACILITY_ID);
        return facility;
    }

    private Inspection inspection(Long inspectionId, int roundNo, InspectionStatus status) {
        Inspection inspection = Inspection.builder()
                .facilityId(FACILITY_ID)
                .createdBy(USER_ID)
                .assignedInspectorId(INSPECTOR_ID)
                .roundNo(roundNo)
                .inspectionDate(LocalDate.of(2026, 1, roundNo))
                .status(status)
                .build();
        ReflectionTestUtils.setField(inspection, "id", inspectionId);
        return inspection;
    }

    private User inspector() {
        User user = User.builder().name("김검수").build();
        ReflectionTestUtils.setField(user, "id", INSPECTOR_ID);
        return user;
    }

    @Test
    void get_시설물이_없으면_FACILITY_NOT_FOUND() {
        when(facilityRepository.findByIdAndCompanyId(FACILITY_ID, COMPANY_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(USER_ID, COMPANY_ID, FACILITY_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.FACILITY_NOT_FOUND);
    }

    @Test
    void get_점검_회차가_없으면_전부_0과_빈_이력을_반환한다() {
        when(facilityRepository.findByIdAndCompanyId(FACILITY_ID, COMPANY_ID)).thenReturn(Optional.of(facility()));
        when(inspectionRepository.findByFacilityIdOrderByRoundNoDesc(FACILITY_ID)).thenReturn(List.of());

        FacilityInspectionOverviewResponse response = service.get(USER_ID, COMPANY_ID, FACILITY_ID);

        assertThat(response.overallGrade()).isNull();
        assertThat(response.totalRounds()).isZero();
        assertThat(response.cumulativeDefectCount()).isZero();
        assertThat(response.unresolvedDefectCount()).isZero();
        assertThat(response.history()).isEmpty();
    }

    @Test
    void get_회차가_1개뿐이면_changeNote는_null이고_최신회차_최악등급이_overallGrade다() {
        Inspection round1 = inspection(11L, 1, InspectionStatus.REVIEWED);
        when(facilityRepository.findByIdAndCompanyId(FACILITY_ID, COMPANY_ID)).thenReturn(Optional.of(facility()));
        when(inspectionRepository.findByFacilityIdOrderByRoundNoDesc(FACILITY_ID)).thenReturn(List.of(round1));
        when(mediaRepository.countGroupByInspectionIds(any())).thenReturn(List.of());
        when(defectRepository.countGroupByInspectionIdAndGrade(any()))
                .thenReturn(List.of(gradeCount(11L, DefectGrade.C, 2), gradeCount(11L, DefectGrade.E, 1)));
        when(defectRepository.countGroupByInspectionId(any())).thenReturn(List.of());
        when(defectRepository.countByInspectionIdInAndDeletedFalseAndStatusNot(any(), any())).thenReturn(1L);
        when(userRepository.findAllById(any())).thenReturn(List.of(inspector()));

        FacilityInspectionOverviewResponse response = service.get(USER_ID, COMPANY_ID, FACILITY_ID);

        assertThat(response.totalRounds()).isEqualTo(1);
        assertThat(response.overallGrade()).isEqualTo(DefectGrade.E); // E가 C보다 심각(worst)
        assertThat(response.history()).hasSize(1);
        FacilityInspectionOverviewResponse.HistoryItem item = response.history().get(0);
        assertThat(item.status()).isEqualTo("검수완료");
        assertThat(item.inspectorName()).isEqualTo("김검수");
        assertThat(item.changeNote()).isNull(); // 비교할 이전 회차가 없음
    }

    @Test
    void get_최신회차가_비교가능상태면_changeNote를_채운다() {
        Inspection round1 = inspection(11L, 1, InspectionStatus.REVIEWED);
        Inspection round2 = inspection(12L, 2, InspectionStatus.REVIEWED);
        when(facilityRepository.findByIdAndCompanyId(FACILITY_ID, COMPANY_ID)).thenReturn(Optional.of(facility()));
        when(inspectionRepository.findByFacilityIdOrderByRoundNoDesc(FACILITY_ID))
                .thenReturn(List.of(round2, round1)); // 최신순
        when(mediaRepository.countGroupByInspectionIds(any())).thenReturn(List.of());
        when(defectRepository.countGroupByInspectionIdAndGrade(any())).thenReturn(List.of());
        when(defectRepository.countGroupByInspectionId(any())).thenReturn(List.of());
        when(defectRepository.countByInspectionIdInAndDeletedFalseAndStatusNot(any(), any())).thenReturn(0L);
        when(userRepository.findAllById(any())).thenReturn(List.of(inspector()));
        when(facilityComparisonService.compare(USER_ID, COMPANY_ID, FACILITY_ID, 1, 2))
                .thenReturn(comparisonWith(3L, 1L));

        FacilityInspectionOverviewResponse response = service.get(USER_ID, COMPANY_ID, FACILITY_ID);

        FacilityInspectionOverviewResponse.HistoryItem latest = response.history().get(0);
        FacilityInspectionOverviewResponse.HistoryItem previous = response.history().get(1);
        assertThat(latest.changeNote()).isEqualTo("이전 회차 대비 신규 하자 +3건 · 진행성 1건");
        assertThat(previous.changeNote()).isNull(); // changeNote는 최신 회차에만 존재
    }

    @Test
    void get_최신회차가_아직_분석전이면_비교_없이_changeNote가_null이다() {
        Inspection round1 = inspection(11L, 1, InspectionStatus.REVIEWED);
        Inspection round2 = inspection(12L, 2, InspectionStatus.CREATED); // 아직 분석 전 — 비교 불가
        when(facilityRepository.findByIdAndCompanyId(FACILITY_ID, COMPANY_ID)).thenReturn(Optional.of(facility()));
        when(inspectionRepository.findByFacilityIdOrderByRoundNoDesc(FACILITY_ID))
                .thenReturn(List.of(round2, round1));
        when(mediaRepository.countGroupByInspectionIds(any())).thenReturn(List.of());
        when(defectRepository.countGroupByInspectionIdAndGrade(any())).thenReturn(List.of());
        when(defectRepository.countGroupByInspectionId(any())).thenReturn(List.of());
        when(defectRepository.countByInspectionIdInAndDeletedFalseAndStatusNot(any(), any())).thenReturn(0L);
        when(userRepository.findAllById(any())).thenReturn(List.of(inspector()));

        FacilityInspectionOverviewResponse response = service.get(USER_ID, COMPANY_ID, FACILITY_ID);

        assertThat(response.history().get(0).changeNote()).isNull();
    }

    private InspectionGradeCountProjection gradeCount(Long inspectionId, DefectGrade grade, long cnt) {
        return new InspectionGradeCountProjection() {
            @Override
            public Long getInspectionId() {
                return inspectionId;
            }

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

    private FacilityComparisonResponse comparisonWith(long newDefects, long worsening) {
        return new FacilityComparisonResponse(
                FACILITY_ID,
                "테스트빌딩",
                new FacilityComparisonResponse.CycleOption(1, LocalDate.of(2026, 1, 1)),
                new FacilityComparisonResponse.CycleOption(2, LocalDate.of(2026, 1, 2)),
                List.of(
                        new ComparisonKpi("newDefects", "신규 하자", newDefects, newDefects),
                        new ComparisonKpi("worsening", "진행성 (악화)", worsening, worsening)),
                List.of(),
                List.of(),
                null,
                null);
    }
}
