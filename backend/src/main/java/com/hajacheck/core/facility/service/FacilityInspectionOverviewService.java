package com.hajacheck.core.facility.service;

import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.repository.UserRepository;
import com.hajacheck.auth.service.CompanyScopeGuard;
import com.hajacheck.core.defect.entity.DefectGrade;
import com.hajacheck.core.defect.entity.DefectStatus;
import com.hajacheck.core.defect.repository.DefectRepository;
import com.hajacheck.core.defect.repository.InspectionGradeCountProjection;
import com.hajacheck.core.facility.dto.FacilityComparisonResponse;
import com.hajacheck.core.facility.dto.FacilityComparisonResponse.ComparisonKpi;
import com.hajacheck.core.facility.dto.FacilityInspectionOverviewResponse;
import com.hajacheck.core.facility.dto.FacilityInspectionOverviewResponse.GradeCount;
import com.hajacheck.core.facility.dto.FacilityInspectionOverviewResponse.HistoryItem;
import com.hajacheck.core.facility.repository.FacilityRepository;
import com.hajacheck.core.inspection.entity.Inspection;
import com.hajacheck.core.inspection.entity.InspectionStatus;
import com.hajacheck.core.inspection.repository.InspectionRepository;
import com.hajacheck.core.media.entity.MediaPurpose;
import com.hajacheck.core.media.repository.MediaRepository;
import com.hajacheck.core.media.repository.MediaRepository.InspectionMediaCountProjection;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 시설물 상세 "점검 이력" 탭 집계(#1359/HAJA-616) — 프론트 로컬 목(useFacilityInspectionOverview)을
 * 대체하는 실 API. DB 마이그레이션 없이 기존 inspections/defects/media 컬럼만으로 계산한다(사전 조사
 * 완료, HAJA-616 이슈 본문 참고).
 *
 * <p>changeNote(최신 회차의 "이전 회차 대비" 변화 메모)는 {@link FacilityComparisonService}의
 * previous_defect_id 기반 classify/buildKpis 로직을 그대로 재사용한다 — 새 분류 로직을 만들지 않는다.
 * 목데이터의 "진행성 균열 N건"처럼 특정 하자유형(균열)을 짚어 말하는 문구는 이번 범위에서 재현하지
 * 않고, buildKpis가 이미 계산하는 신규/진행성(악화+재발생) 총건수만 사용한다(설계 단순화 — 특정
 * 유형 강조 문구가 실제 요구사항인지는 후속 확인 필요).
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class FacilityInspectionOverviewService {

    // 회차 간 비교(compare())가 요구하는 "분석 완료 이후" 상태만 changeNote 계산 대상으로 삼는다
    // (FacilityComparisonService.COMPARABLE_STATUSES와 동일 집합 — 아직 분석 전인 회차를 비교하면
    // INSPECTION_COMPARISON_ROUND_NOT_ANALYZED로 예외가 나므로, 여기서는 예외 대신 changeNote를
    // 조용히 생략한다: "아직 비교 불가"는 오류가 아니라 정상적인 중간 상태다).
    private static final Set<InspectionStatus> COMPARABLE_STATUSES =
            EnumSet.of(InspectionStatus.ANALYZED, InspectionStatus.REVIEWED, InspectionStatus.REPORTED);

    private final FacilityRepository facilityRepository;
    private final InspectionRepository inspectionRepository;
    private final DefectRepository defectRepository;
    private final MediaRepository mediaRepository;
    private final UserRepository userRepository;
    private final CompanyScopeGuard companyScopeGuard;
    private final FacilityComparisonService facilityComparisonService;

    public FacilityInspectionOverviewResponse get(Long userId, Long companyId, Long facilityId) {
        companyScopeGuard.requireEffectiveMembership(userId, companyId);
        facilityRepository.findByIdAndCompanyId(facilityId, companyId)
                .orElseThrow(() -> new BusinessException(ErrorCode.FACILITY_NOT_FOUND));

        List<Inspection> inspections = inspectionRepository.findByFacilityIdOrderByRoundNoDesc(facilityId);
        if (inspections.isEmpty()) {
            return new FacilityInspectionOverviewResponse(null, 0, 0, 0, List.of());
        }

        List<Long> inspectionIds = inspections.stream().map(Inspection::getId).toList();

        // #1641 P2 — 사진 수 배지는 조치 후 사진(DEFECT_ACTION)을 세면 안 된다(원본 촬영사진만).
        Map<Long, Long> imageCountByInspectionId = mediaRepository
                .countGroupByInspectionIdsAndPurpose(inspectionIds, MediaPurpose.INSPECTION_SOURCE).stream()
                .collect(Collectors.toMap(
                        InspectionMediaCountProjection::getInspectionId, InspectionMediaCountProjection::getCnt));

        Map<Long, List<InspectionGradeCountProjection>> gradeBreakdownByInspectionId =
                defectRepository.countGroupByInspectionIdAndGrade(inspectionIds).stream()
                        .collect(Collectors.groupingBy(InspectionGradeCountProjection::getInspectionId));

        long cumulativeDefectCount = defectRepository.countGroupByInspectionId(inspectionIds).stream()
                .mapToLong(p -> p.getCnt())
                .sum();
        long unresolvedDefectCount = defectRepository.countByInspectionIdInAndDeletedFalseAndStatusNot(
                inspectionIds, DefectStatus.RESOLVED);

        Set<Long> inspectorIds = inspections.stream().map(Inspection::getAssignedInspectorId)
                .collect(Collectors.toSet());
        Map<Long, String> inspectorNameById = userRepository.findAllById(inspectorIds).stream()
                .collect(Collectors.toMap(User::getId, User::getName));

        Inspection latest = inspections.get(0);
        DefectGrade overallGrade = worstGrade(gradeBreakdownByInspectionId.getOrDefault(latest.getId(), List.of()));
        String latestChangeNote = inspections.size() < 2
                ? null
                : buildChangeNote(userId, companyId, facilityId, inspections.get(1), latest);
        // #1549 — 프론트는 최신 회차(index 0)만 미리보기 2장을 보여준다(changeNote와 동일 관례) —
        // 나머지 회차까지 조회하면 회차 수만큼 불필요한 쿼리가 늘어난다.
        // #1641 P2 — 미리보기 2장에도 조치 후 사진이 섞이면 안 된다(원본 촬영사진만).
        List<String> latestThumbnailUrls = mediaRepository
                .findTop2ByInspectionIdAndPurposeOrderByIdAsc(latest.getId(), MediaPurpose.INSPECTION_SOURCE)
                .stream()
                .map(media -> "/api/media/" + media.getId() + "/thumbnail")
                .toList();

        List<HistoryItem> history = inspections.stream()
                .map(inspection -> new HistoryItem(
                        inspection.getId(),
                        inspection.getRoundNo(),
                        inspection.getInspectionDate(),
                        inspectorNameById.getOrDefault(inspection.getAssignedInspectorId(), "-"),
                        mapStatusLabel(inspection.getStatus()),
                        imageCountByInspectionId.getOrDefault(inspection.getId(), 0L),
                        toGradeCounts(gradeBreakdownByInspectionId.getOrDefault(inspection.getId(), List.of())),
                        inspection.getId().equals(latest.getId()) ? latestChangeNote : null,
                        inspection.getId().equals(latest.getId()) ? latestThumbnailUrls : List.of()))
                .toList();

        return new FacilityInspectionOverviewResponse(
                overallGrade, inspections.size(), cumulativeDefectCount, unresolvedDefectCount, history);
    }

    private String buildChangeNote(
            Long userId, Long companyId, Long facilityId, Inspection previous, Inspection latest) {
        if (!COMPARABLE_STATUSES.contains(previous.getStatus()) || !COMPARABLE_STATUSES.contains(latest.getStatus())) {
            return null;
        }
        FacilityComparisonResponse comparison = facilityComparisonService.compare(
                userId, companyId, facilityId, previous.getRoundNo(), latest.getRoundNo());
        long newCount = kpiValue(comparison.kpis(), "newDefects");
        long worseningCount = kpiValue(comparison.kpis(), "worsening");
        if (newCount == 0 && worseningCount == 0) {
            return null;
        }
        return "이전 회차 대비 신규 하자 +" + newCount + "건 · 진행성 " + worseningCount + "건";
    }

    private static long kpiValue(List<ComparisonKpi> kpis, String key) {
        return kpis.stream()
                .filter(kpi -> kpi.key().equals(key))
                .findFirst()
                .map(ComparisonKpi::value)
                .orElse(0L);
    }

    private static DefectGrade worstGrade(List<InspectionGradeCountProjection> gradeCounts) {
        return gradeCounts.stream()
                .map(InspectionGradeCountProjection::getGrade)
                .max(Comparator.comparingInt(Enum::ordinal))
                .orElse(null);
    }

    private static List<GradeCount> toGradeCounts(List<InspectionGradeCountProjection> gradeCounts) {
        return gradeCounts.stream()
                .sorted(Comparator.comparingInt((InspectionGradeCountProjection p) -> p.getGrade().ordinal())
                        .reversed())
                .map(p -> new GradeCount(p.getGrade(), p.getCnt()))
                .toList();
    }

    private static String mapStatusLabel(InspectionStatus status) {
        return switch (status) {
            case REPORTED -> "완료";
            case REVIEWED -> "검수완료";
            case ANALYZED, CREATED, UPLOADING, ANALYZING -> "진행중";
        };
    }
}
