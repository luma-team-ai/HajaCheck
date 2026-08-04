package com.hajacheck.mypage.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hajacheck.auth.service.CompanyScopeGuard;
import com.hajacheck.core.defect.entity.DefectGrade;
import com.hajacheck.core.defect.repository.DefectRepository;
import com.hajacheck.core.defect.repository.InspectionDefectCountProjection;
import com.hajacheck.core.defect.repository.InspectionGradeProjection;
import com.hajacheck.core.facility.entity.Facility;
import com.hajacheck.core.inspection.entity.Inspection;
import com.hajacheck.core.inspection.entity.InspectionStatus;
import com.hajacheck.core.inspection.repository.InspectionRepository;
import com.hajacheck.core.report.entity.Report;
import com.hajacheck.core.report.entity.ReportStatus;
import com.hajacheck.core.report.repository.ReportRepository;
import com.hajacheck.core.report.support.ReportPdfStorage;
import com.hajacheck.global.common.PageResponse;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import com.hajacheck.mypage.dto.MyInspectionDisplayStatus;
import com.hajacheck.mypage.dto.MyInspectionRole;
import com.hajacheck.mypage.dto.MyInspectionRowResponse;
import com.hajacheck.mypage.dto.MyInspectionsSummaryResponse;
import com.hajacheck.mypage.dto.MyReportRowResponse;
import com.hajacheck.mypage.dto.ReportGradeDotColor;
import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class MyInspectionsServiceTest {

    @Mock
    private InspectionRepository inspectionRepository;
    @Mock
    private DefectRepository defectRepository;
    @Mock
    private ReportRepository reportRepository;
    @Mock
    private CompanyScopeGuard companyScopeGuard;
    @Mock
    private ReportPdfStorage reportPdfStorage;

    @InjectMocks
    private MyInspectionsService service;

    private static Inspection inspectionWithFacility(
            Long id, Long facilityId, String facilityName, Integer roundNo,
            Long assignedInspectorId, Long createdBy, InspectionStatus status) {
        Facility facility = Facility.builder().companyId(100L).name(facilityName).type("BUILDING").build();
        ReflectionTestUtils.setField(facility, "id", facilityId);

        Inspection inspection = Inspection.builder()
                .facilityId(facilityId)
                .createdBy(createdBy)
                .assignedInspectorId(assignedInspectorId)
                .roundNo(roundNo)
                .inspectionDate(LocalDate.of(2026, 7, 20))
                .status(status)
                .build();
        ReflectionTestUtils.setField(inspection, "id", id);
        ReflectionTestUtils.setField(inspection, "facility", facility);
        return inspection;
    }

    private static InspectionDefectCountProjection countProjection(Long inspectionId, long cnt) {
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

    private static InspectionGradeProjection gradeProjection(Long inspectionId, DefectGrade grade) {
        return new InspectionGradeProjection() {
            @Override
            public Long getInspectionId() {
                return inspectionId;
            }

            @Override
            public DefectGrade getGrade() {
                return grade;
            }
        };
    }

    private static Report reportOf(Long id, Long inspectionId, Inspection inspection, String pdfUrl) {
        Report report = Report.draft(inspectionId, 1, "{}", 300L);
        ReflectionTestUtils.setField(report, "id", id);
        ReflectionTestUtils.setField(report, "pdfUrl", pdfUrl);
        ReflectionTestUtils.setField(report, "status", ReportStatus.FINALIZED);
        ReflectionTestUtils.setField(report, "inspection", inspection);
        return report;
    }

    // ── getSummary ──

    @Test
    void getSummary_4종집계결과조립_스코프가드호출() {
        when(inspectionRepository.countMine(100L, 300L)).thenReturn(18L);
        when(inspectionRepository.countMineByStatusIn(eq(100L), eq(300L), any())).thenReturn(12L, 2L);
        when(reportRepository.countMyFinalizedReports(300L, 100L, ReportStatus.FINALIZED)).thenReturn(7L);

        MyInspectionsSummaryResponse response = service.getSummary(300L, 100L);

        assertThat(response.participatedCount()).isEqualTo(18L);
        assertThat(response.reviewConfirmedCount()).isEqualTo(12L);
        assertThat(response.inProgressCount()).isEqualTo(2L);
        assertThat(response.issuedReportCount()).isEqualTo(7L);
        verify(companyScopeGuard).requireEffectiveMembership(300L, 100L);
    }

    @Test
    void getSummary_회사없는사용자_FORBIDDEN예외() {
        doThrow(new BusinessException(ErrorCode.FORBIDDEN))
                .when(companyScopeGuard).requireEffectiveMembership(300L, null);

        assertThatThrownBy(() -> service.getSummary(300L, null))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        verify(inspectionRepository, never()).countMine(any(), any());
    }

    // ── getInspections ──

    @Test
    void getInspections_담당자와등록자둘다본인이면역할은INSPECTOR우선() {
        Pageable pageable = PageRequest.of(0, 8);
        Inspection inspection = inspectionWithFacility(
                10L, 1L, "테스트빌딩", 3, 300L, 300L, InspectionStatus.REVIEWED);
        when(inspectionRepository.findMyInspectionsPage(eq(300L), eq(100L), isNull(), eq(pageable)))
                .thenReturn(new PageImpl<>(List.of(inspection), pageable, 1));
        when(defectRepository.countGroupByInspectionId(List.of(10L)))
                .thenReturn(List.of(countProjection(10L, 5L)));

        PageResponse<MyInspectionRowResponse> response = service.getInspections(300L, 100L, "ALL", pageable);

        MyInspectionRowResponse row = response.content().get(0);
        assertThat(row.role()).isEqualTo(MyInspectionRole.INSPECTOR);
        assertThat(row.defectCount()).isEqualTo(5L);
        assertThat(row.facilityName()).isEqualTo("테스트빌딩");
        assertThat(row.roundNo()).isEqualTo(3);
        assertThat(row.status()).isEqualTo(MyInspectionDisplayStatus.REVIEW_DONE);
    }

    @Test
    void getInspections_등록자만본인이면역할은OWNER() {
        Pageable pageable = PageRequest.of(0, 8);
        Inspection inspection = inspectionWithFacility(
                11L, 1L, "테스트빌딩", 1, 999L, 300L, InspectionStatus.CREATED);
        when(inspectionRepository.findMyInspectionsPage(eq(300L), eq(100L), isNull(), eq(pageable)))
                .thenReturn(new PageImpl<>(List.of(inspection), pageable, 1));
        when(defectRepository.countGroupByInspectionId(List.of(11L))).thenReturn(List.of());

        PageResponse<MyInspectionRowResponse> response = service.getInspections(300L, 100L, "ALL", pageable);

        assertThat(response.content().get(0).role()).isEqualTo(MyInspectionRole.OWNER);
        assertThat(response.content().get(0).defectCount()).isZero();
    }

    @Test
    void getInspections_상태매핑_ANALYZED는REVIEW_PENDING() {
        Pageable pageable = PageRequest.of(0, 8);
        Inspection inspection = inspectionWithFacility(
                12L, 1L, "테스트빌딩", 1, 300L, 300L, InspectionStatus.ANALYZED);
        when(inspectionRepository.findMyInspectionsPage(eq(300L), eq(100L), isNull(), eq(pageable)))
                .thenReturn(new PageImpl<>(List.of(inspection), pageable, 1));
        when(defectRepository.countGroupByInspectionId(List.of(12L))).thenReturn(List.of());

        PageResponse<MyInspectionRowResponse> response = service.getInspections(300L, 100L, "ALL", pageable);

        assertThat(response.content().get(0).status()).isEqualTo(MyInspectionDisplayStatus.REVIEW_PENDING);
    }

    @Test
    void getInspections_결과없으면하자레포조회안함() {
        Pageable pageable = PageRequest.of(0, 8);
        when(inspectionRepository.findMyInspectionsPage(eq(300L), eq(100L), isNull(), eq(pageable)))
                .thenReturn(new PageImpl<>(List.of(), pageable, 0));

        PageResponse<MyInspectionRowResponse> response = service.getInspections(300L, 100L, "ALL", pageable);

        assertThat(response.content()).isEmpty();
        verify(defectRepository, never()).countGroupByInspectionId(any());
    }

    @Test
    void getInspections_기간ALL이아니면periodFrom을계산해전달() {
        Pageable pageable = PageRequest.of(0, 8);
        when(inspectionRepository.findMyInspectionsPage(eq(300L), eq(100L), any(LocalDate.class), eq(pageable)))
                .thenReturn(new PageImpl<>(List.of(), pageable, 0));

        service.getInspections(300L, 100L, "1M", pageable);

        verify(inspectionRepository).findMyInspectionsPage(eq(300L), eq(100L), any(LocalDate.class), eq(pageable));
    }

    @Test
    void getInspections_인식불가period값_INVALID_INPUT() {
        Pageable pageable = PageRequest.of(0, 8);

        assertThatThrownBy(() -> service.getInspections(300L, 100L, "2M", pageable))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT));
        verify(inspectionRepository, never()).findMyInspectionsPage(any(), any(), any(), any());
    }

    // ── getReports ──

    @Test
    void getReports_PDF조회실패시예외를던지지않고fileSizeBytesNull() throws IOException {
        Inspection inspection = inspectionWithFacility(
                20L, 2L, "보고서빌딩", 5, 300L, 300L, InspectionStatus.REPORTED);
        Report report = reportOf(50L, 20L, inspection, "/api/reports/50/pdf/abc.pdf");
        when(reportRepository.findMyFinalizedReports(
                eq(300L), eq(100L), eq(ReportStatus.FINALIZED), any(), any()))
                .thenReturn(List.of(report));
        when(defectRepository.findDistinctGradesByInspectionIds(List.of(20L))).thenReturn(List.of());
        when(reportPdfStorage.load(50L, "abc.pdf")).thenThrow(new BusinessException(ErrorCode.FILE_NOT_FOUND));

        List<MyReportRowResponse> result = service.getReports(300L, 100L, "ALL");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).fileSizeBytes()).isNull();
        assertThat(result.get(0).facilityName()).isEqualTo("보고서빌딩");
        assertThat(result.get(0).roundNo()).isEqualTo(5);
        assertThat(result.get(0).pdfUrl()).isEqualTo("/api/reports/50/pdf/abc.pdf");
    }

    @Test
    void getReports_PDF조회성공시크기반환() throws IOException {
        Inspection inspection = inspectionWithFacility(
                21L, 2L, "보고서빌딩", 1, 300L, 300L, InspectionStatus.REPORTED);
        Report report = reportOf(51L, 21L, inspection, "/api/reports/51/pdf/xyz.pdf");
        when(reportRepository.findMyFinalizedReports(
                eq(300L), eq(100L), eq(ReportStatus.FINALIZED), any(), any()))
                .thenReturn(List.of(report));
        when(defectRepository.findDistinctGradesByInspectionIds(List.of(21L))).thenReturn(List.of());
        Resource resource = mockResourceWithLength(1258291L);
        when(reportPdfStorage.load(51L, "xyz.pdf")).thenReturn(resource);

        List<MyReportRowResponse> result = service.getReports(300L, 100L, "ALL");

        assertThat(result.get(0).fileSizeBytes()).isEqualTo(1258291L);
    }

    @Test
    void getReports_pdfUrl없으면PDF저장소호출없이fileSizeBytesNull() {
        Inspection inspection = inspectionWithFacility(
                22L, 2L, "보고서빌딩", 1, 300L, 300L, InspectionStatus.REPORTED);
        Report report = reportOf(52L, 22L, inspection, null);
        when(reportRepository.findMyFinalizedReports(
                eq(300L), eq(100L), eq(ReportStatus.FINALIZED), any(), any()))
                .thenReturn(List.of(report));
        when(defectRepository.findDistinctGradesByInspectionIds(List.of(22L))).thenReturn(List.of());

        List<MyReportRowResponse> result = service.getReports(300L, 100L, "ALL");

        assertThat(result.get(0).fileSizeBytes()).isNull();
        verify(reportPdfStorage, never()).load(any(), any());
    }

    @Test
    void getReports_pdfUrl없으면응답도null() {
        Inspection inspection = inspectionWithFacility(
                25L, 2L, "보고서빌딩", 1, 300L, 300L, InspectionStatus.REPORTED);
        Report report = reportOf(55L, 25L, inspection, null);
        when(reportRepository.findMyFinalizedReports(
                eq(300L), eq(100L), eq(ReportStatus.FINALIZED), any(), any()))
                .thenReturn(List.of(report));
        when(defectRepository.findDistinctGradesByInspectionIds(List.of(25L))).thenReturn(List.of());

        List<MyReportRowResponse> result = service.getReports(300L, 100L, "ALL");

        assertThat(result.get(0).pdfUrl()).isNull();
    }

    @Test
    void getReports_gradeDots_중복제거후심각도순RED_ORANGE_GREEN() {
        Inspection inspection = inspectionWithFacility(
                23L, 2L, "보고서빌딩", 1, 300L, 300L, InspectionStatus.REPORTED);
        Report report = reportOf(53L, 23L, inspection, null);
        when(reportRepository.findMyFinalizedReports(
                eq(300L), eq(100L), eq(ReportStatus.FINALIZED), any(), any()))
                .thenReturn(List.of(report));
        // B/A 는 GREEN 으로 같이 축약되어야 하고, E 는 RED, C 는 ORANGE — 반환 순서는 항상 심각도순.
        when(defectRepository.findDistinctGradesByInspectionIds(List.of(23L)))
                .thenReturn(List.of(
                        gradeProjection(23L, DefectGrade.B),
                        gradeProjection(23L, DefectGrade.A),
                        gradeProjection(23L, DefectGrade.E),
                        gradeProjection(23L, DefectGrade.C)));

        List<MyReportRowResponse> result = service.getReports(300L, 100L, "ALL");

        assertThat(result.get(0).gradeDots()).containsExactly(
                ReportGradeDotColor.RED, ReportGradeDotColor.ORANGE, ReportGradeDotColor.GREEN);
    }

    @Test
    void getReports_하자없으면gradeDots빈배열() {
        Inspection inspection = inspectionWithFacility(
                24L, 2L, "보고서빌딩", 1, 300L, 300L, InspectionStatus.REPORTED);
        Report report = reportOf(54L, 24L, inspection, null);
        when(reportRepository.findMyFinalizedReports(
                eq(300L), eq(100L), eq(ReportStatus.FINALIZED), any(), any()))
                .thenReturn(List.of(report));
        when(defectRepository.findDistinctGradesByInspectionIds(List.of(24L))).thenReturn(List.of());

        List<MyReportRowResponse> result = service.getReports(300L, 100L, "ALL");

        assertThat(result.get(0).gradeDots()).isEmpty();
    }

    @Test
    void getReports_결과없으면등급배치조회안함() {
        when(reportRepository.findMyFinalizedReports(
                eq(300L), eq(100L), eq(ReportStatus.FINALIZED), any(), any()))
                .thenReturn(List.of());

        List<MyReportRowResponse> result = service.getReports(300L, 100L, "ALL");

        assertThat(result).isEmpty();
        verify(defectRepository, never()).findDistinctGradesByInspectionIds(any());
    }

    private static Resource mockResourceWithLength(long length) throws IOException {
        Resource resource = org.mockito.Mockito.mock(Resource.class);
        when(resource.contentLength()).thenReturn(length);
        return resource;
    }
}
