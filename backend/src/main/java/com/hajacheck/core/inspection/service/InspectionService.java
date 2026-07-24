package com.hajacheck.core.inspection.service;

import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.repository.UserRepository;
import com.hajacheck.auth.service.AuthService;
import com.hajacheck.auth.service.CompanyScopeGuard;
import com.hajacheck.core.defect.repository.DefectRepository;
import com.hajacheck.core.defect.repository.InspectionDefectCountProjection;
import com.hajacheck.core.facility.dto.FacilityResponse;
import com.hajacheck.core.facility.service.FacilityService;
import com.hajacheck.core.inspection.dto.InspectionCreateRequest;
import com.hajacheck.core.inspection.dto.InspectionListItemResponse;
import com.hajacheck.core.inspection.dto.InspectionResponse;
import com.hajacheck.core.inspection.entity.Inspection;
import com.hajacheck.core.inspection.entity.InspectionStatus;
import com.hajacheck.core.inspection.repository.InspectionRepository;
import com.hajacheck.global.common.PageResponse;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import java.time.LocalDate;
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

    // 점검일 상한 — 원거리 미래 날짜(연도 오타 등 비정상 입력) 방어용 여유폭. 정식 "점검 예약" 정책이
    // 확정되면 재조정 필요(현재 PRD 는 사전 예약 스케줄링을 명시하지 않음).
    private static final int MAX_FUTURE_MONTHS = 12;

    // PG 가 unique(facility_id, round_no) 에 명시적 이름을 주지 않아 자동 생성되는 제약명
    // (HajaCheck_script.sql, testcontainers-users-init.sql 양쪽 다 동일 정의).
    private static final String ROUND_NO_UNIQUE_CONSTRAINT = "inspections_facility_id_round_no_key";

    private final InspectionRepository inspectionRepository;
    private final FacilityService facilityService;
    private final AuthService authService;
    private final CompanyScopeGuard companyScopeGuard;
    private final DefectRepository defectRepository;
    private final UserRepository userRepository;

    @Transactional
    public InspectionResponse createInspection(
            InspectionCreateRequest request, Long companyId, Long creatorUserId) {
        companyScopeGuard.requireEffectiveMembership(creatorUserId, companyId);
        // 시설물 선택 검증 — 요청자 회사 소유 시설물만 회차 생성 가능.
        // FacilityService.get()이 미존재/타회사 소유 모두 FACILITY_NOT_FOUND로 던지므로 그대로 검증에 사용한다.
        FacilityResponse facility = facilityService.get(creatorUserId, companyId, request.facilityId());

        // 담당자 배정 검증 — users.status=ACTIVE AND role IN (INSPECTOR, ADMIN) + 요청자와 동일 회사(table_design.md §inspections).
        authService.validateAssignableInspector(creatorUserId, request.assignedInspectorId());

        validateInspectionDate(request.inspectionDate(), facility);

        // 회차 채번 동시성 경쟁 방지 — 같은 시설물에 대한 동시 생성 요청을 행 잠금으로 직렬화한 뒤 max+1 을 읽는다.
        facilityService.lockForUpdate(request.facilityId());
        int nextRoundNo = inspectionRepository.findMaxRoundNoByFacilityId(request.facilityId()) + 1;

        Inspection inspection = Inspection.builder()
                .facilityId(request.facilityId())
                .createdBy(creatorUserId)
                .assignedInspectorId(request.assignedInspectorId())
                .roundNo(nextRoundNo)
                .inspectionDate(request.inspectionDate())
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
     */
    public PageResponse<InspectionListItemResponse> list(
            Long userId, Long companyId, Long facilityId, InspectionStatus status, Pageable pageable) {
        companyScopeGuard.requireEffectiveMembership(userId, companyId);
        Page<Inspection> page =
                inspectionRepository.findPageByCompanyIdAndFilters(companyId, facilityId, status, pageable);

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

        return PageResponse.from(page.map(inspection -> InspectionListItemResponse.from(
                inspection,
                inspection.getFacility().getName(),
                inspectorNameById.getOrDefault(inspection.getAssignedInspectorId(), "-"),
                defectCountByInspectionId.getOrDefault(inspection.getId(), 0L))));
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

    /** 점검 회차 상태 전이(AI 분석 실행/상태, dev-05-04) — 회사 스코프 검증 후 advanceTo 위임. */
    @Transactional
    public void advanceStatus(Long requesterUserId, Long companyId, Long inspectionId, InspectionStatus next) {
        Inspection inspection = getOwnedInspectionEntity(requesterUserId, companyId, inspectionId);
        inspection.advanceTo(next);
    }

    /**
     * ANALYZING 고착 회차를 리퍼가 시스템 배치로 복원한다(코드 리뷰 P2 10차) — @Scheduled 리퍼는
     * 사용자 컨텍스트가 없어 회사 스코프 검증을 거치지 않는다(배치 전용, 외부 요청 경로 아님).
     * 여전히 ANALYZING일 때만 UPLOADING으로 되돌린다 — 그 사이 정상 완료됐거나 다른 경로가 이미
     * 정리했으면 아무것도 하지 않는다(멱등). 전이는 {@link Inspection#advanceTo}가 허용 전이 테이블로
     * 검증한다(ANALYZING→UPLOADING 허용). RECOVERY_STATUS(=UPLOADING, InspectionAnalysisService)와
     * 동일한 "업로드는 끝났고 분석 전" 상태로 되돌려, 사용자가 다시 분석을 시작할 수 있게 한다.
     */
    @Transactional
    public void revertStuckAnalyzing(Long inspectionId) {
        Inspection inspection = inspectionRepository.findById(inspectionId).orElse(null);
        if (inspection == null || inspection.getStatus() != InspectionStatus.ANALYZING) {
            return;
        }
        inspection.advanceTo(InspectionStatus.UPLOADING);
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
        return inspectionRepository.startAnalyzingIfNotRunning(
                inspectionId, InspectionStatus.ANALYZING, allowedStatuses) > 0;
    }

    private void validateInspectionDate(LocalDate inspectionDate, FacilityResponse facility) {
        if (inspectionDate.isBefore(facility.createdAt().toLocalDate())) {
            throw new BusinessException(ErrorCode.INSPECTION_DATE_INVALID);
        }
        if (inspectionDate.isAfter(LocalDate.now().plusMonths(MAX_FUTURE_MONTHS))) {
            throw new BusinessException(ErrorCode.INSPECTION_DATE_INVALID);
        }
    }
}
