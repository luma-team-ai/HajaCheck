package com.hajacheck.core.facility.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.hajacheck.auth.service.CompanyScopeGuard;
import com.hajacheck.core.defect.entity.Defect;
import com.hajacheck.core.defect.entity.DefectGrade;
import com.hajacheck.core.defect.entity.DefectStatus;
import com.hajacheck.core.defect.entity.DefectType;
import com.hajacheck.core.defect.repository.DefectRepository;
import com.hajacheck.core.facility.dto.FacilityComparisonResponse;
import com.hajacheck.core.facility.dto.FacilityComparisonResponse.ComparisonKpi;
import com.hajacheck.core.facility.dto.FacilityComparisonResponse.DefectChangeRow;
import com.hajacheck.core.facility.entity.Facility;
import com.hajacheck.core.facility.repository.FacilityRepository;
import com.hajacheck.core.inspection.entity.Inspection;
import com.hajacheck.core.inspection.entity.InspectionStatus;
import com.hajacheck.core.inspection.repository.InspectionRepository;
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
class FacilityComparisonServiceTest {

    @Mock
    private FacilityRepository facilityRepository;
    @Mock
    private InspectionRepository inspectionRepository;
    @Mock
    private DefectRepository defectRepository;
    @Mock
    private CompanyScopeGuard companyScopeGuard;

    @InjectMocks
    private FacilityComparisonService facilityComparisonService;

    private static final Long COMPANY_ID = 1L;
    private static final Long USER_ID = 2L;
    private static final Long FACILITY_ID = 100L;

    private Facility facility() {
        Facility facility = Facility.builder()
                .companyId(COMPANY_ID)
                .name("테스트빌딩")
                .type("BUILDING")
                .build();
        ReflectionTestUtils.setField(facility, "id", FACILITY_ID);
        return facility;
    }

    private Inspection inspection(Long inspectionId, int roundNo, LocalDate date) {
        Inspection inspection = Inspection.builder()
                .facilityId(FACILITY_ID)
                .createdBy(USER_ID)
                .assignedInspectorId(USER_ID)
                .roundNo(roundNo)
                .inspectionDate(date)
                .status(InspectionStatus.CREATED)
                .build();
        ReflectionTestUtils.setField(inspection, "id", inspectionId);
        return inspection;
    }

    private Defect defect(Long defectId, Long inspectionId, DefectGrade grade, DefectStatus status, String location) {
        Defect defect = Defect.builder()
                .inspectionId(inspectionId)
                .type(DefectType.CRACK)
                .confidence(0.9)
                .grade(grade)
                .status(status)
                .reviewed(true)
                .deleted(false)
                .location(location)
                .build();
        ReflectionTestUtils.setField(defect, "id", defectId);
        return defect;
    }

    private void stubFacilityAndInspections(Inspection before, Inspection after) {
        when(facilityRepository.findByIdAndCompanyId(FACILITY_ID, COMPANY_ID)).thenReturn(Optional.of(facility()));
        when(inspectionRepository.findByFacilityIdAndRoundNo(FACILITY_ID, before.getRoundNo()))
                .thenReturn(Optional.of(before));
        when(inspectionRepository.findByFacilityIdAndRoundNo(FACILITY_ID, after.getRoundNo()))
                .thenReturn(Optional.of(after));
        when(inspectionRepository.findByFacilityIdIn(List.of(FACILITY_ID))).thenReturn(List.of(before, after));
    }

    @Test
    void compare_시설물없음_예외() {
        when(facilityRepository.findByIdAndCompanyId(FACILITY_ID, COMPANY_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> facilityComparisonService.compare(USER_ID, COMPANY_ID, FACILITY_ID, 1, 2))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FACILITY_NOT_FOUND);
    }

