package com.hajacheck.core.statistics.service;

import com.hajacheck.auth.service.CompanyScopeGuard;
import com.hajacheck.core.defect.entity.Defect;
import com.hajacheck.core.defect.entity.DefectGrade;
import com.hajacheck.core.defect.entity.DefectStatus;
import com.hajacheck.core.defect.entity.DefectType;
import com.hajacheck.core.defect.repository.DefectRepository;
import com.hajacheck.core.defect.repository.DefectStatusCountProjection;
import com.hajacheck.core.defect.repository.GradeCountProjection;
import com.hajacheck.core.defect.repository.InspectionDefectCountProjection;
import com.hajacheck.core.defect.repository.InspectionGradeCountProjection;
import com.hajacheck.core.facility.entity.Facility;
import com.hajacheck.core.facility.repository.FacilityRepository;
import com.hajacheck.core.inspection.entity.Inspection;
import com.hajacheck.core.inspection.repository.InspectionRepository;
import com.hajacheck.core.statistics.dto.DefectTypeDistributionResponse;
import com.hajacheck.core.statistics.dto.FacilitySummaryResponse;
import com.hajacheck.core.statistics.dto.FacilityTypeHeatmapResponse;
import com.hajacheck.core.statistics.dto.MonthlyDefectTrendResponse;
import com.hajacheck.core.statistics.dto.StatisticsGradeDistributionResponse;
import com.hajacheck.core.statistics.dto.StatisticsKpiSummaryResponse;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class StatisticsService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final int DEFAULT_MONTHS = 6;
    private static final Set<DefectType> FRONTEND_CONTRACT_TYPES =
            EnumSet.of(DefectType.CRACK, DefectType.SPALLING, DefectType.REBAR_EXPOSURE);

    private final CompanyScopeGuard companyScopeGuard;
    private final FacilityRepository facilityRepository;
    private final InspectionRepository inspectionRepository;
    private final DefectRepository defectRepository;

    public StatisticsKpiSummaryResponse getSummary(Long userId, Long companyId, String period, String facilityId) {
        StatisticsScope scope = scope(userId, companyId, period, facilityId);
        List<Long> currentInspectionIds = scope.currentInspections().stream().map(Inspection::getId).toList();
        List<Long> previousInspectionIds = scope.previousInspections().stream().map(Inspection::getId).toList();

        SummaryValues current = summaryValues(currentInspectionIds);
        SummaryValues previous = summaryValues(previousInspectionIds);

        return new StatisticsKpiSummaryResponse(
                current.totalDefects(),
                changeRate(current.totalDefects(), previous.totalDefects()),
                current.avgResolutionDays(),
                changeRate(current.avgResolutionDays(), previous.avgResolutionDays()),
                current.actionCompletionRate(),
                changeRate(current.actionCompletionRate(), previous.actionCompletionRate()),
                current.progressingDefects(),
                changeRate(current.progressingDefects(), previous.progressingDefects()));
    }

    public List<MonthlyDefectTrendResponse> getMonthlyTrend(
            Long userId, Long companyId, String period, String facilityId) {
        StatisticsScope scope = scope(userId, companyId, period, facilityId);
        Map<Long, Long> countByInspectionId = countByInspectionId(scope.currentInspectionIds());
        Map<YearMonth, Long> countByMonth = new HashMap<>();
        for (Inspection inspection : scope.currentInspections()) {
            YearMonth month = YearMonth.from(inspection.getInspectionDate());
            countByMonth.merge(month, countByInspectionId.getOrDefault(inspection.getId(), 0L), Long::sum);
        }
        return scope.months().stream()
                .map(month -> new MonthlyDefectTrendResponse(month.toString(), countByMonth.getOrDefault(month, 0L)))
                .toList();
    }

    public List<DefectTypeDistributionResponse> getDefectTypeDistribution(
            Long userId, Long companyId, String period, String facilityId) {
        StatisticsScope scope = scope(userId, companyId, period, facilityId);
        if (scope.currentInspectionIds().isEmpty()) {
            return List.of();
        }
        return defectRepository.countGroupByType(scope.currentInspectionIds()).stream()
                .filter(row -> FRONTEND_CONTRACT_TYPES.contains(row.getType()))
                .map(row -> new DefectTypeDistributionResponse(row.getType().label(), row.getCnt()))
                .toList();
    }

    public List<StatisticsGradeDistributionResponse> getGradeDistribution(
            Long userId, Long companyId, String period, String facilityId) {
        StatisticsScope scope = scope(userId, companyId, period, facilityId);
        if (scope.currentInspectionIds().isEmpty()) {
            return List.of();
        }
        List<GradeCountProjection> counts = defectRepository.countGroupByGrade(scope.currentInspectionIds());
        long total = counts.stream().mapToLong(GradeCountProjection::getCnt).sum();
        if (total == 0) {
            return List.of();
        }
        Map<DefectGrade, Long> countByGrade = counts.stream()
                .collect(Collectors.toMap(GradeCountProjection::getGrade, GradeCountProjection::getCnt));
        return Arrays.stream(DefectGrade.values())
                .map(grade -> new StatisticsGradeDistributionResponse(
                        grade.name(), roundPercent(countByGrade.getOrDefault(grade, 0L), total)))
                .toList();
    }

    public List<FacilityTypeHeatmapResponse> getFacilityTypeHeatmap(
            Long userId, Long companyId, String period, String facilityId) {
        StatisticsScope scope = scope(userId, companyId, period, facilityId);
        Map<Long, Long> countByInspectionId = countByInspectionId(scope.currentInspectionIds());
        Map<Long, Facility> facilityById = scope.facilities().stream()
                .collect(Collectors.toMap(Facility::getId, facility -> facility));
        Map<String, Long> countByCategoryMonth = new HashMap<>();
        for (Inspection inspection : scope.currentInspections()) {
            Facility facility = facilityById.get(inspection.getFacilityId());
            String key = categoryOf(facility == null ? null : facility.getType())
                    + "|" + YearMonth.from(inspection.getInspectionDate());
            countByCategoryMonth.merge(key, countByInspectionId.getOrDefault(inspection.getId(), 0L), Long::sum);
        }

        return countByCategoryMonth.entrySet().stream()
                .map(entry -> {
                    String[] parts = entry.getKey().split("\\|", 2);
                    return new FacilityTypeHeatmapResponse(parts[0], parts[1], entry.getValue());
                })
                .sorted(java.util.Comparator
                        .comparing(FacilityTypeHeatmapResponse::facilityTypeCategory)
                        .thenComparing(FacilityTypeHeatmapResponse::month))
                .toList();
    }

    public List<FacilitySummaryResponse> getFacilitySummary(
            Long userId, Long companyId, String period, String facilityId) {
        StatisticsScope scope = scope(userId, companyId, period, facilityId);
        Map<Long, Long> currentDefectCountByInspectionId = countByInspectionId(scope.currentInspectionIds());
        Map<Long, Long> totalByFacilityId = new HashMap<>();
        for (Inspection inspection : scope.currentInspections()) {
            totalByFacilityId.merge(
                    inspection.getFacilityId(), currentDefectCountByInspectionId.getOrDefault(inspection.getId(), 0L),
                    Long::sum);
        }

        Map<Long, LocalDate> lastInspectedByFacilityId = scope.allInspections().stream()
                .collect(Collectors.toMap(
                        Inspection::getFacilityId,
                        Inspection::getInspectionDate,
                        (left, right) -> left.isAfter(right) ? left : right));

        Map<Long, DefectGrade> highestGradeByFacilityId = highestGradeByFacility(scope.currentInspections());

        return scope.facilities().stream()
                .map(facility -> new FacilitySummaryResponse(
                        facility.getId(),
                        facility.getName(),
                        facility.getType(),
                        totalByFacilityId.getOrDefault(facility.getId(), 0L),
                        highestGradeByFacilityId.containsKey(facility.getId())
                                ? highestGradeByFacilityId.get(facility.getId()).name() : null,
                        lastInspectedByFacilityId.containsKey(facility.getId())
                                ? lastInspectedByFacilityId.get(facility.getId()).toString() : null))
                .toList();
    }

    private StatisticsScope scope(Long userId, Long companyId, String period, String facilityIdParam) {
        companyScopeGuard.requireEffectiveMembership(userId, companyId);
        List<Facility> facilities = resolveFacilities(companyId, facilityIdParam);
        List<Long> facilityIds = facilities.stream().map(Facility::getId).toList();
        List<Inspection> inspections = facilityIds.isEmpty()
                ? List.of()
                : inspectionRepository.findByFacilityIdIn(facilityIds);

        int months = monthsOf(period);
        YearMonth endMonth = YearMonth.now(KST).plusMonths(1);
        YearMonth startMonth = endMonth.minusMonths(months);
        YearMonth previousStartMonth = startMonth.minusMonths(months);

        List<Inspection> currentInspections = inspections.stream()
                .filter(inspection -> inRange(inspection.getInspectionDate(), startMonth, endMonth))
                .toList();
        List<Inspection> previousInspections = inspections.stream()
                .filter(inspection -> inRange(inspection.getInspectionDate(), previousStartMonth, startMonth))
                .toList();
        List<YearMonth> monthLabels = java.util.stream.IntStream.range(0, months)
                .mapToObj(startMonth::plusMonths)
                .toList();

        return new StatisticsScope(facilities, inspections, currentInspections, previousInspections, monthLabels);
    }

    private List<Facility> resolveFacilities(Long companyId, String facilityIdParam) {
        if (facilityIdParam == null || facilityIdParam.isBlank() || "all".equalsIgnoreCase(facilityIdParam)) {
            return facilityRepository.findByCompanyId(companyId);
        }
        try {
            Long facilityId = Long.valueOf(facilityIdParam);
            return List.of(facilityRepository.findByIdAndCompanyId(facilityId, companyId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.FACILITY_NOT_FOUND)));
        } catch (NumberFormatException ex) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
    }

    private SummaryValues summaryValues(List<Long> inspectionIds) {
        if (inspectionIds.isEmpty()) {
            return SummaryValues.zero();
        }
        long total = countByInspectionId(inspectionIds).values().stream().mapToLong(Long::longValue).sum();
        Map<DefectStatus, Long> countByStatus = new EnumMap<>(DefectStatus.class);
        for (DefectStatusCountProjection projection : defectRepository.countGroupByStatus(inspectionIds)) {
            countByStatus.put(projection.getStatus(), projection.getCnt());
        }
        long resolved = countByStatus.getOrDefault(DefectStatus.RESOLVED, 0L);
        long progressing = total - resolved;
        double completionRate = total == 0 ? 0.0 : roundOneDecimal((resolved * 100.0) / total);
        double avgResolutionDays = averageResolutionDays(inspectionIds);
        return new SummaryValues(total, progressing, completionRate, avgResolutionDays);
    }

    private double averageResolutionDays(List<Long> inspectionIds) {
        List<Defect> resolved =
                defectRepository.findResolvedWithActionDateByInspectionIds(inspectionIds, DefectStatus.RESOLVED);
        if (resolved.isEmpty()) {
            return 0.0;
        }
        long totalDays = 0;
        long counted = 0;
        for (Defect defect : resolved) {
            if (defect.getCreatedAt() == null || defect.getActionDate() == null) {
                continue;
            }
            totalDays += Math.max(0,
                    ChronoUnit.DAYS.between(defect.getCreatedAt().toLocalDate(), defect.getActionDate()));
            counted++;
        }
        return counted == 0 ? 0.0 : roundOneDecimal(totalDays / (double) counted);
    }

    private Map<Long, Long> countByInspectionId(List<Long> inspectionIds) {
        if (inspectionIds.isEmpty()) {
            return Map.of();
        }
        return defectRepository.countGroupByInspectionId(inspectionIds).stream()
                .collect(Collectors.toMap(
                        InspectionDefectCountProjection::getInspectionId,
                        InspectionDefectCountProjection::getCnt));
    }

    private Map<Long, DefectGrade> highestGradeByFacility(List<Inspection> inspections) {
        List<Long> inspectionIds = inspections.stream().map(Inspection::getId).toList();
        if (inspectionIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Long> facilityIdByInspectionId = inspections.stream()
                .collect(Collectors.toMap(Inspection::getId, Inspection::getFacilityId));
        Map<Long, DefectGrade> highestByInspectionId = new HashMap<>();
        for (InspectionGradeCountProjection projection :
                defectRepository.countGroupByInspectionIdAndGrade(inspectionIds)) {
            highestByInspectionId.merge(
                    projection.getInspectionId(), projection.getGrade(), StatisticsService::worseGrade);
        }
        Map<Long, DefectGrade> result = new HashMap<>();
        for (Map.Entry<Long, DefectGrade> entry : highestByInspectionId.entrySet()) {
            Long facilityId = facilityIdByInspectionId.get(entry.getKey());
            if (facilityId == null) {
                continue;
            }
            result.merge(
                    facilityId, entry.getValue(), StatisticsService::worseGrade);
        }
        return result;
    }

    private static boolean inRange(LocalDate date, YearMonth fromInclusive, YearMonth toExclusive) {
        LocalDate from = fromInclusive.atDay(1);
        LocalDate to = toExclusive.atDay(1);
        return !date.isBefore(from) && date.isBefore(to);
    }

    private static int monthsOf(String period) {
        if ("3m".equalsIgnoreCase(period)) {
            return 3;
        }
        if ("1y".equalsIgnoreCase(period)) {
            return 12;
        }
        return DEFAULT_MONTHS;
    }

    private static DefectGrade worseGrade(DefectGrade left, DefectGrade right) {
        return left.ordinal() >= right.ordinal() ? left : right;
    }

    private static String categoryOf(String type) {
        if (type == null || type.isBlank()) {
            return "기타";
        }
        String trimmed = type.trim();
        if (trimmed.startsWith("건물")) {
            return "건물";
        }
        if (trimmed.startsWith("교량")) {
            return "교량";
        }
        if (trimmed.startsWith("도로")) {
            return "도로";
        }
        return "기타";
    }

    private static double roundPercent(long count, long total) {
        return roundOneDecimal((count * 100.0) / total);
    }

    private static double changeRate(double current, double previous) {
        if (previous == 0.0) {
            return current == 0.0 ? 0.0 : 100.0;
        }
        return roundOneDecimal(((current - previous) / previous) * 100.0);
    }

    private static double roundOneDecimal(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private record StatisticsScope(
            List<Facility> facilities,
            List<Inspection> allInspections,
            List<Inspection> currentInspections,
            List<Inspection> previousInspections,
            List<YearMonth> months
    ) {
        List<Long> currentInspectionIds() {
            return currentInspections.stream().map(Inspection::getId).toList();
        }
    }

    private record SummaryValues(
            long totalDefects,
            long progressingDefects,
            double actionCompletionRate,
            double avgResolutionDays
    ) {
        static SummaryValues zero() {
            return new SummaryValues(0, 0, 0.0, 0.0);
        }
    }
}
