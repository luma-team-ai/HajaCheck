package com.hajacheck.core.dashboard.service;

import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.repository.UserRepository;
import com.hajacheck.core.dashboard.dto.DashboardSummaryResponse;
import com.hajacheck.core.dashboard.dto.GradeDistributionResponse;
import com.hajacheck.core.dashboard.dto.PendingPriorityResponse;
import com.hajacheck.core.dashboard.dto.RecentInspectionResponse;
import com.hajacheck.core.defect.entity.Defect;
import com.hajacheck.core.defect.entity.DefectGrade;
import com.hajacheck.core.defect.entity.DefectStatus;
import com.hajacheck.core.defect.repository.DefectRepository;
import com.hajacheck.core.defect.repository.GradeCountProjection;
import com.hajacheck.core.defect.repository.InspectionDefectCountProjection;
import com.hajacheck.core.facility.entity.Facility;
import com.hajacheck.core.facility.repository.FacilityRepository;
import com.hajacheck.core.inspection.entity.Inspection;
import com.hajacheck.core.inspection.entity.InspectionStatus;
import com.hajacheck.core.inspection.repository.InspectionRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 대시보드 개요 집계(HAJA-17, dev-03-01) — 모든 조회는 로그인 사용자(ownerId)가 소유한
 * facilities.owner_id 범위로만 집계한다(cross-owner IDOR 방지, facility 도메인과 동일 원칙).
 *
 * <p>도메인 간 결합: facility/inspection/defect 는 core 패키지 내 서로 다른 하위 도메인이지만,
 * 연관관계 없는 FK 값 컬럼 설계(§0 "도메인 간 직접 의존 금지"는 auth/core/counsel/admin 최상위
 * 경계 기준)라 대시보드 서비스가 각 Repository 를 직접 조합한다 — MembershipService 가
 * auth.repository 를 직접 참조하는 기존 선례와 동일한 패턴.
 *
 * <p>changeRate(전월 대비 증감률) 계산 근거는 각 DTO(DashboardSummaryResponse) 문서 참고 —
 * 스냅샷 테이블이 없어 시각 기준 근사치로 계산한다.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class DashboardService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final Set<InspectionStatus> ANALYZED_STATUSES =
            EnumSet.of(InspectionStatus.ANALYZED, InspectionStatus.REVIEWED, InspectionStatus.REPORTED);
    private static final Set<InspectionStatus> PENDING_REVIEW_STATUSES = EnumSet.of(InspectionStatus.ANALYZED);
    private static final int RECENT_LIMIT = 10;
    private static final int PENDING_PRIORITY_LIMIT = 10;

    private final FacilityRepository facilityRepository;
    private final InspectionRepository inspectionRepository;
    private final DefectRepository defectRepository;
    private final UserRepository userRepository;

    public DashboardSummaryResponse getSummary(Long ownerId) {
        List<Long> facilityIds = ownedFacilityIds(ownerId);
        List<Long> inspectionIds = inspectionIdsOf(facilityIds);

        LocalDate thisMonthStart = LocalDate.now(KST).withDayOfMonth(1);
        LocalDate nextMonthStart = thisMonthStart.plusMonths(1);
        LocalDate lastMonthStart = thisMonthStart.minusMonths(1);

        long totalFacilities = facilityRepository.countByOwnerId(ownerId);
        long totalFacilitiesLastMonth =
                facilityRepository.countByOwnerIdAndCreatedAtBefore(ownerId, thisMonthStart.atStartOfDay());

        long monthlyAnalyzed = countInspections(facilityIds, ANALYZED_STATUSES, thisMonthStart, nextMonthStart);
        long monthlyAnalyzedLastMonth =
                countInspections(facilityIds, ANALYZED_STATUSES, lastMonthStart, thisMonthStart);

        long pendingReview = facilityIds.isEmpty() ? 0
                : inspectionRepository.countByFacilityIdInAndStatusIn(facilityIds, PENDING_REVIEW_STATUSES);
        long pendingReviewThisMonth =
                countInspections(facilityIds, PENDING_REVIEW_STATUSES, thisMonthStart, nextMonthStart);
        long pendingReviewLastMonth =
                countInspections(facilityIds, PENDING_REVIEW_STATUSES, lastMonthStart, thisMonthStart);

        LocalDateTime thisMonthStartDt = thisMonthStart.atStartOfDay();
        LocalDateTime nextMonthStartDt = nextMonthStart.atStartOfDay();
        LocalDateTime lastMonthStartDt = lastMonthStart.atStartOfDay();

        long pendingAction = inspectionIds.isEmpty() ? 0
                : defectRepository.countByInspectionIdInAndStatusAndDeletedFalse(
                        inspectionIds, DefectStatus.ACTION_PENDING);
        long pendingActionThisMonth = countPendingActionDefects(inspectionIds, thisMonthStartDt, nextMonthStartDt);
        long pendingActionLastMonth = countPendingActionDefects(inspectionIds, lastMonthStartDt, thisMonthStartDt);

        return new DashboardSummaryResponse(
                totalFacilities,
                DashboardSummaryResponse.changeRate(totalFacilities, totalFacilitiesLastMonth),
                monthlyAnalyzed,
                DashboardSummaryResponse.changeRate(monthlyAnalyzed, monthlyAnalyzedLastMonth),
                pendingReview,
                DashboardSummaryResponse.changeRate(pendingReviewThisMonth, pendingReviewLastMonth),
                pendingAction,
                DashboardSummaryResponse.changeRate(pendingActionThisMonth, pendingActionLastMonth));
    }

    public List<GradeDistributionResponse> getGradeDistribution(Long ownerId) {
        List<Long> inspectionIds = inspectionIdsOf(ownedFacilityIds(ownerId));
        List<GradeCountProjection> counts =
                inspectionIds.isEmpty() ? List.of() : defectRepository.countGroupByGrade(inspectionIds);

        Map<DefectGrade, Long> countByGrade = new HashMap<>();
        long total = 0;
        for (GradeCountProjection projection : counts) {
            countByGrade.put(projection.getGrade(), projection.getCnt());
            total += projection.getCnt();
        }

        long finalTotal = total;
        return Arrays.stream(DefectGrade.values())
                .map(grade -> GradeDistributionResponse.of(grade, countByGrade.getOrDefault(grade, 0L), finalTotal))
                .toList();
    }

    public List<PendingPriorityResponse> getPendingPriority(Long ownerId) {
        List<Facility> facilities = facilityRepository.findByOwnerId(ownerId);
        Map<Long, String> facilityNameById = toFacilityNameMap(facilities);
        List<Long> facilityIds = facilities.stream().map(Facility::getId).toList();

        List<Inspection> inspections = inspectionsOf(facilityIds);
        Map<Long, Long> facilityIdByInspectionId = toFacilityIdMap(inspections);
        List<Long> inspectionIds = inspections.stream().map(Inspection::getId).toList();
        if (inspectionIds.isEmpty()) {
            return List.of();
        }

        List<Defect> defects = defectRepository.findPendingPriorityDefects(
                inspectionIds, DefectStatus.ACTION_PENDING, PageRequest.of(0, PENDING_PRIORITY_LIMIT));

        return defects.stream()
                .map(defect -> {
                    Long facilityId = facilityIdByInspectionId.get(defect.getInspectionId());
                    String facilityName = facilityNameById.getOrDefault(facilityId, "-");
                    return PendingPriorityResponse.from(defect, facilityName);
                })
                .toList();
    }

    public List<RecentInspectionResponse> getRecentInspections(Long ownerId) {
        List<Facility> facilities = facilityRepository.findByOwnerId(ownerId);
        Map<Long, String> facilityNameById = toFacilityNameMap(facilities);
        List<Long> facilityIds = facilities.stream().map(Facility::getId).toList();
        if (facilityIds.isEmpty()) {
            return List.of();
        }

        List<Inspection> recent =
                inspectionRepository.findRecentByFacilityIds(facilityIds, PageRequest.of(0, RECENT_LIMIT));
        if (recent.isEmpty()) {
            return List.of();
        }

        List<Long> inspectionIds = recent.stream().map(Inspection::getId).toList();
        Map<Long, Long> defectCountByInspectionId = defectRepository.countGroupByInspectionId(inspectionIds).stream()
                .collect(Collectors.toMap(
                        InspectionDefectCountProjection::getInspectionId,
                        InspectionDefectCountProjection::getCnt));

        List<Long> creatorIds = recent.stream().map(Inspection::getCreatedBy).distinct().toList();
        Map<Long, String> creatorNameById = userRepository.findAllById(creatorIds).stream()
                .collect(Collectors.toMap(User::getId, User::getName));

        return recent.stream()
                .map(inspection -> RecentInspectionResponse.from(
                        inspection,
                        facilityNameById.getOrDefault(inspection.getFacilityId(), "-"),
                        creatorNameById.getOrDefault(inspection.getCreatedBy(), "-"),
                        defectCountByInspectionId.getOrDefault(inspection.getId(), 0L)))
                .toList();
    }

    private List<Long> ownedFacilityIds(Long ownerId) {
        return facilityRepository.findByOwnerId(ownerId).stream().map(Facility::getId).toList();
    }

    private List<Inspection> inspectionsOf(List<Long> facilityIds) {
        return facilityIds.isEmpty() ? List.of() : inspectionRepository.findByFacilityIdIn(facilityIds);
    }

    private List<Long> inspectionIdsOf(List<Long> facilityIds) {
        return inspectionsOf(facilityIds).stream().map(Inspection::getId).toList();
    }

    private long countInspections(List<Long> facilityIds, Set<InspectionStatus> statuses,
                                   LocalDate from, LocalDate to) {
        if (facilityIds.isEmpty()) {
            return 0;
        }
        return inspectionRepository.countByFacilityIdInAndStatusInAndInspectionDateRange(
                facilityIds, statuses, from, to);
    }

    private long countPendingActionDefects(List<Long> inspectionIds, LocalDateTime from, LocalDateTime to) {
        if (inspectionIds.isEmpty()) {
            return 0;
        }
        return defectRepository.countByInspectionIdInAndStatusAndDeletedFalseAndCreatedAtRange(
                inspectionIds, DefectStatus.ACTION_PENDING, from, to);
    }

    private Map<Long, String> toFacilityNameMap(List<Facility> facilities) {
        Map<Long, String> map = new HashMap<>();
        for (Facility facility : facilities) {
            map.put(facility.getId(), facility.getName());
        }
        return map;
    }

    private Map<Long, Long> toFacilityIdMap(List<Inspection> inspections) {
        Map<Long, Long> map = new HashMap<>();
        for (Inspection inspection : inspections) {
            map.put(inspection.getId(), inspection.getFacilityId());
        }
        return map;
    }
}
