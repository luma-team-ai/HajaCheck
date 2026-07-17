package com.hajacheck.core.ai.service;

import com.hajacheck.core.ai.dto.BriefingStatsRequest;
import com.hajacheck.core.defect.entity.DefectGrade;
import com.hajacheck.core.defect.entity.DefectStatus;
import com.hajacheck.core.defect.repository.DefectRepository;
import com.hajacheck.core.defect.repository.DefectTypeCountProjection;
import com.hajacheck.core.defect.repository.GradeCountProjection;
import com.hajacheck.core.facility.entity.Facility;
import com.hajacheck.core.facility.repository.FacilityRepository;
import com.hajacheck.core.inspection.entity.Inspection;
import com.hajacheck.core.inspection.entity.InspectionStatus;
import com.hajacheck.core.inspection.repository.InspectionRepository;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * AI 주간 브리핑(POST /api/ai/briefing, #248 / HAJA-197) 요청 바디({@code DashboardStats}) 조립.
 *
 * <p>DashboardService(#222, core/dashboard/service)와 동일하게 facility/inspection/defect
 * 레포지토리를 서비스 계층에서 직접 조합한다 — 연관관계 없는 FK 값 컬럼 설계라 core 하위 도메인 간
 * 직접 참조가 기존 선례(DashboardService 클래스 상단 javadoc 참고)와 동일 패턴이다.
 *
 * <p>DashboardService 의 private 헬퍼(ownedFacilityIds/inspectionIdsOf/countInspections 등)는
 * 재사용하지 않고 이 서비스가 독립적으로 조합한다 — #248 handoff 의도적 선택: DashboardService(#222
 * 최근 파일)를 수정하면 리뷰 diff 가 커지므로, 최소한의 조합 로직만 이 서비스에 새로 둔다.
 * 모든 조회는 로그인 사용자(ownerId)가 소유한 facilities.owner_id 범위로만 집계한다
 * (cross-owner IDOR 방지, DashboardService/facility 도메인과 동일 원칙).
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class BriefingStatsService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final Set<InspectionStatus> ANALYZED_OR_LATER_STATUSES =
            EnumSet.of(InspectionStatus.ANALYZED, InspectionStatus.REVIEWED, InspectionStatus.REPORTED);
    private static final Set<InspectionStatus> PENDING_REVIEW_STATUSES = EnumSet.of(InspectionStatus.ANALYZED);
    private static final Set<DefectGrade> CRITICAL_GRADES = EnumSet.of(DefectGrade.D, DefectGrade.E);

    private final FacilityRepository facilityRepository;
    private final InspectionRepository inspectionRepository;
    private final DefectRepository defectRepository;

    public BriefingStatsRequest buildStats(Long ownerId) {
        List<Long> facilityIds = facilityRepository.findByOwnerId(ownerId).stream().map(Facility::getId).toList();
        List<Long> inspectionIds = facilityIds.isEmpty() ? List.of()
                : inspectionRepository.findByFacilityIdIn(facilityIds).stream().map(Inspection::getId).toList();

        LocalDate thisMonthStart = LocalDate.now(KST).withDayOfMonth(1);
        LocalDate nextMonthStart = thisMonthStart.plusMonths(1);

        long totalFacilities = facilityRepository.countByOwnerId(ownerId);
        long monthlyAnalysis = facilityIds.isEmpty() ? 0
                : inspectionRepository.countByFacilityIdInAndStatusInAndInspectionDateRange(
                        facilityIds, ANALYZED_OR_LATER_STATUSES, thisMonthStart, nextMonthStart);
        long pendingReview = facilityIds.isEmpty() ? 0
                : inspectionRepository.countByFacilityIdInAndStatusIn(facilityIds, PENDING_REVIEW_STATUSES);
        long pendingAction = inspectionIds.isEmpty() ? 0
                : defectRepository.countByInspectionIdInAndStatusAndDeletedFalse(
                        inspectionIds, DefectStatus.ACTION_PENDING);

        LocalDate thisWeekStart = LocalDate.now(KST).with(DayOfWeek.MONDAY);
        LocalDate nextWeekStart = thisWeekStart.plusWeeks(1);
        LocalDate lastWeekStart = thisWeekStart.minusWeeks(1);
        long thisWeekDefects = countWeeklyDefects(inspectionIds, thisWeekStart, nextWeekStart);
        long lastWeekDefects = countWeeklyDefects(inspectionIds, lastWeekStart, thisWeekStart);

        Map<String, Long> gradeDistribution = gradeDistribution(inspectionIds);
        long criticalDefects = CRITICAL_GRADES.stream()
                .mapToLong(grade -> gradeDistribution.getOrDefault(grade.name(), 0L))
                .sum();

        String topDefectType = topDefectType(inspectionIds);

        return new BriefingStatsRequest(
                totalFacilities, monthlyAnalysis, pendingReview, pendingAction,
                thisWeekDefects, lastWeekDefects, topDefectType, criticalDefects, gradeDistribution);
    }

    /**
     * [from, to) 반열림 구간(KST 자정 기준) 집계 — {@code to} 는 다음 주 경계(exclusive)를 그대로
     * 넘긴다. 과거 "-1ns" 트릭(BETWEEN 양끝 포함을 흉내)은 PG timestamp 가 마이크로초 정밀도라
     * .999999999 가 다음 자정으로 반올림되어 사실상 양끝 포함이 되고, 주 경계 자정 하자가 이번주·
     * 지난주 양쪽에 중복 집계되는 결함이 있었다(리뷰 P1 픽스) — 명시적 반열림 쿼리로 대체했다.
     */
    private long countWeeklyDefects(List<Long> inspectionIds, LocalDate from, LocalDate to) {
        if (inspectionIds.isEmpty()) {
            return 0;
        }
        LocalDateTime fromDt = from.atStartOfDay();
        LocalDateTime toDt = to.atStartOfDay();
        return defectRepository.countByInspectionIdInAndDeletedFalseAndCreatedAtRange(inspectionIds, fromDt, toDt);
    }

    private Map<String, Long> gradeDistribution(List<Long> inspectionIds) {
        Map<String, Long> distribution = new LinkedHashMap<>();
        for (DefectGrade grade : DefectGrade.values()) {
            distribution.put(grade.name(), 0L);
        }
        if (inspectionIds.isEmpty()) {
            return distribution;
        }
        List<GradeCountProjection> counts = defectRepository.countGroupByGrade(inspectionIds);
        for (GradeCountProjection projection : counts) {
            distribution.put(projection.getGrade().name(), projection.getCnt());
        }
        return distribution;
    }

    private String topDefectType(List<Long> inspectionIds) {
        if (inspectionIds.isEmpty()) {
            return "";
        }
        List<DefectTypeCountProjection> counts = defectRepository.countGroupByTypeOrderByCntDesc(inspectionIds);
        if (counts.isEmpty()) {
            return "";
        }
        return counts.get(0).getType().label();
    }
}