    @Test
    void compare_beforeRound가afterRound이상_예외() {
        when(facilityRepository.findByIdAndCompanyId(FACILITY_ID, COMPANY_ID)).thenReturn(Optional.of(facility()));

        assertThatThrownBy(() -> facilityComparisonService.compare(USER_ID, COMPANY_ID, FACILITY_ID, 2, 2))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_INPUT);
    }

    @Test
    void compare_beforeRound점검없음_예외() {
        when(facilityRepository.findByIdAndCompanyId(FACILITY_ID, COMPANY_ID)).thenReturn(Optional.of(facility()));
        when(inspectionRepository.findByFacilityIdAndRoundNo(FACILITY_ID, 1)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> facilityComparisonService.compare(USER_ID, COMPANY_ID, FACILITY_ID, 1, 2))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INSPECTION_NOT_FOUND);
    }

    @Test
    void compare_afterRound점검없음_예외() {
        Inspection before = inspection(10L, 1, LocalDate.of(2026, 1, 1));
        when(facilityRepository.findByIdAndCompanyId(FACILITY_ID, COMPANY_ID)).thenReturn(Optional.of(facility()));
        when(inspectionRepository.findByFacilityIdAndRoundNo(FACILITY_ID, 1)).thenReturn(Optional.of(before));
        when(inspectionRepository.findByFacilityIdAndRoundNo(FACILITY_ID, 2)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> facilityComparisonService.compare(USER_ID, COMPANY_ID, FACILITY_ID, 1, 2))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INSPECTION_NOT_FOUND);
    }

    @Test
    void compare_previousDefectId없는하자_new로분류() {
        Inspection before = inspection(10L, 1, LocalDate.of(2026, 1, 1));
        Inspection after = inspection(11L, 2, LocalDate.of(2026, 2, 1));
        stubFacilityAndInspections(before, after);

        Defect afterDefect = defect(200L, after.getId(), DefectGrade.C, DefectStatus.DETECTED, "1층");
        when(defectRepository.findByInspectionIdAndNotDeleted(before.getId())).thenReturn(List.of());
        when(defectRepository.findByInspectionIdAndNotDeleted(after.getId())).thenReturn(List.of(afterDefect));

        FacilityComparisonResponse response =
                facilityComparisonService.compare(USER_ID, COMPANY_ID, FACILITY_ID, 1, 2);

        assertThat(response.changes()).hasSize(1);
        DefectChangeRow row = response.changes().get(0);
        assertThat(row.changeType()).isEqualTo("new");
        assertThat(row.gradeBefore()).isNull();
        assertThat(row.gradeAfter()).isEqualTo("C");
    }

    @Test
    void compare_동일등급유지_unchanged로분류() {
        Inspection before = inspection(10L, 1, LocalDate.of(2026, 1, 1));
        Inspection after = inspection(11L, 2, LocalDate.of(2026, 2, 1));
        stubFacilityAndInspections(before, after);

        Defect beforeDefect = defect(100L, before.getId(), DefectGrade.C, DefectStatus.CONFIRMED, "1층");
        Defect afterDefect = defect(200L, after.getId(), DefectGrade.C, DefectStatus.CONFIRMED, "1층");
        ReflectionTestUtils.setField(afterDefect, "previousDefectId", 100L);
        when(defectRepository.findByInspectionIdAndNotDeleted(before.getId())).thenReturn(List.of(beforeDefect));
        when(defectRepository.findByInspectionIdAndNotDeleted(after.getId())).thenReturn(List.of(afterDefect));

        FacilityComparisonResponse response =
                facilityComparisonService.compare(USER_ID, COMPANY_ID, FACILITY_ID, 1, 2);

        assertThat(response.changes()).hasSize(1);
        assertThat(response.changes().get(0).changeType()).isEqualTo("unchanged");
    }

    @Test
    void compare_등급악화_worsened로분류() {
        Inspection before = inspection(10L, 1, LocalDate.of(2026, 1, 1));
        Inspection after = inspection(11L, 2, LocalDate.of(2026, 2, 1));
        stubFacilityAndInspections(before, after);

        Defect beforeDefect = defect(100L, before.getId(), DefectGrade.C, DefectStatus.CONFIRMED, "1층");
        Defect afterDefect = defect(200L, after.getId(), DefectGrade.D, DefectStatus.CONFIRMED, "1층");
        ReflectionTestUtils.setField(afterDefect, "previousDefectId", 100L);
        when(defectRepository.findByInspectionIdAndNotDeleted(before.getId())).thenReturn(List.of(beforeDefect));
        when(defectRepository.findByInspectionIdAndNotDeleted(after.getId())).thenReturn(List.of(afterDefect));

        FacilityComparisonResponse response =
                facilityComparisonService.compare(USER_ID, COMPANY_ID, FACILITY_ID, 1, 2);

        assertThat(response.changes()).hasSize(1);
        DefectChangeRow row = response.changes().get(0);
        assertThat(row.changeType()).isEqualTo("worsened");
        assertThat(row.gradeBefore()).isEqualTo("C");
        assertThat(row.gradeAfter()).isEqualTo("D");

        ComparisonKpi gradeEscalated = response.kpis().stream()
                .filter(kpi -> "gradeEscalated".equals(kpi.key()))
                .findFirst().orElseThrow();
        assertThat(gradeEscalated.value()).isEqualTo(1);
    }

    @Test
    void compare_이후회차하자가개별RESOLVED_등급불변이어도resolved로분류() {
        // 코드 리뷰 P1 회귀 고정 — registerActionResult는 연결된 이전 회차 하자를 되돌아보지 않는
        // 행 단위 조치라, previousDefectId로 매칭된 after 행 자체가 RESOLVED로 종결될 수 있다.
        // before 상태/등급 변화가 전혀 없어도(둘 다 CONFIRMED/C) after가 RESOLVED면 resolved여야
        // 하며, unchanged로 새는 걸 이 테스트가 막는다.
        Inspection before = inspection(10L, 1, LocalDate.of(2026, 1, 1));
        Inspection after = inspection(11L, 2, LocalDate.of(2026, 2, 1));
        stubFacilityAndInspections(before, after);

        Defect beforeDefect = defect(100L, before.getId(), DefectGrade.C, DefectStatus.CONFIRMED, "1층");
        Defect afterDefect = defect(200L, after.getId(), DefectGrade.C, DefectStatus.RESOLVED, "1층");
        ReflectionTestUtils.setField(afterDefect, "previousDefectId", 100L);
        when(defectRepository.findByInspectionIdAndNotDeleted(before.getId())).thenReturn(List.of(beforeDefect));
        when(defectRepository.findByInspectionIdAndNotDeleted(after.getId())).thenReturn(List.of(afterDefect));

        FacilityComparisonResponse response =
                facilityComparisonService.compare(USER_ID, COMPANY_ID, FACILITY_ID, 1, 2);

        assertThat(response.changes()).hasSize(1);
        DefectChangeRow row = response.changes().get(0);
        assertThat(row.changeType()).isEqualTo("resolved");
        assertThat(kpiValue(response, "resolved")).isEqualTo(1);
        assertThat(kpiValue(response, "worsening")).isEqualTo(0);
    }

    @Test
    void compare_이전회차RESOLVED가재연결_재발생은worsened로근사매핑() {
        Inspection before = inspection(10L, 1, LocalDate.of(2026, 1, 1));
        Inspection after = inspection(11L, 2, LocalDate.of(2026, 2, 1));
        stubFacilityAndInspections(before, after);

        Defect beforeDefect = defect(100L, before.getId(), DefectGrade.C, DefectStatus.RESOLVED, "1층");
        Defect afterDefect = defect(200L, after.getId(), DefectGrade.C, DefectStatus.DETECTED, "1층");
        ReflectionTestUtils.setField(afterDefect, "previousDefectId", 100L);
        when(defectRepository.findByInspectionIdAndNotDeleted(before.getId())).thenReturn(List.of(beforeDefect));
        when(defectRepository.findByInspectionIdAndNotDeleted(after.getId())).thenReturn(List.of(afterDefect));

        FacilityComparisonResponse response =
                facilityComparisonService.compare(USER_ID, COMPANY_ID, FACILITY_ID, 1, 2);

        // 재발생(이전 RESOLVED + 이후 회차 재연결)은 프론트 DefectChangeType에 대응 타입이 없어
        // worsened로 근사 매핑된다(HAJA-532 후속에서 정확화 예정) — before 행은 matchedBeforeIds에
        // 걸려 별도 resolved 행으로 중복 표시되지 않아야 한다.
        assertThat(response.changes()).hasSize(1);
        assertThat(response.changes().get(0).changeType()).isEqualTo("worsened");
    }

    @Test
    void compare_이전회차하자매칭없이RESOLVED_resolved로분류() {
        Inspection before = inspection(10L, 1, LocalDate.of(2026, 1, 1));
        Inspection after = inspection(11L, 2, LocalDate.of(2026, 2, 1));
        stubFacilityAndInspections(before, after);

        Defect beforeDefect = defect(100L, before.getId(), DefectGrade.C, DefectStatus.RESOLVED, "1층");
        when(defectRepository.findByInspectionIdAndNotDeleted(before.getId())).thenReturn(List.of(beforeDefect));
        when(defectRepository.findByInspectionIdAndNotDeleted(after.getId())).thenReturn(List.of());

        FacilityComparisonResponse response =
                facilityComparisonService.compare(USER_ID, COMPANY_ID, FACILITY_ID, 1, 2);

        assertThat(response.changes()).hasSize(1);
        DefectChangeRow row = response.changes().get(0);
        assertThat(row.changeType()).isEqualTo("resolved");
        assertThat(row.gradeBefore()).isEqualTo("C");
        assertThat(row.gradeAfter()).isNull();
    }

    @Test
    void compare_이전회차하자매칭없이미해결_변경목록에서제외() {
        Inspection before = inspection(10L, 1, LocalDate.of(2026, 1, 1));
        Inspection after = inspection(11L, 2, LocalDate.of(2026, 2, 1));
        stubFacilityAndInspections(before, after);

        Defect beforeDefect = defect(100L, before.getId(), DefectGrade.C, DefectStatus.CONFIRMED, "1층");
        when(defectRepository.findByInspectionIdAndNotDeleted(before.getId())).thenReturn(List.of(beforeDefect));
        when(defectRepository.findByInspectionIdAndNotDeleted(after.getId())).thenReturn(List.of());

        FacilityComparisonResponse response =
                facilityComparisonService.compare(USER_ID, COMPANY_ID, FACILITY_ID, 1, 2);

        // 이전 회차 미매칭 하자가 RESOLVED가 아니면(아직 조치 진행 중) 4개 분류 어디에도 속하지
        // 않아 changes에 나타나지 않는다 — before/after 어느 쪽도 언급하지 않는 게 설계 의도.
        assertThat(response.changes()).isEmpty();
    }

    @Test
    void compare_KPI집계는changes분류건수와일치() {
        Inspection before = inspection(10L, 1, LocalDate.of(2026, 1, 1));
        Inspection after = inspection(11L, 2, LocalDate.of(2026, 2, 1));
        stubFacilityAndInspections(before, after);

        Defect resolvedBefore = defect(100L, before.getId(), DefectGrade.C, DefectStatus.RESOLVED, "1층");
        Defect unchangedBefore = defect(101L, before.getId(), DefectGrade.B, DefectStatus.CONFIRMED, "2층");
        Defect newAfter = defect(200L, after.getId(), DefectGrade.C, DefectStatus.DETECTED, "3층");
        Defect unchangedAfter = defect(201L, after.getId(), DefectGrade.B, DefectStatus.CONFIRMED, "2층");
        ReflectionTestUtils.setField(unchangedAfter, "previousDefectId", 101L);

        when(defectRepository.findByInspectionIdAndNotDeleted(before.getId()))
                .thenReturn(List.of(resolvedBefore, unchangedBefore));
        when(defectRepository.findByInspectionIdAndNotDeleted(after.getId()))
                .thenReturn(List.of(newAfter, unchangedAfter));

        FacilityComparisonResponse response =
                facilityComparisonService.compare(USER_ID, COMPANY_ID, FACILITY_ID, 1, 2);

        assertThat(response.changes()).hasSize(3);
        assertThat(kpiValue(response, "newDefects")).isEqualTo(1);
        assertThat(kpiValue(response, "worsening")).isEqualTo(0);
        assertThat(kpiValue(response, "resolved")).isEqualTo(1);
        assertThat(kpiValue(response, "gradeEscalated")).isEqualTo(0);
    }

    @Test
    void compare_availableCycles는회차오름차순정렬() {
        Inspection round2 = inspection(11L, 2, LocalDate.of(2026, 2, 1));
        Inspection round1 = inspection(10L, 1, LocalDate.of(2026, 1, 1));
        Inspection round3 = inspection(12L, 3, LocalDate.of(2026, 3, 1));

        when(facilityRepository.findByIdAndCompanyId(FACILITY_ID, COMPANY_ID)).thenReturn(Optional.of(facility()));
        when(inspectionRepository.findByFacilityIdAndRoundNo(FACILITY_ID, 1)).thenReturn(Optional.of(round1));
        when(inspectionRepository.findByFacilityIdAndRoundNo(FACILITY_ID, 2)).thenReturn(Optional.of(round2));
        when(inspectionRepository.findByFacilityIdIn(List.of(FACILITY_ID)))
                .thenReturn(List.of(round2, round1, round3));
        when(defectRepository.findByInspectionIdAndNotDeleted(any())).thenReturn(List.of());

        FacilityComparisonResponse response =
                facilityComparisonService.compare(USER_ID, COMPANY_ID, FACILITY_ID, 1, 2);

        assertThat(response.availableCycles()).extracting(FacilityComparisonResponse.CycleOption::cycle)
                .containsExactly(1, 2, 3);
    }

    private long kpiValue(FacilityComparisonResponse response, String key) {
        return response.kpis().stream()
                .filter(kpi -> key.equals(kpi.key()))
                .findFirst().orElseThrow()
                .value();
    }
}
