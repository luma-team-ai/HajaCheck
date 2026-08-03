package com.hajacheck.core.dashboard.service;

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
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 대시보드 개요 집계(HAJA-17, dev-03-01) — 모든 조회는 로그인 사용자의 회사(companyId)가 소유한
 * facilities.company_id 범위로만 집계한다(cross-company IDOR 방지, facility 도메인과 동일 원칙).
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
    // "조치 대기" KPI → "검수확정"으로 의미 변경(HAJA-499) — 하자 ACTION_PENDING 건수 대신 점검
    // REVIEWED(검수확정) 건수를 센다. DTO 필드명(pendingAction)은 계약 변경을 피하려 그대로 둔다.
    private static final Set<InspectionStatus> REVIEW_CONFIRMED_STATUSES = EnumSet.of(InspectionStatus.REVIEWED);
    private static final int RECENT_LIMIT = 10;
    private static final int PENDING_PRIORITY_LIMIT = 10;
    private static final int UPCOMING_INSPECTIONS_MAX_LIMIT = 50;
    // "최근 점검 전체보기"(신규) 페이지 크기 상한 — FacilityService.FACILITY_LIST_MAX와 동일한
    // 방어적 상한 컨벤션(과다조회 방지). 프론트 기본 페이지 크기는 10이지만 사용자가 size를
    // 임의로 키워 보내는 경우를 대비한다.
    private static final int RECENT_SEARCH_MAX_SIZE = 100;

    // 대시보드 4단계 한글 상태 라벨 → raw InspectionStatus 집합 역매핑. RecentInspectionResponse의
    // statusLabel()(정방향: raw → 한글)과 반드시 대칭 유지 — 그쪽이 바뀌면 여기도 같이 바꿀 것.
    // REVIEWED 라벨은 "검수확정"(HAJA-499로 KPI 카드와 통일, #1044) — 구 라벨 "조치대기"는 더 이상
    // 쓰지 않는다.
    private static final Map<String, Set<InspectionStatus>> RECENT_STATUS_LABEL_GROUPS = Map.of(
            "분석중", EnumSet.of(InspectionStatus.CREATED, InspectionStatus.UPLOADING, InspectionStatus.ANALYZING),
            "검수대기", EnumSet.of(InspectionStatus.ANALYZED),
            "분석실패", EnumSet.of(InspectionStatus.FAILED),
            "검수확정", EnumSet.of(InspectionStatus.REVIEWED),
            "완료", EnumSet.of(InspectionStatus.REPORTED));

    private final FacilityRepository facilityRepository;
    private final InspectionRepository inspectionRepository;
    private final DefectRepository defectRepository;
    private final UserRepository userRepository;
    private final CompanyScopeGuard companyScopeGuard;

    public DashboardSummaryResponse getSummary(Long userId, Long companyId) {
        companyScopeGuard.requireEffectiveMembership(userId, companyId);
        List<Long> facilityIds = companyFacilityIds(companyId);

        LocalDate thisMonthStart = LocalDate.now(KST).withDayOfMonth(1);
        LocalDate nextMonthStart = thisMonthStart.plusMonths(1);
        LocalDate lastMonthStart = thisMonthStart.minusMonths(1);

        long totalFacilities = facilityRepository.countByCompanyId(companyId);
        long totalFacilitiesLastMonth =
                facilityRepository.countByCompanyIdAndCreatedAtBefore(companyId, thisMonthStart.atStartOfDay());

        long monthlyAnalyzed = countInspections(facilityIds, ANALYZED_STATUSES, thisMonthStart, nextMonthStart);
        long monthlyAnalyzedLastMonth =
                countInspections(facilityIds, ANALYZED_STATUSES, lastMonthStart, thisMonthStart);

        long pendingReview = facilityIds.isEmpty() ? 0
                : inspectionRepository.countByFacilityIdInAndStatusIn(facilityIds, PENDING_REVIEW_STATUSES);
        long pendingReviewThisMonth =
                countInspections(facilityIds, PENDING_REVIEW_STATUSES, thisMonthStart, nextMonthStart);
        long pendingReviewLastMonth =
                countInspections(facilityIds, PENDING_REVIEW_STATUSES, lastMonthStart, thisMonthStart);

        long pendingAction = facilityIds.isEmpty() ? 0
                : inspectionRepository.countByFacilityIdInAndStatusIn(facilityIds, REVIEW_CONFIRMED_STATUSES);
        long pendingActionThisMonth =
                countInspections(facilityIds, REVIEW_CONFIRMED_STATUSES, thisMonthStart, nextMonthStart);
        long pendingActionLastMonth =
                countInspections(facilityIds, REVIEW_CONFIRMED_STATUSES, lastMonthStart, thisMonthStart);

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

    public List<GradeDistributionResponse> getGradeDistribution(Long userId, Long companyId) {
        companyScopeGuard.requireEffectiveMembership(userId, companyId);
        List<Long> inspectionIds = inspectionIdsOf(companyFacilityIds(companyId));
        List<GradeCountProjection> counts =
                inspectionIds.isEmpty() ? List.of() : defectRepository.countGroupByGrade(inspectionIds);

        Map<DefectGrade, Long> countByGrade = new HashMap<>();
        long total = 0;
        for (GradeCountProjection projection : counts) {
            countByGrade.put(projection.getGrade(), projection.getCnt());
            total += projection.getCnt();
        }

        // 등급 분류된 하자가 한 건도 없으면 빈 목록(#347). countGroupByGrade 는 grade is not null
        // 조건이라 total==0 은 "미분류(grade=null) 하자만 있는 경우"도 포함한다 — 어느 쪽이든
        // 등급 막대에 그릴 것이 없다. A~E 를 0% 5건으로 채우면 프론트의 빈 상태 가드가
        // 발동하지 못하고, 합계가 0% 라 스토리보드 DASH-01 V2("합계가 100%인지 검증")도 위반된다.
        // 반면 하자가 있으면 집계 0 인 등급까지 5개 전부 반환한다 — 막대그래프가 A~E 를 모두 보여주고
        // 합계 100% 가 성립해야 하므로(GradeDistributionResponse javadoc 참고).
        if (total == 0) {
            return List.of();
        }

        long finalTotal = total;
        return Arrays.stream(DefectGrade.values())
                .map(grade -> GradeDistributionResponse.of(grade, countByGrade.getOrDefault(grade, 0L), finalTotal))
                .toList();
    }

    public List<PendingPriorityResponse> getPendingPriority(Long userId, Long companyId) {
        companyScopeGuard.requireEffectiveMembership(userId, companyId);
        List<Facility> facilities = facilityRepository.findByCompanyId(companyId);
        // 처리 대기 카드에 시설물명뿐 아니라 유형·주소까지 함께 표시(Figma "이름+세부정보" 정합 —
        // #556 후속) 하려면 이름만 담긴 맵이 아니라 Facility 엔티티 자체가 필요하다.
        Map<Long, Facility> facilityById = facilities.stream()
                .collect(Collectors.toMap(Facility::getId, facility -> facility));
        List<Long> facilityIds = facilities.stream().map(Facility::getId).toList();

        List<Inspection> inspections = inspectionsOf(facilityIds);
        Map<Long, Long> facilityIdByInspectionId = toFacilityIdMap(inspections);
        List<Long> inspectionIds = inspections.stream().map(Inspection::getId).toList();
        if (inspectionIds.isEmpty()) {
            return List.of();
        }

        List<Defect> defects = defectRepository.findPendingPriorityDefects(
                inspectionIds, DefectStatus.CONFIRMED, PageRequest.of(0, PENDING_PRIORITY_LIMIT));

        return defects.stream()
                .map(defect -> {
                    Long facilityId = facilityIdByInspectionId.get(defect.getInspectionId());
                    Facility facility = facilityById.get(facilityId);
                    return PendingPriorityResponse.from(defect, facility);
                })
                .toList();
    }

    public List<RecentInspectionResponse> getRecentInspections(Long userId, Long companyId) {
        companyScopeGuard.requireEffectiveMembership(userId, companyId);
        List<Facility> facilities = facilityRepository.findByCompanyId(companyId);
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

    /**
     * 대시보드 "최근 점검 전체보기"(신규) — {@link #getRecentInspections}(위젯, 상위 10건 고정
     * 플랫 배열)와 별도 메서드/엔드포인트로 둔다. 기존 위젯 호출 경로는 이 메서드를 전혀 타지
     * 않으므로 회귀 위험 없는 additive 확장이다. 페이지네이션 + 시설물/상태(한글 라벨)/텍스트
     * 검색(시설물명 또는 담당자명)을 지원한다.
     */
    public PageResponse<RecentInspectionResponse> searchRecentInspections(
            Long userId, Long companyId, Long facilityId, String facilityType, String statusLabel, String query,
            Pageable pageable) {
        companyScopeGuard.requireEffectiveMembership(userId, companyId);

        Set<InspectionStatus> statuses = resolveStatusLabel(statusLabel);
        int cappedSize = Math.min(Math.max(pageable.getPageSize(), 1), RECENT_SEARCH_MAX_SIZE);

        // PR머신 검수 P3 — size는 100으로 캡되지만 page엔 상한이 없어, offset(=page*size)이
        // int 범위를 넘으면 setFirstResult((int) offset)이 음수로 캐스팅돼 500을 유발한다
        // (예: ?page=21474837&size=100). DB까지 갈 필요 없이 빈 페이지로 바로 응답한다.
        long requestedOffset = (long) pageable.getPageNumber() * cappedSize;
        if (requestedOffset > Integer.MAX_VALUE) {
            return new PageResponse<>(List.of(), pageable.getPageNumber(), 0);
        }
        Pageable safePageable = PageRequest.of(pageable.getPageNumber(), cappedSize);

        boolean hasQuery = query != null && !query.isBlank();
        // LIKE 와일드카드(%, _)를 사용자가 검색어에 리터럴로 입력해도(예: "_"가 포함된 시설물명) 와일드카드로
        // 해석되지 않도록 이스케이프 — AdminUserService.normalizeKeyword와 동일 컨벤션. userRepository/
        // inspectionRepository 양쪽 LIKE 절 모두 이 값을 그대로 쓰므로 여기서 한 번만 이스케이프한다.
        String escapedQuery = hasQuery ? escapeLikeWildcards(query.trim()) : null;
        List<Long> matchingCreatorIds = hasQuery
                ? userRepository.findIdsByCompanyIdAndNameContaining(companyId, escapedQuery)
                : List.of();

        Page<Inspection> page = inspectionRepository.findRecentInspectionsPage(
                companyId, facilityId, facilityType, statuses, escapedQuery, matchingCreatorIds, safePageable);

        List<Inspection> content = page.getContent();
        if (content.isEmpty()) {
            return new PageResponse<>(List.of(), page.getNumber(), page.getTotalElements());
        }

        List<Long> inspectionIds = content.stream().map(Inspection::getId).toList();
        Map<Long, Long> defectCountByInspectionId = defectRepository.countGroupByInspectionId(inspectionIds).stream()
                .collect(Collectors.toMap(
                        InspectionDefectCountProjection::getInspectionId,
                        InspectionDefectCountProjection::getCnt));

        List<Long> creatorIds = content.stream().map(Inspection::getCreatedBy).distinct().toList();
        Map<Long, String> creatorNameById = userRepository.findAllById(creatorIds).stream()
                .collect(Collectors.toMap(User::getId, User::getName));

        // getRecentInspections()와 동일하게 facilityRepository로 배치 조회한 이름 맵을 쓴다
        // (inspection.getFacility() 지연 연관관계 직접 참조 지양) — 같은 트랜잭션 내에서 먼저
        // save() 된 Inspection이 영속성 컨텍스트 1차 캐시에 facility=null 상태로 이미 올라가 있으면
        // 이후 fetch join 쿼리 결과가 그 관리 중인 인스턴스를 그대로 재사용해 facility가 채워지지
        // 않는 경우가 있다(JPA 아이덴티티 맵 캐시 특성) — 명시적 배치 조회가 항상 안전하다.
        List<Long> facilityIds = content.stream().map(Inspection::getFacilityId).distinct().toList();
        Map<Long, String> facilityNameById = facilityRepository.findAllById(facilityIds).stream()
                .collect(Collectors.toMap(Facility::getId, Facility::getName));

        return PageResponse.from(page.map(inspection -> RecentInspectionResponse.from(
                inspection,
                facilityNameById.getOrDefault(inspection.getFacilityId(), "-"),
                creatorNameById.getOrDefault(inspection.getCreatedBy(), "-"),
                defectCountByInspectionId.getOrDefault(inspection.getId(), 0L))));
    }

    // 백슬래시부터 먼저 이스케이프해야 뒤이어 삽입하는 이스케이프 문자와 충돌하지 않는다 —
    // UserRepository.findIdsByCompanyIdAndNameContaining/InspectionRepositoryImpl의 `escape '\\'`와 짝을 이룬다.
    private String escapeLikeWildcards(String raw) {
        return raw.replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }

    private Set<InspectionStatus> resolveStatusLabel(String statusLabel) {
        if (statusLabel == null || statusLabel.isBlank()) {
            return Set.of();
        }
        Set<InspectionStatus> statuses = RECENT_STATUS_LABEL_GROUPS.get(statusLabel);
        if (statuses == null) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
        return statuses;
    }

    /**
     * 다가오는 점검 예정 시설물 조회(dev-03-02) — company_id 단일 스코프(기존 대시보드 엔드포인트와
     * 동일 원칙), nextInspectionDueAt 이 오늘~오늘+days 이내이며 null 이 아닌 시설물만
     * nextInspectionDueAt 오름차순으로 최대 limit 건 반환한다.
     */
    public List<UpcomingInspectionResponse> getUpcomingInspections(
            Long userId, Long companyId, int days, int limit) {
        companyScopeGuard.requireEffectiveMembership(userId, companyId);
        LocalDate today = LocalDate.now(KST);
        LocalDate from = today;
        LocalDate to = today.plusDays(days);
        int safeLimit = Math.min(limit, UPCOMING_INSPECTIONS_MAX_LIMIT);

        List<Facility> facilities = facilityRepository.findUpcomingByCompanyId(
                companyId, from, to, PageRequest.of(0, safeLimit));

        return facilities.stream()
                .map(facility -> UpcomingInspectionResponse.from(facility, today))
                .toList();
    }

    private List<Long> companyFacilityIds(Long companyId) {
        return facilityRepository.findByCompanyId(companyId).stream().map(Facility::getId).toList();
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
