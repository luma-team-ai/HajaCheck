package com.hajacheck.core.report.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import com.hajacheck.core.media.repository.MediaRepository;
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
import com.hajacheck.global.exception.ErrorCode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Set;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.stream.Stream;
import com.hajacheck.core.defect.repository.InspectionGradeCountProjection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import lombok.RequiredArgsConstructor;
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
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReportService {

    // 확정 하자 범위 — DETECTED(AI 자동탐지 직후, 사람 검토 전)는 AI 보고서 입력에서 제외한다.
    private static final List<DefectStatus> CONFIRMED_DEFECT_STATUSES = List.of(
            DefectStatus.CONFIRMED, DefectStatus.IN_PROGRESS, DefectStatus.RESOLVED);
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

        return toDetailResponse(reportRepository.save(report), userId, companyId, inspection, facility,
                confirmedDefects);
    }

    public ReportDetailResponse getReport(Long reportId, Long userId, Long companyId) {
        companyScopeGuard.requireEffectiveMembership(userId, companyId);
        ScopedReport scoped = findCompanyReportWithInspection(reportId, userId, companyId);
        return toDetailResponse(scoped.report(), userId, companyId, scoped.inspection());
    }

    @Transactional
    public ReportDetailResponse cloneReport(Long reportId, Long companyId, Long userId) {
        companyScopeGuard.requireEffectiveMembership(userId, companyId);
        ScopedReport scoped = findCompanyReportWithInspection(reportId, userId, companyId);
        Report source = scoped.report();
        int nextVersion = nextVersion(source.getInspectionId());
        Report clone = Report.draft(source.getInspectionId(), nextVersion, source.getContentJson(), userId);
        return toDetailResponse(reportRepository.saveAndFlush(clone), userId, companyId, scoped.inspection());
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
        for (InspectionGradeCountProjection count : defectRepository.countGroupByInspectionIdAndGrade(inspectionIds)) {
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
    public ReportDetailResponse recheckGrounding(Long reportId, Long companyId, Long userId) {
        companyScopeGuard.requireEffectiveMembership(userId, companyId);
        ScopedReport scoped = findCompanyReportWithInspection(reportId, userId, companyId);
        Report report = scoped.report();

        List<Defect> confirmedDefects = defectRepository.findByInspectionIdAndStatusInAndDeletedFalse(
                report.getInspectionId(), CONFIRMED_DEFECT_STATUSES);
        Map<DefectContentKey, Integer> expected = toMultiset(confirmedDefects.stream()
                .map(defect -> new DefectContentKey(defect.getType().label(), gradeLabel(defect.getGrade())))
                .map(ReportService::normalizeKey)
                .toList());
        Map<DefectContentKey, Integer> actual = toMultiset(extractDetailKeys(report.getContentJson()));
        boolean matched = expected.equals(actual)
                || (actual.isEmpty() && excludesDetailsByGeneratedOptions(report.getContentJson()));
        report.recordStructuralGroundingRecheck(
                matched, matched ? NO_GROUNDING_WARNINGS : STRUCTURAL_MISMATCH_WARNINGS, userId);
        return toDetailResponse(report, userId, companyId, scoped.inspection(), null, confirmedDefects);
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
        String storageKey = requireOwnPdfUrl(reportId, pdfUrl);
        reportPdfStorage.load(reportId, storageKey);
        reportFinalizationValidator.validate(report.getContentJson());
        report.finalizeReport(pdfUrl, editedByUserId);
        markInspectionReported(scoped.inspection(), companyId, editedByUserId);
        return toDetailResponse(report, editedByUserId, companyId, scoped.inspection());
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
        List<Media> media = mediaRepository.findByInspectionIdOrderByIdAsc(report.getInspectionId());
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
