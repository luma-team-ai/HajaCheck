package com.hajacheck.core.facility.service;

import com.hajacheck.auth.service.CompanyScopeGuard;
import com.hajacheck.core.defect.entity.Defect;
import com.hajacheck.core.defect.entity.DefectGrade;
import com.hajacheck.core.defect.entity.DefectStatus;
import com.hajacheck.core.defect.repository.DefectRepository;
import com.hajacheck.core.facility.dto.FacilityComparisonResponse;
import com.hajacheck.core.facility.dto.FacilityComparisonResponse.ComparisonKpi;
import com.hajacheck.core.facility.dto.FacilityComparisonResponse.CycleOption;
import com.hajacheck.core.facility.dto.FacilityComparisonResponse.DefectChangeRow;
import com.hajacheck.core.facility.entity.Facility;
import com.hajacheck.core.facility.repository.FacilityRepository;
import com.hajacheck.core.inspection.entity.Inspection;
import com.hajacheck.core.inspection.entity.InspectionStatus;
import com.hajacheck.core.inspection.repository.InspectionRepository;
import com.hajacheck.core.media.entity.Media;
import com.hajacheck.core.media.repository.MediaRepository;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 회차 간 비교 조회(HAJA-531/#1112, HAJA-437 후속) — previous_defect_id로 두 회차의 하자를 매칭해
 * 신규/진행중(악화 포함)/재발생/개선완료 4가지로 분류한다. "재발생"(이전 회차 RESOLVED였던 하자가
 * 이후 회차에 previousDefectId로 재연결된 경우)은 처음엔 프론트 DefectChangeType에 대응 타입이 없어
 * 진행중(worsened)으로 근사 매핑했으나, HAJA-532(#1119)에서 별도 타입("recurring")으로 정확히 분리했다.
 *
 * <p>IDOR/회차 유효성 검증은 DefectService.confirmPreviousDefect의 기존 패턴(같은 시설물·회사 스코프)을
 * 재사용한다 — 시설물을 회사 스코프로 먼저 조회하고, 각 회차를 그 시설물 범위 안에서만 조회한다.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class FacilityComparisonService {

    private static final Map<String, String> KPI_LABELS = Map.of(
            "newDefects", "신규 하자",
            "worsening", "진행성 (악화)",
            "resolved", "개선/조치 완료",
            "gradeEscalated", "등급 상승"
    );

    // #1298 — ANALYZED 이전(CREATED/UPLOADING/ANALYZING)은 AI 분석이 안 끝나 defects가 아직 없다.
    // 이 상태 회차를 비교 대상으로 쓰면 상대 회차의 실제 하자가 전부 "신규"로 오분류된다.
    private static final Set<InspectionStatus> COMPARABLE_STATUSES =
            EnumSet.of(InspectionStatus.ANALYZED, InspectionStatus.REVIEWED, InspectionStatus.REPORTED);

    private final FacilityRepository facilityRepository;
    private final InspectionRepository inspectionRepository;
    private final DefectRepository defectRepository;
    private final MediaRepository mediaRepository;
    private final CompanyScopeGuard companyScopeGuard;

    public FacilityComparisonResponse compare(
            Long userId, Long companyId, Long facilityId, Integer beforeRound, Integer afterRound) {
        companyScopeGuard.requireEffectiveMembership(userId, companyId);
        Facility facility = facilityRepository.findByIdAndCompanyId(facilityId, companyId)
                .orElseThrow(() -> new BusinessException(ErrorCode.FACILITY_NOT_FOUND));

        List<Inspection> allInspections = inspectionRepository.findByFacilityIdIn(List.of(facilityId));
        List<CycleOption> availableCycles = allInspections.stream()
                .filter(i -> COMPARABLE_STATUSES.contains(i.getStatus()))
                .sorted((a, b) -> Integer.compare(a.getRoundNo(), b.getRoundNo()))
                .map(i -> new CycleOption(i.getRoundNo(), i.getInspectionDate()))
                .toList();

        if (beforeRound == null || afterRound == null) {
            // #1157 — 프론트가 시설물마다 다른 실제 회차를 알지 못한 채 최초 진입하므로, 미지정 시
            // 이 시설물이 실제로 가진 가장 최근 2개 회차로 서버가 대신 골라준다(과거 프론트 하드코딩
            // 7/8회차가 회차 이력이 다른 시설물에서 INSPECTION_NOT_FOUND로 항상 실패하던 문제).
            if (availableCycles.size() < 2) {
                throw new BusinessException(ErrorCode.INSPECTION_COMPARISON_INSUFFICIENT_ROUNDS);
            }
            afterRound = availableCycles.get(availableCycles.size() - 1).cycle();
            beforeRound = availableCycles.get(availableCycles.size() - 2).cycle();
        } else if (beforeRound >= afterRound) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }

        Inspection beforeInspection = inspectionRepository.findByFacilityIdAndRoundNo(facilityId, beforeRound)
                .orElseThrow(() -> new BusinessException(ErrorCode.INSPECTION_NOT_FOUND));
        Inspection afterInspection = inspectionRepository.findByFacilityIdAndRoundNo(facilityId, afterRound)
                .orElseThrow(() -> new BusinessException(ErrorCode.INSPECTION_NOT_FOUND));
        // #1298 — availableCycles가 이미 걸러내지만, 클라이언트가 before/after를 명시적으로 지정하는
        // 경로는 그 필터를 우회할 수 있어 서버에서 한 번 더 막는다(방어적 검증).
        if (!COMPARABLE_STATUSES.contains(beforeInspection.getStatus())
                || !COMPARABLE_STATUSES.contains(afterInspection.getStatus())) {
            throw new BusinessException(ErrorCode.INSPECTION_COMPARISON_ROUND_NOT_ANALYZED);
        }

        List<Defect> beforeDefects = defectRepository.findByInspectionIdAndNotDeleted(beforeInspection.getId());
        List<Defect> afterDefects = defectRepository.findByInspectionIdAndNotDeleted(afterInspection.getId());

        List<DefectChangeRow> changes = classify(beforeDefects, afterDefects);

        return new FacilityComparisonResponse(
                facility.getId(),
                facility.getName(),
                new CycleOption(beforeInspection.getRoundNo(), beforeInspection.getInspectionDate()),
                new CycleOption(afterInspection.getRoundNo(), afterInspection.getInspectionDate()),
                buildKpis(changes),
                changes,
                availableCycles,
                representativeImageUrl(beforeInspection.getId()),
                representativeImageUrl(afterInspection.getId())
        );
    }

    // 회차별 "시각적 비교" 대표 사진(HAJA-612/#1346) — 그 회차의 첫 사진(2026-07-31 사용자 결정).
    // 사진이 없으면 null(프론트가 기존 "사진 없음" 플레이스홀더를 그대로 보여준다). 조회 대상 inspectionId는
    // compare()가 이미 회사 스코프(findByIdAndCompanyId → findByFacilityIdAndRoundNo)로 좁혀둔 값이라
    // 별도 인가 분기를 만들지 않는다. URL 형식은 MediaResponse.from()/DefectDetailItem과 동일한 상대경로.
    private String representativeImageUrl(Long inspectionId) {
        List<Media> media = mediaRepository.findByInspectionIdOrderByIdAsc(inspectionId);
        return media.isEmpty() ? null : "/api/media/" + media.get(0).getId() + "/detail";
    }

    private List<DefectChangeRow> classify(List<Defect> beforeDefects, List<Defect> afterDefects) {
        Map<Long, Defect> beforeById = beforeDefects.stream()
                .collect(Collectors.toMap(Defect::getId, Function.identity()));
        Set<Long> matchedBeforeIds = afterDefects.stream()
                .map(Defect::getPreviousDefectId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        List<DefectChangeRow> changes = new ArrayList<>();

        for (Defect after : afterDefects) {
            Defect before = after.getPreviousDefectId() == null ? null : beforeById.get(after.getPreviousDefectId());
            if (before == null) {
                // previousDefectId 미확정, 또는 조회 대상 이전 회차 범위 밖을 가리킴 — 신규로 취급.
                changes.add(new DefectChangeRow(
                        after.getId(), after.getLocation(), after.getType().label(),
                        null, gradeCode(after.getGrade()), "new", ""));
                continue;
            }
            String changeType;
            if (before.getStatus() == DefectStatus.RESOLVED) {
                // 재발생(HAJA-532/#1119) — 이전 회차 RESOLVED였던 하자가 이후 회차에 재연결됨.
                changeType = "recurring";
            } else if (after.getStatus() == DefectStatus.RESOLVED) {
                // 코드 리뷰 P1 — 이후 회차 하자 자체가 RESOLVED로 개별 종결된 경우(등급/상태 불변이라도
                // registerActionResult는 연결된 이전 회차 하자를 되돌아보지 않는 행 단위 조치라 이 분기가
                // 없으면 "조치 완료된 하자"가 unchanged로 잘못 표시된다.
                changeType = "resolved";
            } else if (isMoreSevere(after.getGrade(), before.getGrade())) {
                changeType = "worsened";
            } else {
                changeType = "unchanged";
            }
            changes.add(new DefectChangeRow(
                    after.getId(), after.getLocation(), after.getType().label(),
                    gradeCode(before.getGrade()), gradeCode(after.getGrade()), changeType, ""));
        }

        for (Defect before : beforeDefects) {
            if (matchedBeforeIds.contains(before.getId()) || before.getStatus() != DefectStatus.RESOLVED) {
                continue;
            }
            changes.add(new DefectChangeRow(
                    before.getId(), before.getLocation(), before.getType().label(),
                    gradeCode(before.getGrade()), null, "resolved", ""));
        }

        return changes;
    }

    private boolean isMoreSevere(DefectGrade after, DefectGrade before) {
        return after != null && before != null && after.ordinal() > before.ordinal();
    }

    private String gradeCode(DefectGrade grade) {
        return grade == null ? null : grade.name();
    }

    // KPI changeValue — 이전 회차 대비 추이 기준선(3번째 회차 등)이 없어 "악화/신규=양수, 개선=음수"
    // 부호 규칙(프론트 formatComparisonChange.ts, #489 스펙)만 반영한 단순화. 실제 회차간 KPI 추이
    // 비교는 스펙 부재 — 필요 시 별도 이슈로 분리.
    //
    // "진행성 (악화)" KPI는 worsened와 recurring(HAJA-532/#1119)을 함께 센다 — 재발생도 "다시 조치가
    // 필요한, 좋지 않은 상태"라는 점에서 개념적으로 동일 버킷이다. gradeEscalated는 worsened만 본다 —
    // recurring의 이전 등급은 "이미 조치 완료됐던 시점"의 등급이라 이후 등급과 비교해도 진짜 등급
    // 악화를 의미하지 않는다(재발생 자체가 이미 그 사실을 말해준다).
    private List<ComparisonKpi> buildKpis(List<DefectChangeRow> changes) {
        long newCount = countByType(changes, "new");
        long worseningCount = countByType(changes, "worsened") + countByType(changes, "recurring");
        long resolvedCount = countByType(changes, "resolved");
        long gradeEscalatedCount = changes.stream()
                .filter(row -> "worsened".equals(row.changeType()))
                .filter(row -> row.gradeBefore() != null && row.gradeAfter() != null)
                .filter(row -> DefectGrade.valueOf(row.gradeAfter()).ordinal()
                        > DefectGrade.valueOf(row.gradeBefore()).ordinal())
                .count();

        return List.of(
                kpi("newDefects", newCount, newCount),
                kpi("worsening", worseningCount, worseningCount),
                kpi("resolved", resolvedCount, -resolvedCount),
                kpi("gradeEscalated", gradeEscalatedCount, gradeEscalatedCount)
        );
    }

    private long countByType(List<DefectChangeRow> changes, String type) {
        return changes.stream().filter(row -> type.equals(row.changeType())).count();
    }

    private ComparisonKpi kpi(String key, long value, long changeValue) {
        return new ComparisonKpi(key, KPI_LABELS.get(key), value, changeValue);
    }
}
