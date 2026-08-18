package com.hajacheck.core.report.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hajacheck.auth.entity.Company;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.repository.CompanyRepository;
import com.hajacheck.auth.repository.UserRepository;
import com.hajacheck.auth.service.CompanyScopeGuard;
import com.hajacheck.core.ai.dto.ReportRequest;
import com.hajacheck.core.ai.dto.ReportResponse;
import com.hajacheck.core.ai.service.AiProxyService;
import com.hajacheck.core.defect.entity.Defect;
import com.hajacheck.core.defect.entity.DefectGrade;
import com.hajacheck.core.defect.entity.DefectStatus;
import com.hajacheck.core.defect.repository.DefectRepository;
import com.hajacheck.core.facility.dto.FacilityResponse;
import com.hajacheck.core.facility.service.FacilityService;
import com.hajacheck.core.inspection.dto.InspectionResponse;
import com.hajacheck.core.inspection.entity.InspectionStatus;
import com.hajacheck.core.inspection.service.InspectionService;
import com.hajacheck.core.media.entity.Media;
import com.hajacheck.core.media.entity.MediaPurpose;
import com.hajacheck.core.media.repository.MediaRepository;
import com.hajacheck.core.report.dto.ReportDefectDiffResponse;
import com.hajacheck.core.report.dto.ReportDefectSyncResponse;
import com.hajacheck.core.report.dto.ReportDetailResponse;
import com.hajacheck.core.report.dto.ReportSummaryResponse;
import com.hajacheck.core.report.dto.CompanyReportListItemResponse;
import com.hajacheck.core.report.dto.CompanyReportSummaryResponse;
import com.hajacheck.core.report.entity.GroundingCheckResult;
import com.hajacheck.core.report.entity.GroundingRequestContext;
import com.hajacheck.core.report.entity.Report;
import com.hajacheck.core.report.entity.ReportStatus;
import com.hajacheck.core.report.repository.CompanyReportSummaryProjection;
import com.hajacheck.core.report.repository.ReportRepository;
import com.hajacheck.core.report.support.ReportPdfStorage;
import com.hajacheck.global.common.ApiResponse;
import com.hajacheck.global.common.PageResponse;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.DomainValidationException;
import com.hajacheck.global.exception.ErrorCode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Set;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.stream.Stream;
import com.hajacheck.core.defect.repository.InspectionGradeCountProjection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 점검 결과 기반 AI 보고서 생성·조회·편집·확정(#446 / HAJA-283).
 * grounding 왕복(캡처→AI 호출→기록) 은 GroundingRequestContext/GroundingReportRequestFactory/
 * GroundingReportContentSerializer/GroundingCheckResultFactory(report.service, #349/#334 산출물)를
 * 조립만 하고 새로 설계하지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReportService {

    // 확정 하자 범위 — DETECTED(AI 자동탐지 직후, 사람 검토 전)는 AI 보고서 입력에서 제외한다.
    private static final List<DefectStatus> CONFIRMED_DEFECT_STATUSES = List.of(
            DefectStatus.CONFIRMED, DefectStatus.IN_PROGRESS, DefectStatus.RESOLVED);
    // 버전 채번 경합(#1653 P3) 재시도 대상 제약명 — GlobalExceptionHandler.EXPECTED_INTEGRITY_CONFLICTS와
    // 동일한 두 이름(환경별 제약명 차이 대응, 엔티티 @UniqueConstraint 지정명/DB 자동생성명)을 인식한다.
    private static final Set<String> REPORT_VERSION_CONFLICT_CONSTRAINTS =
            Set.of("uk_reports_inspection_version", "reports_inspection_id_version_key");
    private static final String DEFAULT_ON_MISMATCH = "regenerate";
    // 내부 AI 서버가 이미 grounding_ok/근거 대조까지 마친 응답만 신뢰하므로(GroundingCheckResultFactory
    // 참고), 별도 경고 수집 파이프라인이 붙기 전까지는 항상 빈 배열로 기록한다(GroundingCheckResult가
    // passed=true일 때 비어있지 않은 경고와의 동시 존재를 막는 도메인 규칙과도 정합).
    private static final String NO_GROUNDING_WARNINGS = "[]";
    // 구조 재검증(#680) 불일치 시 기록하는 경고 — 이미 검증된 JSON 문자열 리터럴이라
    // JsonValidator.requireValidJson 통과가 보장된다(GroundingCheckResult가 하는 것과 동일한 방식).
    private static final String STRUCTURAL_MISMATCH_WARNINGS =
            "[\"편집된 하자 상세 항목이 확정된 하자 목록과 일치하지 않습니다\"]";
    private static final String UNCLASSIFIED_GRADE_LABEL = "미분류";
    private static final String GRADE_SUFFIX = "등급";
    private static final ObjectMapper RECHECK_MAPPER = new ObjectMapper();

    private final ReportRepository reportRepository;
    private final DefectRepository defectRepository;
    private final InspectionService inspectionService;
    private final FacilityService facilityService;
    private final AiProxyService aiProxyService;
    private final CompanyScopeGuard companyScopeGuard;
    private final ReportPdfStorage reportPdfStorage;
    private final CompanyRepository companyRepository;
    private final UserRepository userRepository;
    private final MediaRepository mediaRepository;
    private final ReportFinalizationValidator reportFinalizationValidator;

    /**
     * 확정 하자를 근거로 AI 보고서 초안을 생성한다.
     * 확정 하자가 0건이어도 에러로 막지 않고 빈 목록으로 진행한다 — 결함이 없는 정상 점검도 유효한
     * 보고서 유스케이스이며(점검=이상없음 확인도 결과물이 필요), AI 서버 계약(ReportRequest.confirmedDefects
     * @NotEmpty)이 최소 1건을 요구하면 그 시점에 AI_REQUEST_REJECTED 등으로 자연히 드러난다.
     */
    // AI 서버 동기 호출(callAiServer → RestClient)이 지연되면 DB 커넥션이 트랜잭션에 묶여 풀 고갈로 이어진다.
    // NOT_SUPPORTED 로 이 메서드 전체를 트랜잭션 밖에서 실행해(클래스 기본값 readOnly=true 도 상속하지 않음),
    // AI 왕복 동안 커넥션을 잡지 않는다. nextVersion 조회(findFirst...)와 save 는 SimpleJpaRepository 의
    // 각 메서드가 자체 @Transactional 을 걸어주므로(활성 트랜잭션이 없으면 각자 짧게 시작) 별도 처리가 필요 없다.
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ReportDetailResponse generateDraft(Long inspectionId, Long companyId, Long userId) {
        return generateDraft(inspectionId, companyId, userId, null, null);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ReportDetailResponse generateDraft(Long inspectionId, Long companyId, Long userId,
                                               Set<String> sections, Boolean includePhoto) {
        companyScopeGuard.requireEffectiveMembership(userId, companyId);
        InspectionResponse inspection = inspectionService.getInspection(userId, companyId, inspectionId);
        FacilityResponse facility = facilityService.get(userId, companyId, inspection.facilityId());
        // #1479 — 시설물 주소가 비어 있으면 AI 서버 호출 전에 명확히 차단한다. 그대로 넘기면
        // ai-server가 location(min_length=1) 위반으로 422를 던지고 그게 AI_REQUEST_REJECTED(400,
        // "AI 서버가 요청을 거부했습니다")로 뭉뚱그려져 사용자가 원인을 알 수 없게 된다.
        if (!StringUtils.hasText(facility.address())) {
            throw new BusinessException(ErrorCode.FACILITY_ADDRESS_MISSING);
        }

        List<Defect> confirmedDefects = defectRepository.findByInspectionIdAndStatusInAndDeletedFalse(
                inspectionId, CONFIRMED_DEFECT_STATUSES);
        List<ReportRequest.ConfirmedDefect> confirmedDefectDtos = confirmedDefects.stream()
                .map(defect -> ConfirmedDefectTextFactory.from(
                        defect,
                        resolveDefectLocation(defect)
                ))
                .toList();
        ReportRequest.FacilityInfo facilityInfo =
                new ReportRequest.FacilityInfo(facility.name(), facility.address());

        int nextVersion = nextVersion(inspectionId);
        GroundingRequestContext context = GroundingRequestContext.capture(inspectionId, nextVersion);
        ReportRequest request = GroundingReportRequestFactory.from(
                context, facilityInfo, confirmedDefectDtos, DEFAULT_ON_MISMATCH);

        ReportResponse aiReport = callAiServer(userId, request);

        String aiContentJson = GroundingReportContentSerializer.serialize(aiReport);
        Report report = Report.draft(inspectionId, nextVersion, aiContentJson, userId);

        GroundingCheckResult result =
                GroundingCheckResultFactory.fromAiReport(context, aiReport, NO_GROUNDING_WARNINGS);
        report.recordGroundingResult(result, userId);
        if (sections != null || includePhoto != null) {
            report.applyGeneratedOptions(
                    GroundingReportContentSerializer.serialize(aiReport, sections, includePhoto),
                    userId);
        }

        Report saved = saveWithVersionConflictRetry(inspectionId, report, false);
        return toDetailResponse(saved, userId, companyId, inspection, facility, confirmedDefects);
    }

    public ReportDetailResponse getReport(Long reportId, Long userId, Long companyId) {
        companyScopeGuard.requireEffectiveMembership(userId, companyId);
        ScopedReport scoped = findCompanyReportWithInspection(reportId, userId, companyId);
        return toDetailResponse(scoped.report(), userId, companyId, scoped.inspection());
    }

    // 버전 채번 경합 재시도(#1653 P3)를 트랜잭션 밖에서 안전하게 수행하기 위해 generateDraft와 동일하게
    // NOT_SUPPORTED로 둔다 — uk_reports_inspection_version 위반 INSERT 직후 PostgreSQL 트랜잭션은 abort
    // 상태로 고정되어, 같은 트랜잭션 안에서는 재조회(nextVersion)도 재저장도 전부 실패한다(saveWithVersionConflictRetry
    // 참고). 읽기 전용 조회(findCompanyReportWithInspection 등)만 있고 이후 갱신할 기존 행이 없어(새 행
    // INSERT만 발생) 트랜잭션으로 묶을 이유도 없다.
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ReportDetailResponse cloneReport(Long reportId, Long companyId, Long userId) {
        companyScopeGuard.requireEffectiveMembership(userId, companyId);
        ScopedReport scoped = findCompanyReportWithInspection(reportId, userId, companyId);
        Report source = scoped.report();
        int nextVersion = nextVersion(source.getInspectionId());
        Report clone = Report.draft(source.getInspectionId(), nextVersion, source.getContentJson(), userId);
        Report saved = saveWithVersionConflictRetry(source.getInspectionId(), clone, true);
        return toDetailResponse(saved, userId, companyId, scoped.inspection());
    }

    public List<ReportSummaryResponse> listReports(Long inspectionId, Long userId, Long companyId) {
        companyScopeGuard.requireEffectiveMembership(userId, companyId);
        // 소유권 검증(IDOR 방지) — 미존재/타인소유 모두 InspectionService.getInspection() 이 통일 응답.
        inspectionService.getInspection(userId, companyId, inspectionId);
        List<Report> reports = reportRepository.findByInspectionIdAndDeletedAtIsNullOrderByVersionDesc(inspectionId);
        // 작성자 이름은 Report.createdBy(userId)를 조회해야 알 수 있다 — 버전 개수만큼 개별 조회하지
        // 않도록 여기서 한 번에 배치 조회한다(usersById가 이미 이 회사 도메인 전역에서 쓰는 패턴).
        Map<Long, User> usersById = usersById(reports.stream().map(Report::getCreatedBy).toArray(Long[]::new));
        return reports.stream()
                .map(report -> {
                    User creator = usersById.get(report.getCreatedBy());
                    return ReportSummaryResponse.from(report, creator == null ? null : creator.getName());
                })
                .toList();
    }

    public PageResponse<CompanyReportListItemResponse> listCompanyReports(
            Long userId, Long companyId, Long facilityId, Integer roundNo, ReportStatus status, String query,
            String period, Pageable pageable) {
        companyScopeGuard.requireEffectiveMembership(userId, companyId);
        List<ReportStatus> statuses = status == null
                ? List.of(ReportStatus.DRAFT, ReportStatus.FINALIZED) : List.of(status);
        LocalDateTime from = reportPeriodStart(period);
        Page<Report> page = reportRepository.findCompanyPage(companyId, statuses,
                facilityId == null ? -1L : facilityId, roundNo == null ? -1 : roundNo,
                query == null ? "" : query.trim(), from, pageable);
        List<Long> inspectionIds = page.getContent().stream().map(Report::getInspectionId).toList();
        Map<Long, Map<String, Long>> distributions = gradeDistribution(inspectionIds);
        return PageResponse.from(page.map(report -> CompanyReportListItemResponse.from(report,
                distributions.getOrDefault(report.getInspectionId(), emptyGradeDistribution()))));
    }

    public CompanyReportSummaryResponse companyReportsSummary(Long userId, Long companyId) {
        companyScopeGuard.requireEffectiveMembership(userId, companyId);
        YearMonth month = YearMonth.now();
        CompanyReportSummaryProjection summary = reportRepository.summarizeCompany(companyId,
                ReportStatus.FINALIZED, ReportStatus.DRAFT, month.atDay(1).atStartOfDay());
        return new CompanyReportSummaryResponse(summary.getTotalCount(), summary.getFinalizedCount(),
                summary.getDraftCount(), summary.getIssuedThisMonthCount());
    }

    private LocalDateTime reportPeriodStart(String period) {
        if (period == null || period.isBlank() || "ALL".equalsIgnoreCase(period)) {
            return LocalDateTime.of(1970, 1, 1, 0, 0);
        }
        int months = switch (period.toUpperCase()) {
            case "1M" -> 1;
            case "3M" -> 3;
            case "6M" -> 6;
            default -> throw new BusinessException(ErrorCode.INVALID_INPUT);
        };
        return LocalDateTime.now().minusMonths(months);
    }

    private Map<Long, Map<String, Long>> gradeDistribution(List<Long> inspectionIds) {
        if (inspectionIds.isEmpty()) return Map.of();
        Map<Long, Map<String, Long>> result = new LinkedHashMap<>();
        for (Long inspectionId : inspectionIds) result.put(inspectionId, emptyGradeDistribution());
        // #1653 P2 — 보고서 목록 등급 분포는 실제로 보고서에 실리는 확정 하자(CONFIRMED_DEFECT_STATUSES)만
        // 반영한다. status 무관 전체를 세는 countGroupByInspectionIdAndGrade(다른 화면 다수가 소비 중)를
        // 그대로 쓰면 DETECTED(사람 검토 전)까지 섞여, 보고서에 없는 하자가 목록 배지에는 잡힌다.
        for (InspectionGradeCountProjection count :
                defectRepository.countGroupByInspectionIdAndGradeAndStatusIn(inspectionIds, CONFIRMED_DEFECT_STATUSES)) {
            result.get(count.getInspectionId()).put(count.getGrade().name(), count.getCnt());
        }
        return result;
    }

    private Map<String, Long> emptyGradeDistribution() {
        Map<String, Long> result = new LinkedHashMap<>();
        for (DefectGrade grade : DefectGrade.values()) result.put(grade.name(), 0L);
        return result;
    }

    @Transactional
    public ReportDetailResponse updateContent(
            Long reportId, String contentJson, Long companyId, Long editedByUserId) {
        companyScopeGuard.requireEffectiveMembership(editedByUserId, companyId);
        ScopedReport scoped = findCompanyReportWithInspection(reportId, editedByUserId, companyId);
        Report report = scoped.report();
        report.updateContent(sanitizeClientContentJson(report.getContentJson(), contentJson), editedByUserId);
        return toDetailResponse(report, editedByUserId, companyId, scoped.inspection());
    }

    /**
     * 편집(updateContent)으로 null이 된 grounding 판정을 AI 서버(LLM) 재호출 없이 구조 검증만으로
     * 복구한다(#680 / HAJA-374). 본문(contentJson)의 detail.items를 확정 하자 목록과
     * 유형+등급 멀티셋으로 비교해 일치 여부만 판정한다 — ai-server report_chain.py의
     * _detail_content_key/_detail_matches_confirmed 로직을 그대로 이식(Java화)한 것으로,
     * LLM 호출·수치 재계산 없이 결정론적으로 재현 가능하다.
     */
    @Transactional
    public ReportDefectSyncResponse recheckGrounding(Long reportId, Long companyId, Long userId) {
        companyScopeGuard.requireEffectiveMembership(userId, companyId);
        ScopedReport scoped = findCompanyReportWithInspection(reportId, userId, companyId);
        Report report = scoped.report();

        List<Defect> confirmedDefects = defectRepository.findByInspectionIdAndStatusInAndDeletedFalse(
                report.getInspectionId(), CONFIRMED_DEFECT_STATUSES);
        boolean matched = structuralGroundingMatches(report.getContentJson(), confirmedDefects);
        report.recordStructuralGroundingRecheck(
                matched, matched ? NO_GROUNDING_WARNINGS : STRUCTURAL_MISMATCH_WARNINGS, userId);
        ReportDefectDiffResponse diff = computeDefectDiff(confirmedDefects, report.getContentJson());
        return ReportDefectSyncResponse.from(
                toDetailResponse(report, userId, companyId, scoped.inspection(), null, confirmedDefects), diff);
    }

    /**
     * ai-server report_chain.py `_detail_matches_confirmed` 이식 — AI 서버(LLM) 재호출 없이, 본문
     * (detail.items)이 확정 하자 목록과 유형+등급 멀티셋 기준으로 구조적으로 일치하는지만 판정한다.
     * finalizeReport(#1653 P1)와 recheckGrounding이 동일 로직을 공유한다(중복 구현 금지 — handoff 지시).
     */
    private static boolean structuralGroundingMatches(String contentJson, List<Defect> confirmedDefects) {
        Map<DefectContentKey, Integer> expected = toMultiset(confirmedDefects.stream()
                .map(defect -> new DefectContentKey(defect.getType().label(), gradeLabel(defect.getGrade())))
                .map(ReportService::normalizeKey)
                .toList());
        Map<DefectContentKey, Integer> actual = toMultiset(extractDetailKeys(contentJson));
        return expected.equals(actual)
                || (actual.isEmpty() && excludesDetailsByGeneratedOptions(contentJson));
    }

    private static String gradeLabel(DefectGrade grade) {
        return grade != null ? grade.name() : UNCLASSIFIED_GRADE_LABEL;
    }

    /** report_chain.py `_detail_content_key`: (defect_type.strip(), 등급 정규화) 튜플로 비교한다. */
    private record DefectContentKey(String defectType, String severityGrade) {
    }

    private static DefectContentKey normalizeKey(DefectContentKey raw) {
        return new DefectContentKey(
                raw.defectType() == null ? "" : raw.defectType().strip(),
                normalizeGrade(raw.severityGrade()));
    }

    /**
     * report_chain.py `_normalize_grade`/`normalize_grade_strict` 이식 — 'C등급'·' c ' 처럼 알려진
     * 접미사(등급)만 제거한 뒤, 정확히 한 글자이고 A~E에 속할 때만 정규화된 등급으로 인정한다.
     * 그 외(다글자 잔존 등)는 원본(strip+upper)을 그대로 반환한다(파이썬과 동일 계약 유지).
     */
    private static String normalizeGrade(String raw) {
        String normalized = raw == null ? "" : raw.strip().toUpperCase();
        if (normalized.endsWith(GRADE_SUFFIX)) {
            normalized = normalized.substring(0, normalized.length() - GRADE_SUFFIX.length()).strip();
        }
        if (normalized.length() == 1) {
            try {
                DefectGrade.valueOf(normalized);
                return normalized;
            } catch (IllegalArgumentException ignored) {
                // 유효 등급이 아니면 아래에서 strip+upper 원본을 그대로 반환한다.
            }
        }
        return raw == null ? "" : raw.strip().toUpperCase();
    }

    /**
     * contentJson의 detail.items에서 defect_type/severity_grade(구버전 호환으로 type/grade도 허용)를
     * 추출한다. 저장 시점(GroundingReportContentSerializer)에 이미 검증된 JSON이므로 파싱 실패는
     * 이론상 도달 불가하지만, 방어적으로 빈 목록으로 처리한다(강제 확정 차단 = fail-closed).
     */
    private static List<DefectContentKey> extractDetailKeys(String contentJson) {
        List<DefectContentKey> keys = new ArrayList<>();
        JsonNode root;
        try {
            root = RECHECK_MAPPER.readTree(contentJson);
        } catch (Exception e) {
            return keys;
        }
        JsonNode items = root.path("detail").path("items");
        if (!items.isArray()) {
            return keys;
        }
        for (JsonNode item : items) {
            String type = textOf(item, "defect_type", "type");
            String grade = textOf(item, "severity_grade", "grade");
            keys.add(new DefectContentKey(type, grade));
        }
        return keys;
    }

    /** detail.items의 defect_id(구버전 호환: 없으면 null)까지 함께 추출한다(#1653 P2 diff 계산용). */
    private record DetailItemRef(Long defectId, String defectType, String severityGrade) {
    }

    private static List<DetailItemRef> extractDetailItemRefs(String contentJson) {
        List<DetailItemRef> refs = new ArrayList<>();
        JsonNode root;
        try {
            root = RECHECK_MAPPER.readTree(contentJson);
        } catch (Exception e) {
            return refs;
        }
        JsonNode items = root.path("detail").path("items");
        if (!items.isArray()) {
            return refs;
        }
        for (JsonNode item : items) {
            JsonNode defectIdNode = item.get("defect_id");
            Long defectId = defectIdNode != null && defectIdNode.isNumber() ? defectIdNode.asLong() : null;
            refs.add(new DetailItemRef(defectId, textOf(item, "defect_type", "type"), textOf(item, "severity_grade", "grade")));
        }
        return refs;
    }

    /**
     * 확정 하자 목록과 보고서 본문(detail.items)을 defectId 기준으로 비교한 차이(#1653 P2) —
     * grounding-recheck(진단만)와 resync-defects(실제 재구성) 모두 이 결과를 반환한다. defectId가
     * 없는(구버전 저장분) 항목은 비교 대상에서 제외한다(비교 불가능한 항목을 임의로 판정하지 않는다).
     */
    private ReportDefectDiffResponse computeDefectDiff(List<Defect> confirmedDefects, String contentJson) {
        Map<Long, Defect> confirmedById = new LinkedHashMap<>();
        for (Defect defect : confirmedDefects) {
            if (defect.getId() != null) {
                confirmedById.put(defect.getId(), defect);
            }
        }
        List<DetailItemRef> items = extractDetailItemRefs(contentJson);
        Set<Long> itemDefectIds = new LinkedHashSet<>();
        for (DetailItemRef ref : items) {
            if (ref.defectId() != null) {
                itemDefectIds.add(ref.defectId());
            }
        }

        List<ReportDefectDiffResponse.MissingDefectItem> missing = new ArrayList<>();
        for (Defect defect : confirmedDefects) {
            if (defect.getId() != null && !itemDefectIds.contains(defect.getId())) {
                missing.add(new ReportDefectDiffResponse.MissingDefectItem(
                        defect.getId(), defect.getType(),
                        defect.getType() == null ? null : defect.getType().label(),
                        gradeLabel(defect.getGrade()), resolveDefectLocation(defect)));
            }
        }

        List<ReportDefectDiffResponse.ExtraDefectItem> extra = new ArrayList<>();
        List<ReportDefectDiffResponse.UnmatchedItem> unmatched = new ArrayList<>();
        for (DetailItemRef ref : items) {
            if (ref.defectId() == null) {
                // PR머신 리뷰 P1 — defectId 없는 항목(구버전 저장분)을 잉여로 단정해 조용히 지우지
                // 않는다. resyncDefects는 이 항목들을 그대로 보존하므로, missing/extra 어느 쪽도
                // 아닌 "비교 불가" 항목으로 노출해 사용자가 인지하게 한다.
                unmatched.add(new ReportDefectDiffResponse.UnmatchedItem(ref.defectType(), ref.severityGrade()));
            } else if (!confirmedById.containsKey(ref.defectId())) {
                extra.add(new ReportDefectDiffResponse.ExtraDefectItem(
                        ref.defectId(), ref.defectType(), ref.severityGrade()));
            }
        }

        return new ReportDefectDiffResponse(missing, extra, unmatched);
    }

    /**
     * 확정 하자 기준으로 detail.items를 재구성한다(#1653 P2 resync-defects) — 여전히 확정 상태인
     * 항목(defectId 매칭)은 서술(description/cause 등) 그대로 보존하고, 더 이상 확정 하자가 아닌
     * 항목만 제거한 뒤, 새로 확정된 하자만 구조 필드(defect_type/location/severity_grade)로 추가한다.
     * 새로 추가되는 항목은 서술(description/cause)이 없어 AI 재호출 없이는 채울 수 없으므로 빈 문자열로
     * 두고 사용자가 직접 작성하게 한다(finalize는 ReportFinalizationValidator가 그 전까지 막는다).
     *
     * <p>⚠️ PR머신 리뷰 P1 — defectId가 없는 항목(2026-08-02 이전 저장분 등 구버전 콘텐츠)은 확정
     * 하자와 비교할 방법이 없다고 해서 잉여로 간주해 지우면 안 된다(검수자가 직접 쓴 서술의 무경고
     * 유실). computeDefectDiff와 원칙을 맞춰 그대로 보존하고, 대신 diff.unmatchedItems로 존재를
     * 알린다 — 실제 정리는 사용자가 그 항목을 확인한 뒤 수동 편집(PATCH)으로 하게 한다.
     */
    private String rebuildDetailItems(String contentJson, List<Defect> confirmedDefects) {
        ObjectNode root;
        try {
            JsonNode parsed = RECHECK_MAPPER.readTree(contentJson);
            if (!(parsed instanceof ObjectNode objectNode)) {
                throw new DomainValidationException("보고서 본문 구조가 올바르지 않아 재구성할 수 없습니다");
            }
            root = objectNode;
        } catch (DomainValidationException e) {
            throw e;
        } catch (Exception e) {
            throw new DomainValidationException("보고서 본문 JSON을 재구성할 수 없습니다");
        }

        ObjectNode detail = root.has("detail") && root.get("detail").isObject()
                ? (ObjectNode) root.get("detail")
                : root.putObject("detail");
        ArrayNode existingItems = detail.has("items") && detail.get("items").isArray()
                ? (ArrayNode) detail.get("items")
                : RECHECK_MAPPER.createArrayNode();

        Map<Long, Defect> confirmedById = new LinkedHashMap<>();
        for (Defect defect : confirmedDefects) {
            if (defect.getId() != null) {
                confirmedById.put(defect.getId(), defect);
            }
        }

        ArrayNode rebuilt = RECHECK_MAPPER.createArrayNode();
        Set<Long> keptDefectIds = new LinkedHashSet<>();
        for (JsonNode item : existingItems) {
            JsonNode defectIdNode = item.get("defect_id");
            Long defectId = defectIdNode != null && defectIdNode.isNumber() ? defectIdNode.asLong() : null;
            if (defectId == null) {
                // 비교 불가 항목(구버전 저장분) — 잉여로 단정해 지우지 않고 그대로 보존한다.
                rebuilt.add(item);
                continue;
            }
            if (confirmedById.containsKey(defectId)) {
                rebuilt.add(item);
                keptDefectIds.add(defectId);
            }
            // defectId가 있는데 더 이상 확정 하자가 아니면 제거(잉여 항목).
        }
        for (Defect defect : confirmedDefects) {
            if (defect.getId() == null || keptDefectIds.contains(defect.getId())) {
                continue;
            }
            ObjectNode newItem = RECHECK_MAPPER.createObjectNode();
            newItem.put("defect_id", defect.getId());
            newItem.put("defect_type", defect.getType() == null ? "" : defect.getType().label());
            newItem.put("location", resolveDefectLocation(defect));
            newItem.put("severity_grade", gradeLabel(defect.getGrade()));
            newItem.put("description", "");
            newItem.put("cause", "");
            rebuilt.add(newItem);
        }

        detail.set("items", rebuilt);
        return root.toString();
    }

    /**
     * 확정 하자 기준으로 detail.items를 실제로 재구성해 저장한다(#1653 P2 — grounding 불일치 복구 API).
     * 재구성 후에는 콘텐츠가 바뀌므로 updateContent와 동일하게 grounding 판정이 초기화된다 —
     * 사용자는 새로 추가된 항목의 서술을 채운 뒤 grounding-recheck를 다시 호출해야 한다.
     */
    @Transactional
    public ReportDefectSyncResponse resyncDefects(Long reportId, Long companyId, Long userId) {
        companyScopeGuard.requireEffectiveMembership(userId, companyId);
        ScopedReport scoped = findCompanyReportWithInspection(reportId, userId, companyId);
        Report report = scoped.report();

        List<Defect> confirmedDefects = defectRepository.findByInspectionIdAndStatusInAndDeletedFalse(
                report.getInspectionId(), CONFIRMED_DEFECT_STATUSES);
        ReportDefectDiffResponse diff = computeDefectDiff(confirmedDefects, report.getContentJson());
        String rebuiltContentJson = rebuildDetailItems(report.getContentJson(), confirmedDefects);
        report.updateContent(rebuiltContentJson, userId);

        return ReportDefectSyncResponse.from(
                toDetailResponse(report, userId, companyId, scoped.inspection(), null, confirmedDefects), diff);
    }

    private static String resolveDefectLocation(Defect defect) {
        if (defect != null && StringUtils.hasText(defect.getLocation())) {
            return defect.getLocation().trim();
        }
        return "위치 미입력";
    }

    private static String sanitizeClientContentJson(String currentContentJson, String nextContentJson) {
        try {
            JsonNode current = RECHECK_MAPPER.readTree(currentContentJson);
            JsonNode next = RECHECK_MAPPER.readTree(nextContentJson);

            if (next instanceof ObjectNode nextObject) {
                JsonNode serverOptions = current.get("reportOptions");

                if (serverOptions != null && !serverOptions.isNull()) {
                    nextObject.set("reportOptions", serverOptions.deepCopy());
                } else {
                    nextObject.remove("reportOptions");
                }

                return RECHECK_MAPPER.writeValueAsString(nextObject);
            }
        } catch (Exception ignored) {
            // 유효하지 않은 JSON은 Report.updateContent의 기존 검증 경로에서 동일하게 거부한다.
        }

        return nextContentJson;
    }

    private static boolean excludesDetailsByGeneratedOptions(String contentJson) {
        try {
            JsonNode sections = RECHECK_MAPPER.readTree(contentJson).path("reportOptions").path("sections");
            if (!sections.isArray()) {
                return false;
            }
            for (JsonNode section : sections) {
                if ("details".equals(section.asText())) {
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static String textOf(JsonNode node, String primaryField, String fallbackField) {
        JsonNode value = node.hasNonNull(primaryField) ? node.get(primaryField) : node.get(fallbackField);
        return value == null ? "" : value.asText("");
    }

    private static Map<DefectContentKey, Integer> toMultiset(List<DefectContentKey> keys) {
        Map<DefectContentKey, Integer> multiset = new HashMap<>();
        for (DefectContentKey key : keys) {
            multiset.merge(key, 1, Integer::sum);
        }
        return multiset;
    }

    @Transactional
    public ReportDetailResponse finalizeReport(
            Long reportId, String pdfUrl, Long companyId, Long editedByUserId) {
        companyScopeGuard.requireEffectiveMembership(editedByUserId, companyId);
        ScopedReport scoped = findCompanyReportWithInspection(reportId, editedByUserId, companyId);
        Report report = scoped.report();

        // 멱등 분기(#1653 P2) — 이미 FINALIZED고 pdfUrl까지 있으면 재시도(응답 유실 등)를 새 요청으로
        // 다시 처리하지 않고 현재 확정 상태를 그대로 반환한다. pdfUrl이 없는 FINALIZED(비정상 상태)는
        // 이 분기를 타지 않고 아래로 내려가 requireDraft 위반으로 기존 예외를 그대로 던진다.
        if (report.getStatus() == ReportStatus.FINALIZED && StringUtils.hasText(report.getPdfUrl())) {
            return toDetailResponse(report, editedByUserId, companyId, scoped.inspection());
        }

        String storageKey = requireOwnPdfUrl(reportId, pdfUrl);
        reportPdfStorage.load(reportId, storageKey);
        reportFinalizationValidator.validate(report.getContentJson());

        // grounding 상시 재검증(#1653 P1) — 저장된 groundingCheckPassed는 이 보고서가 편집되지 않은
        // 동안에도 그 사이 확정 하자 목록이 바뀌면 stale해진다(하자 등급 수정·재검수 등, 보고서 자체는
        // 안 건드림). recheckGrounding과 동일 로직(structuralGroundingMatches)을 재사용해 확정 직전
        // 매번 최신 확정 하자 기준으로 다시 판정한다 — 신뢰하지 않고 항상 재계산.
        List<Defect> confirmedDefects = defectRepository.findByInspectionIdAndStatusInAndDeletedFalse(
                report.getInspectionId(), CONFIRMED_DEFECT_STATUSES);
        boolean matched = structuralGroundingMatches(report.getContentJson(), confirmedDefects);
        report.recordStructuralGroundingRecheck(
                matched, matched ? NO_GROUNDING_WARNINGS : STRUCTURAL_MISMATCH_WARNINGS, editedByUserId);

        report.finalizeReport(pdfUrl, editedByUserId);
        markInspectionReported(scoped.inspection(), companyId, editedByUserId);
        return toDetailResponse(report, editedByUserId, companyId, scoped.inspection(), null, confirmedDefects);
    }

    /**
     * 보고서 확정(PDF 업로드 완료) 시점에 회차를 "완료"로 표시한다(팀 테스트 피드백, 2026-08-01).
     *
     * <p>이전엔 {@code REVIEWED}·{@code REPORTED} 둘 다 상태 머신에 도착 상태로만 정의돼 있고 실제로
     * 전이시키는 코드가 어디에도 없었다 — 검수를 아무리 끝내도 회차는 {@code ANALYZED}에 영원히
     * 머물러, 시설물 상세의 "미종료 회차" 판정({@code status != REPORTED})이 다 끝난 회차를 계속
     * "진행 중"으로 보고했다.
     *
     * <p>{@code REPORTED}로 가는 유일한 허용 경로가 {@code REVIEWED → REPORTED}뿐이라(
     * {@link InspectionStatus#canTransitionTo}), 아직 {@code ANALYZED}에 머물러 있는 회차는 먼저
     * {@code REVIEWED}를 거쳐야 한다 — 검수 화면이 하자 단위로만 검수 여부를 관리하고 회차 단위
     * "검수 확정" 액션이 따로 없어서, 그 중간 전이를 여기서 함께 처리한다.
     *
     * <p>⚠️ PR머신 리뷰 P1 — {@code generateDraft()}가 회차 상태를 전혀 검증하지 않아(확정 하자가
     * 0건이어도 초안 생성 허용) {@code CREATED}/{@code UPLOADING}/{@code ANALYZING} 상태에서도
     * finalize 호출이 실제로 도달 가능하다. 이 세 상태는 {@code REPORTED}로 가는 허용 전이 소스가
     * 아니므로(위 canTransitionTo 참고) 무조건 전이를 시도하면 {@code DomainStateTransitionException}
     * 이 나서 finalize 트랜잭션 전체가 롤백된다 — 이전엔 항상 성공하던 "보고서 확정" 자체가
     * 깨지는 회귀였다. {@code ANALYZED}·{@code REVIEWED}(및 이미 {@code REPORTED})가 아니면
     * 아무 전이도 시도하지 않고 그대로 둔다 — 보고서 확정 자체는 이 회차 상태와 무관하게 항상
     * 성공해야 하고, 회차 완료 표시는 그 위에 얹는 부가 효과일 뿐이다.
     */
    private void markInspectionReported(InspectionResponse inspection, Long companyId, Long editedByUserId) {
        InspectionStatus status = inspection.status();
        if (status == InspectionStatus.ANALYZED) {
            inspectionService.advanceStatus(editedByUserId, companyId, inspection.id(), InspectionStatus.REVIEWED);
            status = InspectionStatus.REVIEWED;
        }
        if (status == InspectionStatus.REVIEWED) {
            inspectionService.advanceStatus(editedByUserId, companyId, inspection.id(), InspectionStatus.REPORTED);
            // #1497/HAJA-656 — REPORTED로 실제 전이될 때만(재확정 시 재호출은 이 if에 다시 들어오지
            // 않아 자연히 멱등) 그 회차의 실제 점검일 기준으로 다음 점검일을 재계산한다.
            facilityService.recalculateNextInspectionDueAt(
                    editedByUserId, companyId, inspection.facilityId(), inspection.inspectionDate());
        }
    }

    @Transactional
    public void deleteDraftReport(Long reportId, Long companyId, Long editedByUserId) {
        companyScopeGuard.requireEffectiveMembership(editedByUserId, companyId);
        Report report = findCompanyReport(reportId, editedByUserId, companyId);
        report.markDeleted(editedByUserId);
        // 고아 PDF 방지(#1653 P3) — DRAFT는 pdfUrl이 없어도(finalize 전 업로드 후 방치) 저장소에는
        // 파일이 남아 있을 수 있다. 참조 여부와 무관하게 이 보고서 소유 파일 전체를 즉시 정리한다.
        reportPdfStorage.deleteAll(reportId);
    }

    /**
     * pdfUrl이 이 보고서의 업로드 엔드포인트(/api/reports/{id}/pdf/{storageKey})를 가리키는지 확인하고
     * storageKey를 추출한다 (#455 P2-2, #463 P2).
     */
    private String requireOwnPdfUrl(Long reportId, String pdfUrl) {
        String expectedPrefix = "/api/reports/%d/pdf/".formatted(reportId);
        if (pdfUrl == null || !pdfUrl.startsWith(expectedPrefix) || pdfUrl.length() == expectedPrefix.length()) {
            throw new BusinessException(ErrorCode.REPORT_PDF_URL_INVALID);
        }
        return pdfUrl.substring(expectedPrefix.length());
    }

    private int nextVersion(Long inspectionId) {
        return reportRepository.findFirstByInspectionIdOrderByVersionDesc(inspectionId)
                .map(latest -> latest.getVersion() + 1)
                .orElse(1);
    }

    /**
     * 버전 채번 경합(#1653 P3) — nextVersion() 조회와 INSERT 사이 동시 요청이 끼어들면
     * uk_reports_inspection_version 유니크 제약 위반(REPORT_VERSION_CONFLICT)이 발생한다. 실패 시
     * 새로 배정된 버전으로 1회만 재시도한다(무한 재시도 대신 팀 결정 — 연속 충돌은 매우 좁은 시간창의
     * 3중 이상 경합이 필요해 실무상 드묾).
     *
     * <p>⚠️ 호출자는 반드시 트랜잭션 밖(NOT_SUPPORTED)이어야 한다 — 실패한 INSERT 직후 PostgreSQL
     * 트랜잭션은 abort 상태로 고정되어, 같은 물리 트랜잭션 안에서는 재조회·재저장 모두 실패한다.
     * generateDraft/cloneReport 모두 NOT_SUPPORTED라 각 save 호출이 SimpleJpaRepository 자체
     * {@code @Transactional} 덕분에 독립된 트랜잭션으로 실행되므로, 두 번째 시도는 첫 시도가 남긴
     * abort 상태의 영향을 받지 않는다.
     */
    private Report saveWithVersionConflictRetry(Long inspectionId, Report report, boolean flush) {
        try {
            return flush ? reportRepository.saveAndFlush(report) : reportRepository.save(report);
        } catch (DataIntegrityViolationException e) {
            if (!isReportVersionConflict(e)) {
                throw e;
            }
            int retryVersion = nextVersion(inspectionId);
            log.warn("보고서 버전 채번 경합 감지 — inspectionId={} 재시도 버전={}", inspectionId, retryVersion);
            report.reassignVersionOnConflictRetry(retryVersion);
            return flush ? reportRepository.saveAndFlush(report) : reportRepository.save(report);
        }
    }

    private static boolean isReportVersionConflict(DataIntegrityViolationException e) {
        Throwable cause = e;
        while (cause != null) {
            if (cause instanceof org.hibernate.exception.ConstraintViolationException constraintViolation) {
                return REPORT_VERSION_CONFLICT_CONSTRAINTS.contains(constraintViolation.getConstraintName());
            }
            cause = cause.getCause();
        }
        return false;
    }

    private ReportDetailResponse toDetailResponse(
            Report report, Long userId, Long companyId, InspectionResponse inspection) {
        return toDetailResponse(report, userId, companyId, inspection, null, null);
    }

    private ReportDetailResponse toDetailResponse(
            Report report, Long userId, Long companyId, InspectionResponse inspection, FacilityResponse facility) {
        return toDetailResponse(report, userId, companyId, inspection, facility, null);
    }

    private ReportDetailResponse toDetailResponse(
            Report report, Long userId, Long companyId, InspectionResponse inspection, FacilityResponse facility,
            List<Defect> confirmedDefects) {
        return ReportDetailResponse.from(report, buildContext(
                report, userId, companyId, inspection, facility, confirmedDefects));
    }

    private ReportDetailResponse.ReportContext buildContext(
            Report report, Long userId, Long companyId, InspectionResponse knownInspection,
            FacilityResponse knownFacility, List<Defect> knownConfirmedDefects) {
        InspectionResponse inspection = knownInspection != null
                ? knownInspection
                : inspectionService.getInspection(userId, companyId, report.getInspectionId());
        if (inspection == null) {
            return null;
        }
        FacilityResponse facility = knownFacility != null
                ? knownFacility
                : facilityService.get(userId, companyId, inspection.facilityId());
        List<Defect> defects = knownConfirmedDefects != null
                ? knownConfirmedDefects
                : defectRepository.findByInspectionIdAndStatusInAndDeletedFalse(
                        report.getInspectionId(), CONFIRMED_DEFECT_STATUSES);
        // #1641 P3 방어적 위생 — 보고서 컨텍스트는 원본 촬영사진만 대상으로 한다. 조치 후 사진
        // (DEFECT_ACTION)이 lookup map 조립 외 다른 방식으로 노출되는 경로가 생겨도 안전하도록 좁힌다.
        List<Media> media = mediaRepository.findByInspectionIdAndPurposeOrderByIdAsc(
                report.getInspectionId(), MediaPurpose.INSPECTION_SOURCE);
        Company company = companyRepository.findById(companyId).orElse(null);
        Map<Long, User> users = usersById(
                inspection.createdBy(), inspection.assignedInspectorId(), report.getCreatedBy());

        return new ReportDetailResponse.ReportContext(
                toFacilityContext(facility),
                toInspectionContext(inspection),
                toCompanyContext(company),
                toUserContext(users.get(inspection.assignedInspectorId())),
                toUserContext(users.get(inspection.createdBy())),
                defects.stream().map(this::toDefectContext).toList(),
                media.stream().map(this::toMediaContext).toList());
    }

    private Map<Long, User> usersById(Long... ids) {
        List<Long> validIds = Stream.of(ids).filter(id -> id != null && id > 0).distinct().toList();
        if (validIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, User> result = new HashMap<>();
        for (User user : userRepository.findAllById(validIds)) {
            result.put(user.getId(), user);
        }
        return result;
    }

    private ReportDetailResponse.FacilityContext toFacilityContext(FacilityResponse facility) {
        if (facility == null) {
            return null;
        }
        return new ReportDetailResponse.FacilityContext(
                facility.id(),
                facility.name(),
                facility.type(),
                facility.address(),
                facility.latitude(),
                facility.longitude(),
                facility.builtYear(),
                facility.scale(),
                facility.inspectionCycleMonths(),
                facility.nextInspectionDueAt(),
                facility.initialGrade(),
                facility.assigneeUserId(),
                facility.memo(),
                facility.thumbnailUrl());
    }

    private ReportDetailResponse.InspectionContext toInspectionContext(InspectionResponse inspection) {
        return new ReportDetailResponse.InspectionContext(
                inspection.id(),
                inspection.facilityId(),
                inspection.createdBy(),
                inspection.assignedInspectorId(),
                inspection.roundNo(),
                inspection.inspectionDate(),
                inspection.type(),
                inspection.status(),
                inspection.createdAt());
    }

    private ReportDetailResponse.CompanyContext toCompanyContext(Company company) {
        if (company == null) {
            return null;
        }
        return new ReportDetailResponse.CompanyContext(
                company.getId(),
                company.getName(),
                company.getRepresentativeName(),
                company.getAddress(),
                company.getAddressDetail());
    }

    private ReportDetailResponse.UserContext toUserContext(User user) {
        if (user == null) {
            return null;
        }
        return new ReportDetailResponse.UserContext(
                user.getId(),
                user.getName(),
                user.getRole() == null ? null : user.getRole().name());
    }

    private ReportDetailResponse.DefectContext toDefectContext(Defect defect) {
        return new ReportDetailResponse.DefectContext(
                defect.getId(),
                defect.getType(),
                defect.getType() == null ? null : defect.getType().label(),
                defect.getGrade(),
                defect.getStatus(),
                defect.getLocation(),
                defect.getConfidence(),
                defect.getMediaId(),
                defect.getBboxX(),
                defect.getBboxY(),
                defect.getBboxW(),
                defect.getBboxH(),
                defect.getCrackWidthMm(),
                defect.getCrackLengthMm(),
                defect.getAreaRatio(),
                defect.getActionContent(),
                defect.getActionDate(),
                defect.getActionAssigneeId());
    }

    private ReportDetailResponse.MediaContext toMediaContext(Media media) {
        return new ReportDetailResponse.MediaContext(
                media.getId(),
                media.getInspectionId(),
                media.getFacilityId(),
                media.getFileType() == null ? null : media.getFileType().name(),
                media.getThumbnailUrl() == null || media.getId() == null
                        ? null : "/api/media/%d/thumbnail".formatted(media.getId()),
                media.getDetailUrl() == null || media.getId() == null
                        ? null : "/api/media/%d/detail".formatted(media.getId()),
                media.getOriginalFilename(),
                media.getCapturedAt());
    }

    private ReportResponse callAiServer(Long userId, ReportRequest request) {
        // AiProxyService.generateReport()는 연결/타임아웃/응답형식 실패를 이미 BusinessException으로
        // 던진다(AiProxyService 참고) — 여기서 잡는 것은 envelope 자체는 정상 수신했으나 AI 서버가
        // 보고서 생성을 논리적으로 거부한 경우(envelope.success()=false)뿐이다.
        // userId 는 generateDraft 가 principal 에서 받은 값 — 사용자 축 rate-limit 키로 전달한다.
        ApiResponse<ReportResponse> response = aiProxyService.generateReport(userId, request);
        if (!response.success() || response.data() == null) {
            throw new BusinessException(ErrorCode.REPORT_GENERATION_FAILED);
        }
        return response.data();
    }

    /**
     * 소유권 검증(IDOR 방지) — MediaService.getThumbnail() 패턴과 동일하게, 존재 여부 열거를 막기 위해
     * 미존재/타인소유 모두 REPORT_NOT_FOUND(404) 로 통일 응답한다.
     */
    private Report findCompanyReport(Long reportId, Long userId, Long companyId) {
        return findCompanyReportWithInspection(reportId, userId, companyId).report();
    }

    private record ScopedReport(Report report, InspectionResponse inspection) {
    }

    private ScopedReport findCompanyReportWithInspection(Long reportId, Long userId, Long companyId) {
        Report report = reportRepository.findById(reportId)
                .orElseThrow(() -> new BusinessException(ErrorCode.REPORT_NOT_FOUND));
        if (report.getDeletedAt() != null) {
            throw new BusinessException(ErrorCode.REPORT_NOT_FOUND);
        }
        InspectionResponse inspection;
        try {
            inspection = inspectionService.getInspection(userId, companyId, report.getInspectionId());
        } catch (BusinessException e) {
            if (e.getErrorCode() == ErrorCode.INSPECTION_NOT_FOUND
                    || e.getErrorCode() == ErrorCode.FACILITY_NOT_FOUND) {
                throw new BusinessException(ErrorCode.REPORT_NOT_FOUND);
            }
            throw e;
        }
        return new ScopedReport(report, inspection);
    }
}
