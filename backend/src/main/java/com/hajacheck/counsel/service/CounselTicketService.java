package com.hajacheck.counsel.service;

import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.repository.CompanyMembershipRepository;
import com.hajacheck.auth.repository.UserRepository;
import com.hajacheck.counsel.dto.CounselTicketResponse;
import com.hajacheck.counsel.dto.CounselTicketSummaryResponse;
import com.hajacheck.counsel.entity.ChatSession;
import com.hajacheck.counsel.entity.ChatSessionType;
import com.hajacheck.counsel.entity.CounselTicket;
import com.hajacheck.counsel.entity.CounselTicketStatus;
import com.hajacheck.counsel.repository.ChatSessionRepository;
import com.hajacheck.counsel.repository.CounselTicketRepository;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import com.hajacheck.membership.entity.Plan;
import com.hajacheck.membership.entity.UserPlan;
import com.hajacheck.membership.entity.UserPlanStatus;
import com.hajacheck.membership.repository.PlanRepository;
import com.hajacheck.membership.repository.UserPlanRepository;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 전문 상담 티켓 라이프사이클(FR-7, #20/HAJA-33) — 생성(WAITING)·상담원 셀프-클레임 배정·종료·오프라인 이탈.
 *
 * <p><b>배정 모델(셀프-클레임)</b>: 자동 push-배정(상담원 프레즌스 트래킹 필요, WS 비정상 종료 시 stale 위험)
 * 대신 상담원이 콘솔에서 대기열을 보고 직접 집는 pull 모델을 쓴다. 스킬 기반 라우팅(counsel_type +
 * counselor_skills)은 별도 이슈(#708)에서 아직 착수 전이라 DB에 없으므로, 이번 구현은 스킬 매칭 없이 진행하되
 * 자격 검증 지점을 {@link #validateAssignmentEligibility}로 격리한다 — #708 머지 후 그 메서드 한 곳만
 * counsel_type 매칭으로 교체하면 REST 계약·WS·상태머신을 건드리지 않는다.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class CounselTicketService {

    private static final String DEST_ASSIGNED = "/queue/counsel/assigned";
    private static final String DEST_ENDED = "/queue/counsel/ended";

    private final CounselTicketRepository ticketRepository;
    private final ChatSessionRepository chatSessionRepository;
    private final UserRepository userRepository;
    private final UserPlanRepository userPlanRepository;
    private final PlanRepository planRepository;
    private final CompanyMembershipRepository companyMembershipRepository;
    private final SimpMessagingTemplate messagingTemplate;

    /**
     * 상담 티켓 생성(WAITING). {@code has_counselor_access=true} 활성 플랜 게이트를 통과한 요청만 허용한다
     * (NlSearchService.requireAiAddon 과 동일한 개인/회사 분기 패턴). queuePosition 은 생성 시점 스냅샷
     * (WAITING 건수 + 1)만 저장하고 이후 재계산하지 않는다 — 실시간 순번은 후속 과제.
     */
    @Transactional
    public CounselTicketResponse createTicket(Long userId) {
        requireCounselorAccess(userId);
        int queuePosition = (int) ticketRepository.countByStatus(CounselTicketStatus.WAITING) + 1;
        CounselTicket ticket = ticketRepository.save(CounselTicket.request(userId, queuePosition));
        return CounselTicketResponse.from(ticket);
    }

    /** 상담원 대기열 — 상태별 목록(생성순 FIFO), 페이지네이션. 기본 사용처는 WAITING 대기열. */
    public Page<CounselTicketSummaryResponse> getQueue(CounselTicketStatus status, Pageable pageable) {
        return ticketRepository.findByStatusOrderByCreatedAtAsc(status, pageable)
                .map(CounselTicketSummaryResponse::from);
    }

    /**
     * 상담원 셀프-클레임 배정 — WAITING 티켓에 자기 자신을 배정하고 COUNSEL 세션을 생성한다. 동시 클레임
     * 경합은 {@code @Version} 낙관적 락(saveAndFlush)이 감지해 한쪽만 성공하고 다른 쪽은 409로 표면화한다.
     */
    @Transactional
    public CounselTicketResponse assignToCounselor(Long ticketId, Long counselorId) {
        CounselTicket ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> new BusinessException(ErrorCode.COUNSEL_TICKET_NOT_FOUND));
        if (ticket.getStatus() != CounselTicketStatus.WAITING) {
            throw new BusinessException(ErrorCode.COUNSEL_SESSION_ASSIGNMENT_CONFLICT);
        }
        validateAssignmentEligibility(ticket, counselorId);

        ChatSession session = chatSessionRepository.save(
                ChatSession.start(ticket.getUserId(), ChatSessionType.COUNSEL));
        ticket.assign(counselorId, session);
        try {
            ticketRepository.saveAndFlush(ticket);
        } catch (ObjectOptimisticLockingFailureException e) {
            // 다른 상담원이 방금 같은 티켓을 집어 lock_version 이 어긋난 경우 — 경합 충돌로 통일 응답.
            throw new BusinessException(ErrorCode.COUNSEL_SESSION_ASSIGNMENT_CONFLICT);
        }

        CounselTicketResponse response = CounselTicketResponse.from(ticket);
        messagingTemplate.convertAndSendToUser(
                String.valueOf(ticket.getUserId()), DEST_ASSIGNED, response);
        return response;
    }

    /**
     * 상담 종료 — 담당 상담원 본인 또는 플랫폼 관리자만 허용. 티켓을 RESOLVED 로 전이하고 상담 세션을 종료한다.
     */
    @Transactional
    public CounselTicketResponse resolve(Long ticketId, Long requesterId, boolean platformAdmin) {
        CounselTicket ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> new BusinessException(ErrorCode.COUNSEL_TICKET_NOT_FOUND));
        if (!platformAdmin && !requesterId.equals(ticket.getCounselorId())) {
            // 미존재와 동일 코드로 통일하지 않고 별도 403 — 대기열/배정은 이미 role 게이트를 통과한 상담원이라
            // 존재 자체는 열거 위협이 아니고, "담당 아님"을 명확히 알려주는 편이 콘솔 UX 에 유용하다.
            throw new BusinessException(ErrorCode.COUNSEL_TICKET_FORBIDDEN);
        }
        ticket.resolve();
        endSession(ticket.getSessionId());
        ticketRepository.saveAndFlush(ticket);

        CounselTicketResponse response = CounselTicketResponse.from(ticket);
        messagingTemplate.convertAndSendToUser(
                String.valueOf(ticket.getUserId()), DEST_ENDED, response);
        return response;
    }

    /** 오프라인 이탈 — 티켓 소유 사용자 본인만 허용. WAITING/IN_PROGRESS 티켓을 OFFLINE_LEFT 로 전이한다. */
    @Transactional
    public CounselTicketResponse leaveOffline(Long ticketId, Long requesterId) {
        CounselTicket ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> new BusinessException(ErrorCode.COUNSEL_TICKET_NOT_FOUND));
        if (!requesterId.equals(ticket.getUserId())) {
            // 미존재/타인 소유 통일 응답 — cross-user IDOR 열거 방지.
            throw new BusinessException(ErrorCode.COUNSEL_TICKET_NOT_FOUND);
        }
        ticket.leaveOffline();
        endSession(ticket.getSessionId());
        ticketRepository.saveAndFlush(ticket);
        return CounselTicketResponse.from(ticket);
    }

    private void endSession(Long sessionId) {
        if (sessionId == null) {
            return;
        }
        chatSessionRepository.findById(sessionId).ifPresent(ChatSession::end);
    }

    /**
     * 배정 자격 검증(격리 지점). 현재는 스킬 매칭 없이 pull 모델이므로 셀프-클레임 상담원의 존재만 확인한다.
     * TODO(#708 머지 후): {@code ticket.counselType} ↔ {@code counselor_skills} 매칭 검증으로 교체한다.
     */
    private void validateAssignmentEligibility(CounselTicket ticket, Long counselorId) {
        if (!userRepository.existsById(counselorId)) {
            throw new BusinessException(ErrorCode.COUNSEL_TICKET_FORBIDDEN);
        }
    }

    /**
     * has_counselor_access=true 인 활성 플랜 게이트(NlSearchService.requireAiAddon 과 동일 구조). 회사 소속
     * (companyId != null)이면 유효한 승인 멤버십까지 확인한 뒤에만 회사 플랜을 조회한다. 실패 시 즉시 중단.
     */
    private void requireCounselorAccess(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        Long companyId = user.getCompanyId();

        Optional<UserPlan> userPlan;
        if (companyId != null) {
            if (!companyMembershipRepository.existsEffectiveApprovedMembership(companyId, userId, Instant.now())) {
                throw new BusinessException(ErrorCode.COUNSEL_PLAN_REQUIRED);
            }
            userPlan = userPlanRepository.findFirstByCompanyIdAndStatusOrderByStartedAtDesc(
                    companyId, UserPlanStatus.ACTIVE);
        } else {
            userPlan = userPlanRepository.findFirstByUserIdAndStatusOrderByStartedAtDesc(
                    userId, UserPlanStatus.ACTIVE);
        }

        if (userPlan.isEmpty()) {
            throw new BusinessException(ErrorCode.COUNSEL_PLAN_REQUIRED);
        }
        // 활성 플랜은 있는데 참조 Plan 행이 없으면 데이터 정합성 오류(500) — MembershipService.findPlan 과 동일 기준.
        Plan plan = planRepository.findById(userPlan.get().getPlanId())
                .orElseThrow(() -> new BusinessException(ErrorCode.PLAN_DATA_INVALID));
        if (!plan.isHasCounselorAccess()) {
            throw new BusinessException(ErrorCode.COUNSEL_PLAN_REQUIRED);
        }
    }
}
