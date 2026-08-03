package com.hajacheck.core.inspection.service;

import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.repository.UserRepository;
import com.hajacheck.auth.service.AuthService;
import com.hajacheck.auth.service.CompanyScopeGuard;
import com.hajacheck.core.defect.entity.DefectGrade;
import com.hajacheck.core.defect.entity.DefectStatus;
import com.hajacheck.core.defect.entity.DefectType;
import com.hajacheck.core.defect.repository.DefectRepository;
import com.hajacheck.core.defect.repository.InspectionDefectCountProjection;
import com.hajacheck.core.defect.repository.InspectionGradeCountProjection;
import com.hajacheck.core.facility.service.FacilityService;
import com.hajacheck.core.inspection.dto.InspectionCreateRequest;
import com.hajacheck.core.inspection.dto.InspectionListFilterRequest;
import com.hajacheck.core.inspection.dto.InspectionListItemResponse;
import com.hajacheck.core.inspection.dto.InspectionResponse;
import com.hajacheck.core.inspection.entity.Inspection;
import com.hajacheck.core.inspection.entity.InspectionStatus;
import com.hajacheck.core.inspection.repository.InspectionRepository;
import com.hajacheck.core.inspection.repository.InspectionSearchCriteria;
import com.hajacheck.global.common.PageResponse;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import com.hajacheck.membership.service.QuotaService;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class InspectionService {


    // PG 가 unique(facility_id, round_no) 에 명시적 이름을 주지 않아 자동 생성되는 제약명
    // (HajaCheck_script.sql, testcontainers-users-init.sql 양쪽 다 동일 정의).
    private static final String ROUND_NO_UNIQUE_CONSTRAINT = "inspections_facility_id_round_no_key";

    // tryStartAnalyzing 전용(PR머신 리뷰 2차 P1) — 이 상태에서 재분석을 선점할 때는 "비삭제 하자 없음"
    // 요건을 건너뛴다. FAILED는 검수(REVIEWED)에 도달한 적이 없어 남은 하자가 전부 이번에 실패한
    // 분석 실행 자체가 만든 AI 결과뿐이다 — 실제 삭제는 워커가 첫 탐지 성공 시점에 한다(tryStartAnalyzing
    // 참고).
    private static final java.util.Set<InspectionStatus> FAILED_SOURCE_IGNORING_EXISTING_DEFECTS =
            java.util.EnumSet.of(InspectionStatus.FAILED);

    private final InspectionRepository inspectionRepository;
    private final FacilityService facilityService;
    private final AuthService authService;
    private final CompanyScopeGuard companyScopeGuard;
    private final DefectRepository defectRepository;
    private final UserRepository userRepository;
    private final QuotaService quotaService;

    @Transactional
    public InspectionResponse createInspection(
            InspectionCreateRequest request, Long companyId, Long creatorUserId) {
        companyScopeGuard.requireEffectiveMembership(creatorUserId, companyId);
        // 시설물 선택 검증 — 요청자 회사 소유 시설물만 회차 생성 가능.
        // FacilityService.get()이 미존재/타회사 소유 모두 FACILITY_NOT_FOUND로 던지므로 그대로 검증에 사용한다.
        facilityService.get(creatorUserId, companyId, request.facilityId());

        // 플랜 하향으로 한도를 넘긴 시설물은 읽기 전용이다(#890) — 조회·기존 점검 이력은 그대로 두고
        // 신규 점검 생성만 막는다. 상태 컬럼 없이 "id 오름차순 한도 초과분"으로 계산 판정하므로,
        // 요금제를 다시 올리면 별도 복구 작업 없이 자동으로 풀린다.
        if (quotaService.isFacilityReadOnly(companyId, request.facilityId())) {
            throw new BusinessException(ErrorCode.PLAN_FACILITY_QUOTA_EXCEEDED);
        }

        // 담당자 배정 검증 — users.status=ACTIVE AND role IN (INSPECTOR, ADMIN) + 요청자와 동일 회사(table_design.md §inspections).
        authService.validateAssignableInspector(creatorUserId, request.assignedInspectorId());

        validateInspectionDateBounds(request.inspectionDate());

        // 회차 채번 동시성 경쟁 방지 — 같은 시설물에 대한 동시 생성 요청을 행 잠금으로 직렬화한 뒤 max+1 을 읽는다.
        // code-reviewer P1(#1291) — "기존 최신 회차보다 이전 날짜 금지" 검증(validateInspectionDateNotBeforeLatestRound)도
        // 반드시 이 잠금 뒤에서 읽어야 한다. 잠금 앞에서 읽으면 두 동시 요청이 서로 다른 날짜로 같은 "현재 최신 날짜"
        // 스냅샷을 보고 둘 다 통과할 수 있고, 그 뒤 랜덤한 순서로 커밋되면서 roundNo 오름차순과 점검일 순서가 다시
        // 어긋난다(이 검증 자체가 막으려던 상황이 그대로 재현됨) — nextRoundNo 계산과 동일한 이유로 잠금 뒤에 둔다.
        facilityService.lockForUpdate(request.facilityId());
        validateInspectionDateNotBeforeLatestRound(request.inspectionDate(), request.facilityId());
        int nextRoundNo = inspectionRepository.findMaxRoundNoByFacilityId(request.facilityId()) + 1;

        Inspection inspection = Inspection.builder()
                .facilityId(request.facilityId())
                .createdBy(creatorUserId)
                .assignedInspectorId(request.assignedInspectorId())
                .roundNo(nextRoundNo)
                .inspectionDate(request.inspectionDate())
                .type(request.type())
                .build();

        try {
            // saveAndFlush로 명시 — Inspection.id는 IDENTITY 전략이라 save()만으로도 즉시 INSERT되지만,
            // catch가 채번 전략(SEQUENCE/AUTO 등으로 변경 시 flush가 커밋까지 지연될 수 있음)에
            // 암묵적으로 의존하지 않도록 이 트랜잭션 안에서 INSERT를 강제로 확정한다.
            return InspectionResponse.from(inspectionRepository.saveAndFlush(inspection));
        } catch (DataIntegrityViolationException e) {
            // PESSIMISTIC_WRITE 행 잠금이 정상 경로는 모두 막지만, 방어적으로 unique(facility_id, round_no)
            // 위반만 통일된 409로 변환한다. 그 외 무결성 위반(예: FK 대상이 검증 이후 삭제된 경우 등)은
            // "재시도하면 된다"는 잘못된 안내를 주지 않도록 그대로 전파해 GlobalExceptionHandler가 500으로
            // 로그와 함께 처리하게 둔다.
            if (isRoundNoUniqueViolation(e)) {
                throw new BusinessException(ErrorCode.INSPECTION_ROUND_CONFLICT);
            }
            throw e;
        }
    }

    private boolean isRoundNoUniqueViolation(DataIntegrityViolationException e) {
        if (e.getCause() instanceof ConstraintViolationException cve
                && cve.getConstraintName() != null
                && cve.getConstraintName().contains(ROUND_NO_UNIQUE_CONSTRAINT)) {
            return true;
        }
        String message = e.getMostSpecificCause().getMessage();
        return message != null && message.contains(ROUND_NO_UNIQUE_CONSTRAINT);
    }

    /**
     * 점검 목록 조회(HAJA-393/#725) — 하자 목록 화면 개편 "①점검 단위 목록". 회사 스코프는
     * InspectionRepositoryImpl 이 facility.companyId 조인으로 강제하므로, facilityId 필터에 타사 소유
     * 시설물을 넘겨도 빈 결과만 나온다(cross-company IDOR 방지, DefectService.list()와 동일 원칙).
     *
     * <p>#878(HAJA-452) — defectTypes/defectGrades/defectStatuses 는 자연어 하자조건 검색
     * (POST /api/defects/nl-search)이 산출한 필터를 그대로 실어 재조회하는 용도. 회사 스코프 검증은
     * 위 기존 로직 그대로이며, 새 파라미터는 repository 의 EXISTS 서브쿼리로만 매칭에 관여한다.
     */
    public PageResponse<InspectionListItemResponse> list(
            Long userId, Long companyId, InspectionListFilterRequest filters, Pageable pageable) {
        companyScopeGuard.requireEffectiveMembership(userId, companyId);
        validateListFilters(filters);
        InspectionSearchCriteria criteria = new InspectionSearchCriteria(
                companyId,
                filters.facilityId(),
                filters.status(),
                filters.inspectionType(),
                filters.inspectionDateFrom(),
                filters.inspectionDateTo(),
                filters.roundNoMin(),
                filters.roundNoMax(),
                filters.defectCountMin(),
                filters.defectCountMax(),
                filters.defectType(),
                filters.defectGrade(),
                filters.defectStatus());
        Page<Inspection> page = inspectionRepository.findPageByCompanyIdAndFilters(
                criteria, pageable);

        List<Long> inspectionIds = page.getContent().stream().map(Inspection::getId).toList();
        Map<Long, Long> defectCountByInspectionId = inspectionIds.isEmpty() ? Map.of()
                : defectRepository.countGroupByInspectionId(inspectionIds).stream()
                        .collect(Collectors.toMap(
                                InspectionDefectCountProjection::getInspectionId,
                                InspectionDefectCountProjection::getCnt));

        List<Long> inspectorIds =
                page.getContent().stream().map(Inspection::getAssignedInspectorId).distinct().toList();
        Map<Long, String> inspectorNameById = inspectorIds.isEmpty() ? Map.of()
                : userRepository.findAllById(inspectorIds).stream()
                        .collect(Collectors.toMap(User::getId, User::getName));

        Map<Long, Map<String, Long>> gradeDistributionByInspectionId =
                buildGradeDistributionByInspectionId(inspectionIds);

        return PageResponse.from(page.map(inspection -> InspectionListItemResponse.from(
                inspection,
                inspection.getFacility().getName(),
                inspectorNameById.getOrDefault(inspection.getAssignedInspectorId(), "-"),
                defectCountByInspectionId.getOrDefault(inspection.getId(), 0L),
                gradeDistributionByInspectionId.getOrDefault(inspection.getId(), emptyGradeDistribution()))));
    }

    /**
     * 기존 내부 테스트·호출 호환 어댑터. HTTP 요청 경로는 {@link InspectionListFilterRequest}를 사용한다.
     */
    public PageResponse<InspectionListItemResponse> list(
            Long userId, Long companyId, Long facilityId, InspectionStatus status,
            List<DefectType> defectTypes, List<DefectGrade> defectGrades, List<DefectStatus> defectStatuses,
            Pageable pageable) {
        return list(userId, companyId, new InspectionListFilterRequest(
                facilityId,
                status == null ? null : List.of(status),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                defectTypes,
                defectGrades,
                defectStatuses), pageable);
    }

    private void validateListFilters(InspectionListFilterRequest filters) {
        if (isAfter(filters.inspectionDateFrom(), filters.inspectionDateTo())
                || isGreater(filters.roundNoMin(), filters.roundNoMax())
                || isGreater(filters.defectCountMin(), filters.defectCountMax())) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
    }

    private static boolean isAfter(LocalDate min, LocalDate max) {
        return min != null && max != null && min.isAfter(max);
    }

    private static <T extends Comparable<T>> boolean isGreater(T min, T max) {
        return min != null && max != null && min.compareTo(max) > 0;
    }

    // 점검 목록 등급분포(#893/HAJA-458) — inspectionId 마다 A~E 5개 키를 전부 채운 뒤(기본값 0)
    // 실제 집계값으로 덮어쓴다. BriefingStatsService.gradeDistribution() 과 동일한 패턴이며, 프론트
    // InspectionListItem.gradeDistribution 이 5개 등급 전부를 기대해 undefined 접근("Cannot read
    // properties of undefined") 크래시(#893)를 일으키지 않도록 빈 등급도 0으로 명시한다.
    private Map<Long, Map<String, Long>> buildGradeDistributionByInspectionId(List<Long> inspectionIds) {
        if (inspectionIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Map<String, Long>> distributionByInspectionId = new LinkedHashMap<>();
        for (Long inspectionId : inspectionIds) {
            distributionByInspectionId.put(inspectionId, emptyGradeDistribution());
        }
        List<InspectionGradeCountProjection> counts =
                defectRepository.countGroupByInspectionIdAndGrade(inspectionIds);
        for (InspectionGradeCountProjection projection : counts) {
            distributionByInspectionId.get(projection.getInspectionId())
                    .put(projection.getGrade().name(), projection.getCnt());
        }
        return distributionByInspectionId;
    }

    private static Map<String, Long> emptyGradeDistribution() {
        Map<String, Long> distribution = new LinkedHashMap<>();
        for (DefectGrade grade : DefectGrade.values()) {
            distribution.put(grade.name(), 0L);
        }
        return distribution;
    }

    public InspectionResponse getInspection(Long userId, Long companyId, Long inspectionId) {
        companyScopeGuard.requireEffectiveMembership(userId, companyId);
        Inspection inspection = inspectionRepository.findById(inspectionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INSPECTION_NOT_FOUND));
        // 소유권 검증 — 본인 소유 시설물의 점검만 조회 가능(IDOR 방지). 미존재/타인소유 모두
        // INSPECTION_NOT_FOUND로 통일 응답(시설물이 없거나 타인 소유면 FACILITY_NOT_FOUND가 나오지만
        // 클라이언트 관점에선 "점검이 없거나 조회 불가"로 봐야 하므로 변환).
        try {
            facilityService.get(userId, companyId, inspection.getFacilityId());
        } catch (BusinessException e) {
            if (e.getErrorCode() == ErrorCode.FACILITY_NOT_FOUND) {
                throw new BusinessException(ErrorCode.INSPECTION_NOT_FOUND);
            }
            throw e;
        }
        return InspectionResponse.from(inspection);
    }

    /**
     * 회사 스코프 검증 후 엔티티를 그대로 반환한다(AI 분석 실행/상태, dev-05-04) — 분석 서비스가
     * facilityId/assignedInspectorId 등 DTO에 없는 필드까지 필요해서 getInspection()의 DTO 반환과
     * 별도로 둔다. 조회 전용(읽기 트랜잭션)이며 호출부가 상태를 바꾸려면 {@link #advanceStatus}를 쓴다.
     */
    public Inspection getOwnedInspectionEntity(Long requesterUserId, Long companyId, Long inspectionId) {
        companyScopeGuard.requireEffectiveMembership(requesterUserId, companyId);
        Inspection inspection = inspectionRepository.findById(inspectionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INSPECTION_NOT_FOUND));
        try {
            facilityService.get(requesterUserId, companyId, inspection.getFacilityId());
        } catch (BusinessException e) {
            if (e.getErrorCode() == ErrorCode.FACILITY_NOT_FOUND) {
                throw new BusinessException(ErrorCode.INSPECTION_NOT_FOUND);
            }
            throw e;
        }
        return inspection;
    }

    /**
     * 점검 회차 상태 전이(AI 분석 실행/상태, dev-05-04) — 회사 스코프 검증 후 advanceTo 위임.
     *
     * <p>전이된 엔티티를 그대로 반환한다(#494/#495) — {@link com.hajacheck.core.analysis.service.InspectionAnalysisWorker}가 ANALYZED
     * 전이 직후 createdBy/assignedInspectorId/roundNo로 ANALYSIS_DONE·REVIEW_PENDING 알림을
     * 발행하는 데 필요하다. 트랜잭션이 이 메서드 반환 시점에 커밋되므로, 호출부는 스칼라 필드만
     * 읽어야 한다(facility 등 지연 연관관계는 트랜잭션 밖에서 접근 시 LazyInitializationException).
     */
    @Transactional
    public Inspection advanceStatus(Long requesterUserId, Long companyId, Long inspectionId, InspectionStatus next) {
        Inspection inspection = getOwnedInspectionEntity(requesterUserId, companyId, inspectionId);
        inspection.advanceTo(next);
        return inspection;
    }

    /**
     * 점검 요약 진입("점검 요약" 버튼) 시점에 회차 검수를 확정한다(ANALYZED → REVIEWED) — 이전에는
     * 이 전이가 보고서 최종 확정(ReportService.markInspectionReported)에만 묶여 있어, 보고서를
     * 실제로 만들기 전까지는 회차가 계속 "진행 중"으로 잡혀 같은 시설물의 새 회차 생성이 매번
     * 경고창에 걸렸다(#1291 상태 전이 테이블 주석에 이미 "전이 코드는 미구현" 상태로 예정돼 있던
     * 자리). 검수 자체는 이미 끝났다는 사실(점검 요약 버튼이 활성화되는 조건과 동일)을 여기서
     * 앞당겨 반영한다 — REPORTED로의 최종 전이는 그대로 보고서 확정 시점에 일어난다.
     *
     * <p>이미 REVIEWED/REPORTED면 멱등하게 아무것도 하지 않는다(페이지 재진입·중복 클릭 대비).
     * ANALYZED가 아닌 그 이전 상태(분석 미완료)면 거부한다.
     *
     * <p>미확정 판정 기준(PR머신 리뷰 P1 정정) — status=DETECTED가 아니라 is_reviewed=false 잔존
     * 여부로 막는다. 프론트 "점검 요약" 버튼·reviewedCount는 is_reviewed 기준인데(등급 수정만으로도
     * true가 됨, status는 별도 changeStatus로만 바뀜), status 기준으로 막으면 등급 수정만 하고
     * "이 하자 검수 확정" 버튼은 안 누른 정상 검수 완료 회차도 항상 거부돼 보고서 화면에 영영
     * 진입할 수 없었다. 프론트와 같은 필드로 맞춰야 두 게이트가 항상 같은 결론을 낸다.
     *
     * <p>원자적 조건부 UPDATE(PR머신 리뷰 P2) — read-then-advanceTo(더티 체킹) 대신
     * {@link InspectionRepository#confirmReviewIfAnalyzed}로 "여전히 ANALYZED일 때만" 전이한다.
     * 하자 0건 회차에서 이 메서드가 ANALYZED를 읽은 직후(커밋 전) 다른 요청이 재분석을 원자적으로
     * 선점(ANALYZED→ANALYZING)하면, read-then-write 방식은 그 사실을 못 보고 그대로 REVIEWED로
     * 덮어써 방금 시작된 워커를 고아화한다 — 조건부 UPDATE가 영향 행 0건이면 그 경합이 일어난
     * 것이므로 재시도를 요청한다(멱등 재시도 안전 — 호출부인 ResultViewerPage가 이미 재진입마다
     * 다시 부른다).
     */
    @Transactional
    public void confirmReview(Long requesterUserId, Long companyId, Long inspectionId) {
        Inspection inspection = getOwnedInspectionEntity(requesterUserId, companyId, inspectionId);
        if (inspection.getStatus() == InspectionStatus.REVIEWED
                || inspection.getStatus() == InspectionStatus.REPORTED) {
            return;
        }
        if (inspection.getStatus() != InspectionStatus.ANALYZED) {
            throw new BusinessException(ErrorCode.INSPECTION_REVIEW_NOT_READY);
        }
        if (defectRepository.existsByInspectionIdAndDeletedFalseAndReviewedFalse(inspectionId)) {
            throw new BusinessException(ErrorCode.INSPECTION_REVIEW_INCOMPLETE);
        }
        int updated = inspectionRepository.confirmReviewIfAnalyzed(
                inspectionId, InspectionStatus.REVIEWED, InspectionStatus.ANALYZED);
        if (updated == 0) {
            // 사전 체크 이후 다른 요청(재분석 선점 등)이 상태를 먼저 바꿨다 — 회차 자체는 여전히
            // 유효하니 사용자에게는 "다시 시도해 달라"는 충돌로 안내한다(재조회는 하지 않는다,
            // 클래스 docstring 참고 — 호출부가 재진입 시 다시 부르므로 멱등하게 해소된다).
            throw new BusinessException(ErrorCode.INSPECTION_ROUND_CONFLICT);
        }
    }

    /**
     * ANALYZING 고착 회차를 리퍼(시스템 배치)나 사용자 취소(cancelAnalysis)가 복원한다(코드 리뷰
     * P2 10차) — 배치 호출은 사용자 컨텍스트가 없어 회사 스코프 검증을 거치지 않는다. 여전히
     * ANALYZING일 때만 되돌린다 — 그 사이 정상 완료됐거나 다른 경로가 이미 정리했으면 아무것도
     * 하지 않는다(멱등).
     *
     * <p><b>복원 목적지는 항상 UPLOADING이 아니다</b>(PR머신 리뷰 5차 P1) — FAILED 재분석은 원자적
     * 선점이 "비삭제 하자 없음" 요건을 FAILED에 한해 건너뛰므로(InspectionRepository
     * #startAnalyzingIfNotRunning statusesIgnoringExistingDefects), ANALYZING에 "비삭제 하자가
     * 남은 채" 진입하는 것은 이 PR 이전엔 불가능했던 새 상태이고 오직 FAILED 소스에서만 가능하다.
     * 실제 삭제는 워커가 첫 탐지 성공 시점에야 한다(InspectionAnalysisWorker
     * #softDeleteAllForInspectionThenSave). 그 전에 이 메서드가 불려(취소·리퍼 고착 복구) 항상
     * UPLOADING으로 되돌리면, "UPLOADING인데 비삭제 하자가 있는" 상태가 만들어진다 — 이건
     * hasExistingDefects 기반 fail-closed 가드({@code status != FAILED}일 때만 거부)에 걸려
     * 재분석은 영구 거부되고, ANALYZED도 아니라 검수 진입도 안 되는 dead-end다(이 PR이 없애려던
     * FAILED dead-end가 다른 상태로 재발). 원자적 선점의 불변식상 "비삭제 하자가 남아있다"는 건
     * 곧 "FAILED에서 왔다"는 뜻이므로, 그 경우엔 FAILED로 되돌려 재분석 시 fail-closed 예외가
     * 다시 정확히 성립하게 한다. 하자가 없으면 기존과 동일하게 UPLOADING(RECOVERY_STATUS,
     * InspectionAnalysisService)으로 되돌린다. 전이는 {@link Inspection#advanceTo}가 허용 전이
     * 테이블로 검증한다(ANALYZING→UPLOADING·ANALYZING→FAILED 둘 다 허용).
     */
    /**
     * @return 실제로 되돌린 목적지 상태(FAILED 또는 UPLOADING), 이미 ANALYZING이 아니어서 아무것도
     *         안 했으면 {@code null} — 호출부(reapIfStuck/cancelAnalysis)가 하드코딩된 RECOVERY_STATUS
     *         대신 이 실제 값으로 로그를 남겨야 한다(PR머신 리뷰 감사 — 로그가 항상 "UPLOADING으로
     *         되돌림"이라고 찍어 FAILED로 되돌아간 경우를 오도했었다).
     */
    @Transactional
    public InspectionStatus revertStuckAnalyzing(Long inspectionId) {
        Inspection inspection = inspectionRepository.findById(inspectionId).orElse(null);
        if (inspection == null || inspection.getStatus() != InspectionStatus.ANALYZING) {
            return null;
        }
        InspectionStatus recoveryStatus = defectRepository.existsByInspectionIdAndDeletedFalse(inspectionId)
                ? InspectionStatus.FAILED
                : InspectionStatus.UPLOADING;
        inspection.advanceTo(recoveryStatus);
        return recoveryStatus;
    }

    /**
     * AI 분석 시작 원자적 선점(dev-05-04, 코드 리뷰 P2 픽스) — "조회 후 상태 확인 → 별도 UPDATE"
     * (check-then-act)는 동시 요청 사이에 경쟁 구간이 생겨 둘 다 통과할 수 있다. 소유권 검증 후
     * {@link InspectionRepository#startAnalyzingIfNotRunning} 단일 조건부 UPDATE로 전이해,
     * 영향 행 수로 선점 성공 여부를 원자적으로 판정한다.
     *
     * @param allowedStatuses 선점을 허용할 소스 상태 집합(코드 리뷰 P1 10차) — 호출부가 재분석 허용
     *                        소스 상태(ANALYSIS_ALLOWED_SOURCE_STATUSES)를 넘긴다. 조건부 UPDATE의
     *                        WHERE가 이 집합을 강제하므로, 사전 체크와 이 UPDATE 사이에 REVIEWED/
     *                        REPORTED 등으로 전이돼도 원자적으로 거부된다(사람 확정 하자 유실 TOCTOU 차단).
     * @return true = 이 호출이 ANALYZING을 선점함, false = 선점 불가(다른 요청이 선점했거나 허용되지
     *         않은 소스 상태) — 호출부는 ANALYSIS_ALREADY_RUNNING으로 응답해야 한다.
     */
    @Transactional
    public boolean tryStartAnalyzing(Long requesterUserId, Long companyId, Long inspectionId,
            java.util.Collection<InspectionStatus> allowedStatuses) {
        getOwnedInspectionEntity(requesterUserId, companyId, inspectionId);
        // FAILED_SOURCE_IGNORING_EXISTING_DEFECTS(PR머신 리뷰 2차 P1) — FAILED에서 재분석을 선점할 때는
        // "비삭제 하자 없음" 요건을 건너뛴다. 남은 하자를 지우는 시점은 여기가 아니라 워커가 실제로
        // 첫 탐지에 성공한 순간이다(InspectionAnalysisWorker 참고) — 그래야 선점 이후 큐 포화 등으로
        // 재분석이 시작되지 못해도 기존 하자가 유실되지 않는다(startAnalyzingIfNotRunning 주석 참고).
        return inspectionRepository.startAnalyzingIfNotRunning(
                inspectionId, InspectionStatus.ANALYZING, allowedStatuses,
                FAILED_SOURCE_IGNORING_EXISTING_DEFECTS) > 0;
    }

    // 점검일은 "실제로 점검을 수행한 날짜"를 기록하는 필드다(회차 생성과 동시에 촬영 데이터를
    // 업로드해 AI 분석까지 이어지는 흐름) — 미래 날짜는 애초에 의미가 없어 전부 거부한다. PRD가
    // 사전 예약 스케줄링을 명시하지 않는 한(기존 12개월 여유폭은 "정식 정책 확정 전 임시 여유폭"
    // 이었을 뿐, 실제 예약 기능은 아니었음) 오늘까지만 허용한다.
    //
    // 하한(시설물 등록일 이전 거부)은 팀 피드백으로 제거했다(2026-08-01) — 시설물이 시스템에
    // "등록된" 시점과 그 시설물이 "실제로 존재하기 시작한" 시점은 다른데, 등록일을 하한으로 쓰면
    // 최근에 등록한 시설물은 과거 점검 이력(마이그레이션·소급 입력 등)을 전혀 못 넣는다. 과거
    // 날짜는 이제 전부 허용하고, 회차 간 시간 순서 정합성은 validateInspectionDateNotBeforeLatestRound
    // (#1291, "기존 최신 회차보다 이전 금지")가 별도로 보장한다 — 그건 이번 변경과 무관하게 유지.
    private void validateInspectionDateBounds(LocalDate inspectionDate) {
        if (inspectionDate.isAfter(LocalDate.now())) {
            throw new BusinessException(ErrorCode.INSPECTION_DATE_INVALID);
        }
    }

    // #1291 — roundNo는 항상 생성 순서(max+1)라 점검일과 독립적이다. 점검일이 기존 최신 회차보다
    // 앞서면 허용하던 걸 막는다(회차 간 비교 화면이 회차 번호 오름차순=시간 순서로 가정해서 깨짐).
    // 같은 날짜(하루 여러 회차)는 허용 — 엄격히 "이전"만 막는다. code-reviewer P1 — 반드시
    // facilityService.lockForUpdate 뒤에서 호출해야 한다(호출부 주석 참고, TOCTOU 방지).
    private void validateInspectionDateNotBeforeLatestRound(LocalDate inspectionDate, Long facilityId) {
        inspectionRepository.findMaxInspectionDateByFacilityId(facilityId)
                .filter(inspectionDate::isBefore)
                .ifPresent(latest -> {
                    throw new BusinessException(ErrorCode.INSPECTION_DATE_BEFORE_LATEST_ROUND);
                });
    }
}
