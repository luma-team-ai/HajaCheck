package com.hajacheck.counsel.service;

import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.repository.CompanyMembershipRepository;
import com.hajacheck.auth.repository.UserRepository;
import com.hajacheck.counsel.dto.ChatMessageResponse;
import com.hajacheck.counsel.dto.CounselTicketResponse;
import com.hajacheck.counsel.dto.CounselTicketSummaryResponse;
import com.hajacheck.counsel.entity.BotScenario;
import com.hajacheck.counsel.entity.ChatMessage;
import com.hajacheck.counsel.entity.ChatSession;
import com.hajacheck.counsel.entity.ChatSessionType;
import com.hajacheck.counsel.entity.CounselTicket;
import com.hajacheck.counsel.entity.CounselTicketStatus;
import com.hajacheck.counsel.entity.CounselType;
import com.hajacheck.counsel.entity.CounselorSkillId;
import com.hajacheck.counsel.repository.BotScenarioRepository;
import com.hajacheck.counsel.repository.ChatMessageRepository;
import com.hajacheck.counsel.repository.ChatSessionRepository;
import com.hajacheck.counsel.repository.CounselTicketRepository;
import com.hajacheck.counsel.repository.CounselorSkillRepository;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import com.hajacheck.membership.entity.Plan;
import com.hajacheck.membership.entity.UserPlan;
import com.hajacheck.membership.entity.UserPlanStatus;
import com.hajacheck.membership.repository.PlanRepository;
import com.hajacheck.membership.repository.UserPlanRepository;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 전문 상담 티켓 라이프사이클(FR-7, #20/HAJA-33) — 생성(WAITING)·상담원 셀프-클레임 배정·종료·오프라인 이탈
 * + 내 상담 이력 조회·대화 조회·트랜스크립트 내보내기.
 *
 * <p><b>배정 모델(셀프-클레임)</b>: 자동 push-배정(상담원 프레즌스 트래킹 필요, WS 비정상 종료 시 stale 위험)
 * 대신 상담원이 콘솔에서 대기열을 보고 직접 집는 pull 모델을 쓴다. 스킬 기반 라우팅(counsel_type +
 * counselor_skills, #743/#772)이 머지돼 자격 검증 지점 {@link #validateAssignmentEligibility}에서
 * 실제로 매칭한다 — 시나리오 category → counselType 매핑은 {@link #resolveCounselType}에 격리한다.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class CounselTicketService {

    private static final String DEST_ASSIGNED = "/queue/counsel/assigned";
    private static final String DEST_ENDED = "/queue/counsel/ended";
    private static final DateTimeFormatter TRANSCRIPT_TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final CounselTicketRepository ticketRepository;
    private final ChatSessionRepository chatSessionRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final BotScenarioRepository botScenarioRepository;
    private final CounselorSkillRepository counselorSkillRepository;
    private final UserRepository userRepository;
    private final UserPlanRepository userPlanRepository;
    private final PlanRepository planRepository;
    private final CompanyMembershipRepository companyMembershipRepository;
    private final SimpMessagingTemplate messagingTemplate;

    /**
     * 상담 티켓 생성(WAITING). {@code has_counselor_access=true} 활성 플랜 게이트를 통과한 요청만 허용한다.
     * {@code scenarioId}는 상담원 연결을 유발하는 리프(leadsToCounselor=true)여야 하며, 그 트리를 타고 올라가
     * 최상위 category와 바로 위 부모 라벨을 스냅샷으로 저장한다(시나리오 트리 변경과 무관하게 이력 고정).
     * ticket_number 는 PK 확정 후 부여하므로 같은 트랜잭션 내 2단계 저장한다.
     */
    @Transactional
    public CounselTicketResponse createTicket(Long userId, Long scenarioId) {
        requireCounselorAccess(userId);
        ScenarioSnapshot snapshot = resolveScenarioSnapshot(scenarioId);

        int queuePosition = (int) ticketRepository.countByStatus(CounselTicketStatus.WAITING) + 1;
        CounselType counselType = resolveCounselType(snapshot.category());
        CounselTicket ticket = ticketRepository.saveAndFlush(
                CounselTicket.request(userId, counselType, queuePosition, snapshot.category(), snapshot.title()));
        ticket.assignTicketNumber(
                CounselTicket.formatTicketNumber(ticket.getCreatedAt(), ticket.getId()));
        ticketRepository.saveAndFlush(ticket);
        return CounselTicketResponse.from(ticket, resolveCounselorName(ticket.getCounselorId()));
    }

    /**
     * 상담원 대기열 — 상태별 목록(생성순 FIFO), 페이지네이션. 기본 사용처는 WAITING 대기열.
     *
     * <p>#1019/HAJA-501 — 클레임 시점({@link #validateAssignmentEligibility})에만 스킬을 검증하던 걸
     * 조회 시점까지 당겨온다. {@code platformAdmin}은 운영 모니터링 목적으로 필터 예외(전체 노출) —
     * {@link #resolve}의 {@code platformAdmin} 파라미터와 동일하게 컨트롤러가 role 을 판별해 넘긴다.
     */
    public Page<CounselTicketSummaryResponse> getQueue(
            CounselTicketStatus status, Pageable pageable, Long requesterId, boolean platformAdmin) {
        Page<CounselTicket> page = platformAdmin
                ? ticketRepository.findByStatusOrderByCreatedAtAsc(status, pageable)
                : findQueueForCounselor(status, requesterId, pageable);
        Map<Long, String> names = resolveCounselorNames(page.getContent());
        return page.map(ticket -> CounselTicketSummaryResponse.from(ticket, nameOf(names, ticket)));
    }

    /** COUNSELOR 전용 대기열 — 본인 보유 스킬(counselType) 밖 티켓은 제외한다. 스킬 미보유면 빈 페이지. */
    private Page<CounselTicket> findQueueForCounselor(
            CounselTicketStatus status, Long counselorId, Pageable pageable) {
        List<CounselType> skills = counselorSkillRepository.findCounselTypesByCounselorId(counselorId);
        if (skills.isEmpty()) {
            return Page.empty(pageable);
        }
        return ticketRepository.findByStatusAndCounselTypeInOrderByCreatedAtAsc(status, skills, pageable);
    }

    /**
     * 내 상담 이력 — 요청자 본인 티켓만(최신순). {@code status}가 null 이면 전체, 아니면 해당 상태만.
     * userId 는 세션 주체({@code @AuthenticationPrincipal})에서만 채운다 — 요청 파라미터로 받지 않아 IDOR 차단.
     */
    public Page<CounselTicketSummaryResponse> getMyTickets(
            Long userId, CounselTicketStatus status, Pageable pageable) {
        Page<CounselTicket> tickets = (status == null)
                ? ticketRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable)
                : ticketRepository.findByUserIdAndStatusOrderByCreatedAtDesc(userId, status, pageable);
        Map<Long, String> names = resolveCounselorNames(tickets.getContent());
        return tickets.map(ticket -> CounselTicketSummaryResponse.from(ticket, nameOf(names, ticket)));
    }

    /** 티켓 전체 대화 이력(시간순). 당사자(사용자 본인/담당 상담원)만 — 아니면 열거 방지 통일 응답. */
    public List<ChatMessageResponse> getMessages(Long ticketId, Long requesterId) {
        CounselTicket ticket = loadParticipantTicket(ticketId, requesterId);
        // 티켓의 담당 상담원 이름을 1회 조회해 COUNSELOR 발신 메시지에만 부여한다(DTO from 이 발신자 유형으로 분기).
        String counselorName = resolveCounselorName(ticket.getCounselorId());
        return loadMessages(ticket).stream()
                .map(message -> ChatMessageResponse.from(message, ticket.getId(), counselorName))
                .toList();
    }

    /** 대화 내보내기 — 당사자만. 전체 대화를 평문 텍스트 트랜스크립트(UTF-8)로 변환해 반환한다. */
    public Transcript exportTranscript(Long ticketId, Long requesterId) {
        CounselTicket ticket = loadParticipantTicket(ticketId, requesterId);
        StringBuilder sb = new StringBuilder();
        sb.append("상담 티켓: ").append(ticket.getTicketNumber()).append('\n');
        sb.append("카테고리: ").append(ticket.getCategory()).append('\n');
        sb.append("제목: ").append(ticket.getTitle()).append('\n');
        sb.append("상태: ").append(ticket.getStatus()).append('\n');
        sb.append("생성: ").append(ticket.getCreatedAt().format(TRANSCRIPT_TS)).append('\n');
        sb.append("----------------------------------------\n");
        for (ChatMessage message : loadMessages(ticket)) {
            sb.append('[').append(message.getCreatedAt().format(TRANSCRIPT_TS)).append("] ")
                    .append(message.getSender()).append(": ")
                    .append(message.getContent() == null ? "" : message.getContent());
            if (message.getAttachmentKey() != null) {
                sb.append(" [이미지 첨부]");
            }
            sb.append('\n');
        }
        byte[] content = sb.toString().getBytes(StandardCharsets.UTF_8);
        return new Transcript(ticket.getTicketNumber() + ".txt", content);
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

        CounselTicketResponse response =
                CounselTicketResponse.from(ticket, resolveCounselorName(counselorId));
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

        CounselTicketResponse response =
                CounselTicketResponse.from(ticket, resolveCounselorName(ticket.getCounselorId()));
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
        return CounselTicketResponse.from(ticket, resolveCounselorName(ticket.getCounselorId()));
    }

    private List<ChatMessage> loadMessages(CounselTicket ticket) {
        if (ticket.getSessionId() == null) {
            return List.of();
        }
        return chatMessageRepository.findBySessionIdOrderByCreatedAtAsc(ticket.getSessionId());
    }

    /** 당사자(사용자 본인/담당 상담원)만 접근 가능한 티켓 로드. 비당사자·미존재는 열거 방지 통일 응답(404). */
    private CounselTicket loadParticipantTicket(Long ticketId, Long requesterId) {
        CounselTicket ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> new BusinessException(ErrorCode.COUNSEL_TICKET_NOT_FOUND));
        boolean participant = Objects.equals(ticket.getUserId(), requesterId)
                || Objects.equals(ticket.getCounselorId(), requesterId);
        if (!participant) {
            throw new BusinessException(ErrorCode.COUNSEL_TICKET_NOT_FOUND);
        }
        return ticket;
    }

    /**
     * 시나리오 리프에서 category(최상위)/title(바로 위 부모 라벨) 스냅샷을 도출한다. leadsToCounselor=false
     * 노드에서 진입하면 잘못된 진입점이므로 거부한다.
     */
    private ScenarioSnapshot resolveScenarioSnapshot(Long scenarioId) {
        BotScenario leaf = botScenarioRepository.findById(scenarioId)
                .orElseThrow(() -> new BusinessException(ErrorCode.COUNSEL_SCENARIO_NOT_FOUND));
        if (!leaf.isLeadsToCounselor()) {
            throw new BusinessException(ErrorCode.COUNSEL_TICKET_FORBIDDEN);
        }
        // 바로 위 부모 라벨 = title. 부모가 없으면(최상위 리프) 자기 라벨을 title 로 사용.
        BotScenario parent = leaf.getParentId() == null ? leaf
                : botScenarioRepository.findById(leaf.getParentId())
                .orElseThrow(() -> new BusinessException(ErrorCode.COUNSEL_SCENARIO_NOT_FOUND));
        String title = parent.getButtonLabel();

        // 최상위(parent_id NULL)까지 올라가 category 스냅샷.
        BotScenario cursor = parent;
        while (cursor.getParentId() != null) {
            cursor = botScenarioRepository.findById(cursor.getParentId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.COUNSEL_SCENARIO_NOT_FOUND));
        }
        return new ScenarioSnapshot(cursor.getCategory(), title);
    }

    private void endSession(Long sessionId) {
        if (sessionId == null) {
            return;
        }
        chatSessionRepository.findById(sessionId).ifPresent(ChatSession::end);
    }

    /**
     * 배정 자격 검증(격리 지점). 상담사 존재 확인 후, {@code counselor_skills}에 티켓의 counselType을
     * 처리 가능하다고 등록돼 있는지 확인한다(#743/#772 스킬 기반 라우팅).
     */
    private void validateAssignmentEligibility(CounselTicket ticket, Long counselorId) {
        if (!userRepository.existsById(counselorId)) {
            throw new BusinessException(ErrorCode.COUNSEL_TICKET_FORBIDDEN);
        }
        boolean hasSkill = counselorSkillRepository.existsById(
                new CounselorSkillId(counselorId, ticket.getCounselType()));
        if (!hasSkill) {
            throw new BusinessException(ErrorCode.COUNSEL_SKILL_MISMATCH);
        }
    }

    /**
     * 시나리오 최상위 category → counselType 매핑(격리 지점). category(4종)와 counselType(3종, #743)이
     * 1:1이 아니라 여기서 명시적으로 대응시킨다 — INSPECTION_REPORT(점검 결과서 관련)는 분석 결과 문의가
     * 주 사례라 ANALYSIS_RESULT로, ERROR_REPORT(오류 신고)는 정형 분류가 없어 기타 성격인 BILLING_ETC로
     * 묶는다(팀 결정, 2026-07-25). USAGE_GUIDE는 현재 시드(V17)에 상담원 연결 리프가 없어 셀프서비스
     * 전용이지만, 매핑은 향후 리프 추가에 대비해 선제로 둔다.
     *
     * <p>default 분기는 "시나리오 미존재"(COUNSEL_SCENARIO_NOT_FOUND)와 구분한다 — 여기 도달했다는 건
     * bot_scenarios에 실존하는 category인데 이 매핑 테이블 갱신이 누락됐다는 뜻(신규 category 시드만 하고
     * 이 switch를 안 고친 경우)이라, PLAN_DATA_INVALID와 동일 성격의 데이터 정합성 오류로 취급한다.
     */
    private static CounselType resolveCounselType(String category) {
        return switch (category) {
            case "INSPECTION_REPORT" -> CounselType.ANALYSIS_RESULT;
            case "ACCOUNT_BILLING" -> CounselType.BILLING_ETC;
            case "USAGE_GUIDE" -> CounselType.USAGE;
            case "ERROR_REPORT" -> CounselType.BILLING_ETC;
            default -> throw new BusinessException(ErrorCode.COUNSEL_TYPE_MAPPING_NOT_FOUND);
        };
    }

    /** 상담원 표시 이름 단건 조회. counselorId 가 null 이거나(미배정) 탈퇴 등으로 없으면 null. */
    private String resolveCounselorName(Long counselorId) {
        if (counselorId == null) {
            return null;
        }
        return userRepository.findById(counselorId).map(User::getName).orElse(null);
    }

    /** 목록 조회용 배치 이름 조회 — 페이지 내 counselorId 들을 한 번에 모아 조회해 N+1 을 방지한다. */
    private Map<Long, String> resolveCounselorNames(List<CounselTicket> tickets) {
        Set<Long> counselorIds = tickets.stream()
                .map(CounselTicket::getCounselorId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (counselorIds.isEmpty()) {
            return Map.of();
        }
        return userRepository.findAllById(counselorIds).stream()
                .collect(Collectors.toMap(User::getId, User::getName));
    }

    private String nameOf(Map<Long, String> names, CounselTicket ticket) {
        return ticket.getCounselorId() == null ? null : names.get(ticket.getCounselorId());
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

    private record ScenarioSnapshot(String category, String title) {
    }

    /** 트랜스크립트 내보내기 결과 — 파일명 + UTF-8 본문 바이트. */
    public record Transcript(String fileName, byte[] content) {
    }
}
