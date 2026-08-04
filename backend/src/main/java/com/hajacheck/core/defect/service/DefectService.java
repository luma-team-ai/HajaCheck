package com.hajacheck.core.defect.service;

import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.repository.UserRepository;
import com.hajacheck.auth.service.AuthService;
import com.hajacheck.auth.service.CompanyScopeGuard;
import com.hajacheck.core.defect.dto.DefectActionLogResponse;
import com.hajacheck.core.defect.dto.DefectActionResultRequest;
import com.hajacheck.core.defect.dto.DefectResponse;
import com.hajacheck.core.defect.dto.DefectRevisionResponse;
import com.hajacheck.core.defect.entity.Defect;
import com.hajacheck.core.defect.entity.DefectActionLog;
import com.hajacheck.core.defect.entity.DefectGrade;
import com.hajacheck.core.defect.entity.DefectRevision;
import com.hajacheck.core.defect.entity.DefectStatus;
import com.hajacheck.core.defect.entity.DefectType;
import com.hajacheck.core.defect.repository.DefectActionLogRepository;
import com.hajacheck.core.defect.repository.DefectRepository;
import com.hajacheck.core.defect.repository.DefectRevisionRepository;
import com.hajacheck.core.media.repository.MediaRepository;
import com.hajacheck.global.common.PageResponse;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.DomainValidationException;
import com.hajacheck.global.exception.ErrorCode;
import java.util.ArrayList;
import java.util.List;
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
    private final DefectActionLogRepository defectActionLogRepository;
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

    // 그룹 판정 대상 상태(v0.2, #1456) — DETECTED(검수 전)는 그룹에 포함하지 않는다.
    private static final List<DefectStatus> GROUP_ELIGIBLE_STATUSES =
            List.of(DefectStatus.CONFIRMED, DefectStatus.IN_PROGRESS, DefectStatus.RESOLVED);

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
     *
     * <p>이미지 단위 보수 작업 그룹 팬아웃(v0.2, #1456) — 요청 대상 하자(anchor)와 같은
     * inspection_id+media_id를 가진, 확정(CONFIRMED 이상)·비삭제 하자 전체를 {@link #resolveActionGroup}
     * 으로 묶어 동일한 값을 반복 반영한다. mediaId가 없는 하자는 그룹 크기 1(기존 단독 동작과 동일).
     * 신규 엔티티/컬럼 없이 이 메서드 하나의 {@code @Transactional} 안에서 그룹 전체가 갱신되므로,
     * 그룹 내 아무 하자에서나 낙관적 락(lock_version) 충돌이 나면 트랜잭션 전체가 롤백된다(부분 반영 없음).
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
        Defect anchor = defectRepository.findByIdAndCompanyId(defectId, companyId)
                .orElseThrow(() -> new BusinessException(ErrorCode.DEFECT_NOT_FOUND));
        authService.validateAssignableInspector(userId, request.actionAssigneeId());
        mediaRepository.findByIdAndInspectionId(request.actionMediaId(), anchor.getInspectionId())
                .orElseThrow(() -> new BusinessException(ErrorCode.MEDIA_NOT_FOUND));

        List<Defect> group = resolveActionGroup(anchor);
        for (Defect defect : group) {
            applyActionResult(userId, defect, request, targetStatus);
        }

        String actionAssigneeName = userRepository.findById(request.actionAssigneeId())
                .map(User::getName)
                .orElse(null);
        DefectResponse response = DefectResponse.from(anchor, actionAssigneeName);
        return response.withGroupSummary(group.size(), aggregateGroupStatus(group));
    }

    // registerActionResult()의 하자 1건분 반영 로직 — 그룹 내 각 하자에 동일하게 적용한다.
    private void applyActionResult(
            Long userId, Defect defect, DefectActionResultRequest request, DefectStatus targetStatus) {
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
        // 이번 제출을 append-only 이력으로 남긴다(#1193/HAJA-569) — flat 필드(위)는 "최신 스냅샷"만
        // 유지하지만, 이 테이블은 targetStatus=IN_PROGRESS 유지 재제출을 포함해 모든 제출을 보존한다.
        // 그룹 팬아웃(#1456)에서는 그룹 내 하자마다 로그가 1건씩 남는다(§제약 — 이력 중복 저장 트레이드오프).
        defectActionLogRepository.save(DefectActionLog.record(
                defect.getId(), request.actionMediaId(), targetStatus, request.actionContent(),
                request.actionDate(), request.actionAssigneeId()));
        // 상태가 실제로 바뀐 제출만 활동기록 타임라인(defect_revisions)에 남긴다 — IN_PROGRESS 유지
        // 재제출은 상태 변경이 아니므로 타임라인에 안 남기고 위 이력 테이블에만 남는다(#1193/HAJA-569,
        // 활동기록은 "상태 변경"만 다루는 기존 의미 유지).
        if (previousStatus != defect.getStatus()) {
            defectRevisionRepository.save(DefectRevision.record(
                    defect.getId(), userId, "status", previousStatus.name(), defect.getStatus().name(), null));
        }
    }

    /**
     * 이미지 단위 보수 작업 그룹 해석(v0.2, #1456) — mediaId가 없는 하자는 그룹 크기 1(기존 단독
     * 하자와 동일 취급). mediaId가 있으면 같은 inspection_id+media_id로 확정된(CONFIRMED 이상)
     * 비삭제 하자 전체를 id 오름차순으로 묶는다. id 오름차순 고정은 서로 다른 사용자가 같은 그룹을
     * 서로 다른 하자로 동시에 제출해도 두 트랜잭션이 항상 같은 순서로 행을 갱신하도록 강제해
     * 교착(deadlock)을 방지하기 위함이다(§쓰기 — 신규 엔티티의 단일 lock_version이 없는 대신
     * 애플리케이션이 순서를 고정한다).
     */
    private List<Defect> resolveActionGroup(Defect anchor) {
        if (anchor.getMediaId() == null) {
            return List.of(anchor);
        }
        List<Defect> members = defectRepository.findByInspectionIdAndMediaIdAndStatusInAndDeletedFalseOrderByIdAsc(
                anchor.getInspectionId(), anchor.getMediaId(), GROUP_ELIGIBLE_STATUSES);
        if (members.stream().noneMatch(d -> d.getId().equals(anchor.getId()))) {
            // anchor가 그룹 조회 조건에서 빠지는 경우(예: 방금 CONFIRMED로 전이되어 아직 커밋 전인
            // 동시 요청)에도, 실제 조치 등록 대상은 반드시 그룹에 포함해야 한다 — id 오름차순
            // 불변식은 유지한 채 끼워 넣는다.
            members = new ArrayList<>(members);
            int insertAt = 0;
            while (insertAt < members.size() && members.get(insertAt).getId() < anchor.getId()) {
                insertAt++;
            }
            members.add(insertAt, anchor);
        }
        return members;
    }

    // 그룹 상태 집계(v0.2, §읽기) — 전체 RESOLVED면 RESOLVED, 하나라도 조치 진행 이상(IN_PROGRESS
    // 이상)이면 IN_PROGRESS, 그 외(전부 CONFIRMED)는 CONFIRMED. 저장하지 않고 매 호출 시 계산한다.
    private DefectStatus aggregateGroupStatus(List<Defect> group) {
        boolean allResolved = group.stream().allMatch(d -> d.getStatus() == DefectStatus.RESOLVED);
        if (allResolved) {
            return DefectStatus.RESOLVED;
        }
        boolean anyInProgressOrAbove = group.stream()
                .anyMatch(d -> d.getStatus() == DefectStatus.IN_PROGRESS || d.getStatus() == DefectStatus.RESOLVED);
        return anyInProgressOrAbove ? DefectStatus.IN_PROGRESS : DefectStatus.CONFIRMED;
    }

    /**
     * 조치 등록 제출 이력 조회(#1193/HAJA-569) — 하자 상세 모달 "조치 사진"/"조치 완료 사진" 탭이
     * 항목 2개 이상일 때 등록일 select로 넘겨보는 이력이다. findByIdAndCompanyId로 회사 범위를
     * 먼저 검증해 cross-company IDOR을 차단한다(get()/getRevisions()와 동일 원칙). 담당자 이름은
     * 건수가 적어(조치 등록 폼 제출 횟수만큼) row별 조회로 충분하다.
     */
    public List<DefectActionLogResponse> getActionLogs(
            Long userId, Long companyId, Long defectId, DefectStatus phase) {
        companyScopeGuard.requireEffectiveMembership(userId, companyId);
        if (phase != DefectStatus.IN_PROGRESS && phase != DefectStatus.RESOLVED) {
            throw new DomainValidationException(
                    "조치 이력 조회 불가: phase는 IN_PROGRESS/RESOLVED만 지정할 수 있다 (요청 phase=%s)"
                            .formatted(phase));
        }
        defectRepository.findByIdAndCompanyId(defectId, companyId)
                .orElseThrow(() -> new BusinessException(ErrorCode.DEFECT_NOT_FOUND));
        List<DefectActionLog> logs =
                defectActionLogRepository.findByDefectIdAndPhaseOrderByCreatedAtDesc(defectId, phase);
        return logs.stream()
                .map(log -> DefectActionLogResponse.from(log, userRepository.findById(log.getActionAssigneeId())
                        .map(User::getName)
                        .orElse(null)))
                .toList();
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
