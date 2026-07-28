package com.hajacheck.core.defect.service;

import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.repository.UserRepository;
import com.hajacheck.auth.service.AuthService;
import com.hajacheck.auth.service.CompanyScopeGuard;
import com.hajacheck.core.defect.dto.DefectActionResultRequest;
import com.hajacheck.core.defect.dto.DefectResponse;
import com.hajacheck.core.defect.dto.DefectRevisionResponse;
import com.hajacheck.core.defect.entity.Defect;
import com.hajacheck.core.defect.entity.DefectGrade;
import com.hajacheck.core.defect.entity.DefectRevision;
import com.hajacheck.core.defect.entity.DefectStatus;
import com.hajacheck.core.defect.entity.DefectType;
import com.hajacheck.core.defect.repository.DefectRepository;
import com.hajacheck.core.defect.repository.DefectRevisionRepository;
import com.hajacheck.core.media.repository.MediaRepository;
import com.hajacheck.global.common.PageResponse;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.DomainValidationException;
import com.hajacheck.global.exception.ErrorCode;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 하자 목록·상세 조회 및 상태 전이(HAJA-26) — 모든 조회/변경은 로그인 사용자의 회사가 소유한
 * facilities.company_id 범위로만 제한한다(cross-company IDOR 방지, facility 도메인과 동일 원칙).
 * 상태 전이 규칙(정방향 순서 강제, 역행/건너뛰기는 사유 필수)은 Defect#changeStatus 가 담당하고,
 * 전이가 성공하면 defect_revisions에 append-only 이력을 남긴다(PRD FR-4).
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class DefectService {

    private final DefectRepository defectRepository;
    private final DefectRevisionRepository defectRevisionRepository;
    private final CompanyScopeGuard companyScopeGuard;
    private final AuthService authService;
    private final MediaRepository mediaRepository;
    private final UserRepository userRepository;

    public PageResponse<DefectResponse> list(
            Long userId, Long companyId, DefectType type, DefectGrade grade,
            DefectStatus status, Pageable pageable) {
        companyScopeGuard.requireEffectiveMembership(userId, companyId);
        Page<Defect> page = defectRepository.findPageByCompanyIdAndFilters(companyId, type, grade, status, pageable);
        return PageResponse.from(page.map(DefectResponse::from));
    }

    /**
     * 조치 결과 등록(HAJA-393/#725, "조치 완료 등록" 버튼) — 담당자는 #690과 동일 자격 조건
     * (authService.validateAssignableInspector, 활성·INSPECTOR/ADMIN·유효 승인 멤버십)으로 검증하고,
     * 조치 후 사진은 같은 점검 소속 media인지 확인해(findByIdAndInspectionId) 둘 다 cross-company
     * IDOR을 차단한다. 상태전이(targetStatus)는 Defect#registerActionResult 가 changeStatus() 규칙을
     * 재사용해 처리하므로, 여기서는 기존 updateStatus()와 동일하게 defect_revisions에 이력만 남긴다.
     *
     * <p>targetStatus(#1128)는 폼의 "진행상태" select 값이다. 조치 등록의 타겟이 될 수 있는 값은
     * IN_PROGRESS/RESOLVED 두 개뿐이라 그 외(DETECTED/CONFIRMED)는 여기서 먼저 거부한다 — 실제로도
     * changeStatus()의 정방향 규칙에 걸려 대부분 막히지만, "타겟이 될 수 없는 값"이라는 의도를 명시적
     * 검증으로 남겨 둔다(요청 바디는 신뢰하지 않는다는 원칙).
     */
    @Transactional
    public DefectResponse registerActionResult(
            Long userId, Long companyId, Long defectId, DefectActionResultRequest request) {
        companyScopeGuard.requireEffectiveMembership(userId, companyId);
        DefectStatus targetStatus = request.targetStatus();
        if (targetStatus != DefectStatus.IN_PROGRESS && targetStatus != DefectStatus.RESOLVED) {
            throw new DomainValidationException(
                    "조치 결과 등록 불가: 진행상태는 IN_PROGRESS/RESOLVED만 지정할 수 있다 (요청 상태=%s)"
                            .formatted(targetStatus));
        }
        Defect defect = defectRepository.findByIdAndCompanyId(defectId, companyId)
                .orElseThrow(() -> new BusinessException(ErrorCode.DEFECT_NOT_FOUND));
        authService.validateAssignableInspector(userId, request.actionAssigneeId());
        mediaRepository.findByIdAndInspectionId(request.actionMediaId(), defect.getInspectionId())
                .orElseThrow(() -> new BusinessException(ErrorCode.MEDIA_NOT_FOUND));

        DefectStatus previousStatus = defect.getStatus();
        // 조치 필드(사진/내용)는 1세트만 존재해 targetStatus=RESOLVED 2차 등록이 IN_PROGRESS 1차
        // 등록분을 덮어쓴다(#1128 코드리뷰 P2-1) — 덮어써지기 직전 값을 감사기록으로 먼저 남겨
        // 무기록 소실을 막는다. previousActionContent가 null이면 최초 등록이라 남길 이전 값이 없다.
        String previousActionContent = defect.getActionContent();
        Long previousActionMediaId = defect.getActionMediaId();
        if (previousActionContent != null) {
            defectRevisionRepository.save(DefectRevision.record(
                    defect.getId(), userId, "actionContent",
                    truncateForRevision(previousActionContent), truncateForRevision(request.actionContent()), null));
            defectRevisionRepository.save(DefectRevision.record(
                    defect.getId(), userId, "actionMediaId",
                    String.valueOf(previousActionMediaId), String.valueOf(request.actionMediaId()), null));
        }
        defect.registerActionResult(
                request.actionMediaId(), request.actionContent(), request.actionDate(), request.actionAssigneeId(),
                targetStatus);
        defectRevisionRepository.save(DefectRevision.record(
                defect.getId(), userId, "status", previousStatus.name(), defect.getStatus().name(), null));

        String actionAssigneeName = userRepository.findById(request.actionAssigneeId())
                .map(User::getName)
                .orElse(null);
        return DefectResponse.from(defect, actionAssigneeName);
    }

    // defect_revisions.old_value/new_value 는 varchar(255)인데 조치 내용은 최대 2000자까지
    // 허용되므로(DefectActionResultRequest), 감사기록 저장 전 컬럼 폭에 맞춰 자른다.
    private static String truncateForRevision(String value) {
        return value.length() > 255 ? value.substring(0, 255) : value;
    }

    public DefectResponse get(Long userId, Long companyId, Long defectId) {
        companyScopeGuard.requireEffectiveMembership(userId, companyId);
        Defect defect = defectRepository.findByIdAndCompanyId(defectId, companyId)
                .orElseThrow(() -> new BusinessException(ErrorCode.DEFECT_NOT_FOUND));
        // 조치 결과 등록(HAJA-393/#725) 이후 하자 상세 모달을 다시 열었을 때도 담당자 이름이
        // 채워지도록, actionAssigneeId가 있으면 조회한다(list()는 요약형이라 이 조회를 생략).
        String actionAssigneeName = defect.getActionAssigneeId() == null ? null
                : userRepository.findById(defect.getActionAssigneeId()).map(User::getName).orElse(null);
        // 시설물 담당자 이름(#970 갭3) — Facility.assigneeUserId 재사용(팀 결정, defects 테이블 변경
        // 없음). findByIdAndCompanyId가 join fetch d.inspection i join fetch i.facility f로 이미
        // Facility까지 즉시 로딩하므로 추가 쿼리 없이 필드만 읽는다.
        Long facilityAssigneeUserId = defect.getInspection().getFacility().getAssigneeUserId();
        String assigneeName = facilityAssigneeUserId == null ? null
                : userRepository.findById(facilityAssigneeUserId).map(User::getName).orElse(null);
        return DefectResponse.from(defect, actionAssigneeName, assigneeName);
    }

    /**
     * 하자 위치 사후 편집(#970 갭3) — 조치 등록과 분리된 가벼운 편집 엔드포인트라 회사 스코프
     * 인가만 재사용하고 상태 전이 규칙은 관여하지 않는다(삭제된 하자만 Defect#updateLocation이 거부).
     */
    @Transactional
    public DefectResponse updateLocation(Long userId, Long companyId, Long defectId, String location) {
        companyScopeGuard.requireEffectiveMembership(userId, companyId);
        Defect defect = defectRepository.findByIdAndCompanyId(defectId, companyId)
                .orElseThrow(() -> new BusinessException(ErrorCode.DEFECT_NOT_FOUND));
        defect.updateLocation(location);
        return DefectResponse.from(defect);
    }

    /**
     * 회차 간 대응 하자 확정(HAJA-437) — previousDefectId가 가리키는 하자를 findByIdAndCompanyId로
     * 조회해 (a) 같은 회사 스코프인지부터 확인하고(미존재/타사 소유는 이미 여기서 걸러짐), 이어서
     * (b) 같은 시설물, (c) 현재 하자보다 더 이전 회차(roundNo가 더 작음)인지를 검증한다. 셋 중
     * 하나라도 어긋나면 DEFECT_PREVIOUS_DEFECT_INVALID로 통일 응답한다(구체적으로 어느 조건이
     * 깨졌는지는 노출하지 않음 — 다른 회사 하자 id를 넣어보며 존재 여부를 추정하는 것을 방지).
     * previousDefectId == defectId(자기 자신 참조)인 경우도 roundNo 비교(같은 값은 "더 이전"이
     * 아님)로 자연히 거부된다.
     */
    @Transactional
    public DefectResponse confirmPreviousDefect(Long userId, Long companyId, Long defectId, Long previousDefectId) {
        companyScopeGuard.requireEffectiveMembership(userId, companyId);
        Defect defect = defectRepository.findByIdAndCompanyId(defectId, companyId)
                .orElseThrow(() -> new BusinessException(ErrorCode.DEFECT_NOT_FOUND));
        Defect previousDefect = defectRepository.findByIdAndCompanyId(previousDefectId, companyId)
                .orElseThrow(() -> new BusinessException(ErrorCode.DEFECT_PREVIOUS_DEFECT_INVALID));

        Long facilityId = defect.getInspection().getFacility().getId();
        Long previousFacilityId = previousDefect.getInspection().getFacility().getId();
        boolean sameFacility = facilityId.equals(previousFacilityId);
        boolean earlierRound = previousDefect.getInspection().getRoundNo() < defect.getInspection().getRoundNo();
        if (!sameFacility || !earlierRound) {
            throw new BusinessException(ErrorCode.DEFECT_PREVIOUS_DEFECT_INVALID);
        }

        defect.confirmPreviousDefect(previousDefectId);
        return DefectResponse.from(defect);
    }

    /**
     * 하자 활동 기록 타임라인 조회(HAJA-314) — findByIdAndCompanyId로 회사 범위를 먼저 검증해
     * cross-company IDOR을 차단한 뒤에만 defect_revisions를 조회한다(get()과 동일 원칙).
     */
    public PageResponse<DefectRevisionResponse> getRevisions(
            Long userId, Long companyId, Long defectId, Pageable pageable) {
        companyScopeGuard.requireEffectiveMembership(userId, companyId);
        defectRepository.findByIdAndCompanyId(defectId, companyId)
                .orElseThrow(() -> new BusinessException(ErrorCode.DEFECT_NOT_FOUND));
        Page<DefectRevision> page = defectRevisionRepository.findByDefectIdOrderByCreatedAtDesc(defectId, pageable);
        return PageResponse.from(page.map(DefectRevisionResponse::from));
    }

    @Transactional
    public DefectResponse updateStatus(
            Long revisedByUserId, Long companyId, Long defectId, DefectStatus status, String reason) {
        companyScopeGuard.requireEffectiveMembership(revisedByUserId, companyId);
        Defect defect = defectRepository.findByIdAndCompanyId(defectId, companyId)
                .orElseThrow(() -> new BusinessException(ErrorCode.DEFECT_NOT_FOUND));
        DefectStatus previousStatus = defect.getStatus();
        defect.changeStatus(status, reason);
        defectRevisionRepository.save(DefectRevision.record(
                defect.getId(), revisedByUserId, "status", previousStatus.name(), status.name(), reason));
        return DefectResponse.from(defect);
    }

}
