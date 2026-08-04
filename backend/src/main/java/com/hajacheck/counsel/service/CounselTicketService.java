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
import com.hajacheck.counsel.entity.ChatSenderType;
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
import com.hajacheck.membership.service.PaymentGraceService;
import com.hajacheck.membership.repository.UserPlanRepository;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
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
    // 상담원 대기열 실시간 갱신(#1001 후속, 사용자 피드백: "신청하면 상담원이 새로고침해야 보임").
    // 티켓 내용은 담지 않고 신호만 보낸다 — 구독은 COUNSELOR/PLATFORM_ADMIN로 제한되지만
    // (StompAuthChannelInterceptor), 그래도 최소 정보 원칙을 지켜 프론트가 REST로 재조회하게 한다.
    private static final String DEST_QUEUE_UPDATED = "/topic/counsel-queue";
    private static final int CUSTOMER_HISTORY_SIZE = 20;
    private static final DateTimeFormatter TRANSCRIPT_TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // 내보내기(.txt) 전용 한글 라벨 — 프론트 constants.ts의 CATEGORY_LABEL과 동일한 문구를 유지한다.
    // 프론트 전용 상수라 백엔드에서 재사용할 수 없어 이 자리에 별도로 둔다(#1506 후속: 내보내기 파일에
    // ACCOUNT_BILLING 같은 raw 코드가 그대로 노출되던 문제).
    private static final Map<String, String> EXPORT_CATEGORY_LABEL = Map.of(
            "ACCOUNT_BILLING", "계정 및 결제",
            "ERROR_REPORT", "오류 신고",
            "INSPECTION_REPORT", "점검 결과서 관련",
            "USAGE_GUIDE", "이용 방법 안내");

    private static final Map<CounselTicketStatus, String> EXPORT_STATUS_LABEL = Map.of(
            CounselTicketStatus.WAITING, "배정 대기중",
            CounselTicketStatus.IN_PROGRESS, "상담중",
            CounselTicketStatus.RESOLVED, "상담 완료",
            CounselTicketStatus.OFFLINE_LEFT, "고객 이탈 종료");

    private final CounselTicketRepository ticketRepository;
    private final ChatSessionRepository chatSessionRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final BotScenarioRepository botScenarioRepository;
    private final CounselorSkillRepository counselorSkillRepository;
    private final UserRepository userRepository;
    private final UserPlanRepository userPlanRepository;
    private final PlanRepository planRepository;
    // 미결제 유예(#1177) — 유예 중이면 엔타이틀먼트를 FREE 로 낮춘다(무결제 유료 기능 사용 차단).
    private final PaymentGraceService paymentGraceService;
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
        messagingTemplate.convertAndSend(DEST_QUEUE_UPDATED, "WAITING_TICKET_CREATED");
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

    /**
     * COUNSELOR 전용 대기열 — WAITING 은 본인 보유 스킬(counselType) 밖 티켓을 제외(#1019/HAJA-501)하고,
     * 그 외 상태(IN_PROGRESS 등, #1001 후속)는 이미 배정이 끝난 티켓이라 스킬이 아니라 담당자 본인
     * 여부로 좁힌다 — 그래야 "종료되지 않은 내 상담"이 콘솔 목록에 계속 보인다.
     */
    private Page<CounselTicket> findQueueForCounselor(
            CounselTicketStatus status, Long counselorId, Pageable pageable) {
        if (status != CounselTicketStatus.WAITING) {
            return ticketRepository.findByStatusAndCounselorIdOrderByCreatedAtAsc(status, counselorId, pageable);
        }
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

    /**
     * 티켓 전체 대화 이력(시간순). 당사자(사용자 본인/담당 상담원)만 — 아니면 열거 방지 통일 응답.
     *
     * <p>{@code platformAdmin=true}(#1168) 면 당사자 여부와 무관하게 조회를 허용한다(관리자 콘솔의
     * 날짜별 상담 목록에서 임의 티켓의 대화를 열람). 기존 호출부(고객/상담원 콘솔)는 항상
     * {@code platformAdmin=false} 고정 전달 — 비당사자는 여전히 거부된다(회귀 없음).
     */
    public List<ChatMessageResponse> getMessages(Long ticketId, Long requesterId, boolean platformAdmin) {
        CounselTicket ticket = platformAdmin
                ? ticketRepository.findById(ticketId)
                        .orElseThrow(() -> new BusinessException(ErrorCode.COUNSEL_TICKET_NOT_FOUND))
                : loadParticipantTicket(ticketId, requesterId);
        // 티켓의 담당 상담원 이름을 1회 조회해 COUNSELOR 발신 메시지에만 부여한다(DTO from 이 발신자 유형으로 분기).
        String counselorName = resolveCounselorName(ticket.getCounselorId());
        return loadMessages(ticket).stream()
                .map(message -> ChatMessageResponse.from(message, ticket.getId(), counselorName))
                .toList();
    }

    /**
     * 플랫폼 관리자 날짜별 상담 목록(#1168) — 접수일(createdAt) 기준 해당 날짜(00:00~23:59:59.999999,
     * 서버 기본 타임존)에 접수된 티켓 전체(최신순). 인가는 컨트롤러의 PLATFORM_ADMIN role 게이트를 전제한다
     * (이 메서드 자체는 그 role 게이트를 통과한 컨트롤러 경로에서만 참조돼야 한다).
     *
     * <p><b>타임존 계약(#1205 머신 리뷰 P3 확인 결과)</b> — 경계 계산에 {@code ZoneId}를 명시하지 않는 것은
     * 누락이 아니라 의도된 설계다. {@link CounselTicket#getCreatedAt()} 은 {@code @CreatedDate} 가 채우는
     * 존 정보 없는 {@code LocalDateTime}(= 서버 벽시계 그대로)이고, 여기서 만드는 조회 경계도 같은 서버
     * 벽시계다 — 저장과 조회가 동일 기준이라 구조적으로 정합하며, 이 값을 {@code ZoneId} 로 UTC 환산하면
     * 오히려 저장값과 9시간 어긋난다. 따라서 이 조회의 정확성은 "서버 기본 타임존 고정"에만 의존한다:
     * {@code backend/Dockerfile}({@code -Duser.timezone=Asia/Seoul})과 {@code docker-compose.yml}
     * ({@code TZ: Asia/Seoul})이 이를 이중으로 고정한다(과거 가입일이 9시간 밀려 보이던 회귀의 재발 방지책).
     * 서버를 다른 타임존으로 띄우면 저장·조회가 함께 밀려 관리자가 보는 날짜 경계가 어긋나므로, 배포 환경의
     * TZ 설정을 바꾸려면 이 조회와 {@code createdAt} 저장 경로를 함께 재검토해야 한다.
     */
    public Page<CounselTicketSummaryResponse> getAdminTicketsByDate(LocalDate date, Pageable pageable) {
        LocalDateTime start = date.atStartOfDay();
        // PG timestamp 컬럼은 마이크로초 정밀도까지만 저장한다 — 나노초 단위(minusNanos(1))로 끊으면
        // 999999999ns가 저장 시 마이크로초로 반올림돼 자정으로 올림(캐리)되는 경계 버그가 난다
        // (리뷰에서 실제 재현: 다음날 자정 티켓이 당일 조회에 포함됨). 1마이크로초(1000ns) 앞에서 끊는다.
        LocalDateTime end = date.plusDays(1).atStartOfDay().minusNanos(1000);
        Page<CounselTicket> page =
                ticketRepository.findByCreatedAtBetweenOrderByCreatedAtDescIdDesc(start, end, pageable);
        Map<Long, String> counselorNames = resolveCounselorNames(page.getContent());
        Map<Long, CustomerProfile> customerProfiles = resolveCustomerProfiles(page.getContent());
        return page.map(ticket -> {
            CustomerProfile profile = customerProfiles.get(ticket.getUserId());
            return CounselTicketSummaryResponse.fromAdmin(
                    ticket,
                    nameOf(counselorNames, ticket),
                    profile == null ? null : profile.name(),
                    profile == null ? null : profile.email(),
                    profile == null ? null : profile.planName(),
                    profile == null ? null : profile.joinedAt());
        });
    }

    /** 대화 내보내기 — 당사자만. 전체 대화를 평문 텍스트 트랜스크립트(UTF-8)로 변환해 반환한다. */
    public Transcript exportTranscript(Long ticketId, Long requesterId) {
        CounselTicket ticket = loadParticipantTicket(ticketId, requesterId);
        String counselorName = resolveCounselorName(ticket.getCounselorId());
        StringBuilder sb = new StringBuilder();
        sb.append("상담 티켓: ").append(ticket.getTicketNumber()).append('\n');
        sb.append("카테고리: ").append(EXPORT_CATEGORY_LABEL.getOrDefault(ticket.getCategory(), ticket.getCategory())).append('\n');
        sb.append("제목: ").append(ticket.getTitle()).append('\n');
        sb.append("상태: ").append(EXPORT_STATUS_LABEL.get(ticket.getStatus())).append('\n');
        sb.append("생성: ").append(ticket.getCreatedAt().format(TRANSCRIPT_TS)).append('\n');
        sb.append("----------------------------------------\n");
        for (ChatMessage message : loadMessages(ticket)) {
            sb.append('[').append(message.getCreatedAt().format(TRANSCRIPT_TS)).append("] ")
                    .append(exportSenderLabel(message.getSender(), counselorName)).append(": ")
                    .append(message.getContent() == null ? "" : message.getContent());
            if (message.getAttachmentKey() != null) {
                sb.append(" [이미지 첨부]");
            }
            sb.append('\n');
        }
        byte[] content = sb.toString().getBytes(StandardCharsets.UTF_8);
        return new Transcript(ticket.getTicketNumber() + ".txt", content);
    }

    // 내보내기 발신자 라벨 — 고객은 계정 실명 대신 "고객"으로 익명화(파일이 상담원 손을 떠나
    // 공유될 수 있어 개인정보 노출을 피한다), 상담원은 담당자 실명, 봇은 "챗봇"으로 표기한다.
    private String exportSenderLabel(ChatSenderType sender, String counselorName) {
        return switch (sender) {
            case USER -> "고객";
            case BOT -> "챗봇";
            case COUNSELOR -> counselorName != null ? "상담원 " + counselorName : "상담원";
        };
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
        // #1506 — 상담원 콘솔도 자신이 방금 배정받은 티켓을 알아야 한다(그래야 목록/화면이 즉시 갱신).
        messagingTemplate.convertAndSendToUser(
                String.valueOf(counselorId), DEST_ASSIGNED, response);
        return response;
    }

    /**
     * 상담 종료 — 담당 상담원 본인·티켓 소유 고객 본인·플랫폼 관리자만 허용(#1000 후속: 고객도 종료 가능).
     * 티켓을 RESOLVED 로 전이하고 상담 세션을 종료한다.
     */
    @Transactional
    public CounselTicketResponse resolve(Long ticketId, Long requesterId, boolean platformAdmin) {
        CounselTicket ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> new BusinessException(ErrorCode.COUNSEL_TICKET_NOT_FOUND));
        boolean isOwner = requesterId.equals(ticket.getUserId());
        boolean isCounselor = requesterId.equals(ticket.getCounselorId());
        if (!platformAdmin && !isOwner && !isCounselor) {
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
        // #1506 — 종료 시점 진입 전 ticket.resolve()가 IN_PROGRESS만 허용하므로 counselorId는 항상 non-null.
        // 담당 상담원 콘솔도 종료 사실을 실시간으로 알아야 "종료됨" 배지가 즉시 반영된다.
        messagingTemplate.convertAndSendToUser(
                String.valueOf(ticket.getCounselorId()), DEST_ENDED, response);
        // 활성 채팅 목록(대기열 IN_PROGRESS 필터) 실시간 갱신 — createTicket과 동일 패턴, 신호만 전달.
        messagingTemplate.convertAndSend(DEST_QUEUE_UPDATED, "TICKET_RESOLVED");
        return response;
    }

    /**
     * 고객 상담 이력(#1001 후속) — 이 티켓의 담당 상담원 본인 또는 PLATFORM_ADMIN만 조회 가능하다.
     * userId만으로 임의 고객의 이력을 열람하게 하지 않고, "지금 담당 중인 티켓"이라는 정당한 접점이
     * 있는 요청자만 허용한다(resolve()와 동일한 인가 패턴). 현재 보고 있는 티켓 자신은 제외한다
     * (채팅창에 이미 표시되므로 이력 목록에 중복 노출할 필요가 없다).
     */
    public List<CounselTicketSummaryResponse> getCustomerHistory(
            Long ticketId, Long requesterId, boolean platformAdmin) {
        CounselTicket ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> new BusinessException(ErrorCode.COUNSEL_TICKET_NOT_FOUND));
        if (!platformAdmin && !requesterId.equals(ticket.getCounselorId())) {
            throw new BusinessException(ErrorCode.COUNSEL_TICKET_FORBIDDEN);
        }
        Pageable pageable = PageRequest.of(0, CUSTOMER_HISTORY_SIZE);
        Page<CounselTicket> history = ticketRepository.findByUserIdOrderByCreatedAtDesc(ticket.getUserId(), pageable);
        Map<Long, String> names = resolveCounselorNames(history.getContent());
        return history.getContent().stream()
                .filter(other -> !other.getId().equals(ticketId))
                .map(other -> CounselTicketSummaryResponse.from(other, nameOf(names, other)))
                .toList();
    }

    /**
     * 고객 이력 티켓의 대화 조회(#1001 후속) — getCustomerHistory와 동일한 인가 규칙을 대화 내용에도
     * 적용한다. historyTicketId 는 그 자체 담당자가 요청자와 다를 수 있어(과거에 다른 상담원이
     * 처리) loadParticipantTicket(본인만 허용)을 그대로 쓰면 403이 난다 — 대신 "지금 담당 중인
     * ticketId 티켓과 같은 고객(userId)의 티켓인가"로 접근을 허용한다.
     */
    public List<ChatMessageResponse> getCustomerHistoryMessages(
            Long ticketId, Long historyTicketId, Long requesterId, boolean platformAdmin) {
        CounselTicket anchor = ticketRepository.findById(ticketId)
                .orElseThrow(() -> new BusinessException(ErrorCode.COUNSEL_TICKET_NOT_FOUND));
        if (!platformAdmin && !requesterId.equals(anchor.getCounselorId())) {
            throw new BusinessException(ErrorCode.COUNSEL_TICKET_FORBIDDEN);
        }
        CounselTicket history = ticketRepository.findById(historyTicketId)
                .orElseThrow(() -> new BusinessException(ErrorCode.COUNSEL_TICKET_NOT_FOUND));
        if (!Objects.equals(history.getUserId(), anchor.getUserId())) {
            // 다른 고객 티켓 ID를 끼워 넣는 시도 — 존재 자체를 숨기는 통일 응답.
            throw new BusinessException(ErrorCode.COUNSEL_TICKET_NOT_FOUND);
        }
        String counselorName = resolveCounselorName(history.getCounselorId());
        return loadMessages(history).stream()
                .map(message -> ChatMessageResponse.from(message, history.getId(), counselorName))
                .toList();
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
        CounselTicketResponse response =
                CounselTicketResponse.from(ticket, resolveCounselorName(ticket.getCounselorId()));
        // #1506 — 이전엔 아무에게도 알리지 않아 상담원 콘솔이 사용자 이탈을 몰랐다(활성 채팅에 "상담 중"
        // 잔존 → 상담원이 종료를 누르면 409). WAITING 상태에서 이탈하면 counselorId가 없을 수 있다.
        if (ticket.getCounselorId() != null) {
            messagingTemplate.convertAndSendToUser(
                    String.valueOf(ticket.getCounselorId()), DEST_ENDED, response);
        }
        messagingTemplate.convertAndSend(DEST_QUEUE_UPDATED, "TICKET_RESOLVED");
        return response;
    }

    /**
     * 티켓 단건 상태 조회(#1506) — 고객 화면이 WS 재연결 시점에 REST로 최신 상태를 백필하기 위한 폴백
     * 엔드포인트. 당사자(사용자 본인/담당 상담원) 또는 PLATFORM_ADMIN만 허용 — {@link #getMessages}와
     * 동일한 인가 패턴({@link #loadParticipantTicket}).
     */
    public CounselTicketResponse getTicket(Long ticketId, Long requesterId, boolean platformAdmin) {
        CounselTicket ticket = platformAdmin
                ? ticketRepository.findById(ticketId)
                        .orElseThrow(() -> new BusinessException(ErrorCode.COUNSEL_TICKET_NOT_FOUND))
                : loadParticipantTicket(ticketId, requesterId);
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

    /**
     * 플랫폼 관리자 날짜별 목록용 배치 고객 프로필 조회(#1168) — 이름/이메일은 {@code UserRepository}에서,
     * 활성 플랜명·가입일은 {@code UserPlanRepository}/{@code PlanRepository}에서 배치로 모아
     * {@code resolveCounselorNames}와 동일한 N+1 방지 패턴으로 조합한다.
     *
     * <p><b>플랜 소유 주체 분기(#1263)</b> — {@code user_plans} 는 owner XOR
     * ({@code ck_user_plans_owner_xor})라 회사 귀속 구독 행은 {@code user_id IS NULL} 이다. 따라서 개인
     * 구독만 조회하면 <b>회사 소속 고객의 요금제가 항상 null</b> 로 떨어진다(권한은 유료인데 관리자 화면엔
     * "-" 로 보임). 상담 권한 판정이 {@link #requireCounselorAccess} 에서 companyId 유무로 갈리는 것과
     * 동일하게, 여기서도 {@code user.companyId != null} 이면 회사 구독을, 아니면 개인 구독을 읽는다.
     * 두 조회 모두 IN 배치라 고객 수와 무관하게 쿼리는 2개 고정이다.
     *
     * <p><b>표시 요금제는 effective plan</b>(#1177) — 미결제 유예 중이면 발급된 요금제 이름이 유료여도
     * 실제 엔타이틀먼트는 FREE 다. 원본 이름을 그대로 보여주면 관리자 화면의 "PREMIUM" 과 실권한(FREE)이
     * 어긋나므로 {@code PaymentGraceService#resolveEffectivePlan} 단일 소스를 거쳐 이름을 얻는다.
     */
    private Map<Long, CustomerProfile> resolveCustomerProfiles(List<CounselTicket> tickets) {
        Set<Long> userIds = tickets.stream()
                .map(CounselTicket::getUserId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (userIds.isEmpty()) {
            return Map.of();
        }
        List<User> users = userRepository.findAllById(userIds);
        Set<Long> individualIds = users.stream()
                .filter(user -> user.getCompanyId() == null)
                .map(User::getId)
                .collect(Collectors.toSet());
        Set<Long> companyIds = users.stream()
                .map(User::getCompanyId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        Map<Long, UserPlan> planByUserId = individualIds.isEmpty() ? Map.of()
                : userPlanRepository.findByUserIdInAndStatus(individualIds, UserPlanStatus.ACTIVE).stream()
                        .collect(Collectors.toMap(UserPlan::getUserId, up -> up, CounselTicketService::latestPlan));
        Map<Long, UserPlan> planByCompanyId = companyIds.isEmpty() ? Map.of()
                : userPlanRepository.findByCompanyIdInAndStatus(companyIds, UserPlanStatus.ACTIVE).stream()
                        .collect(Collectors.toMap(UserPlan::getCompanyId, up -> up, CounselTicketService::latestPlan));

        Set<Long> planIds = Stream.concat(planByUserId.values().stream(), planByCompanyId.values().stream())
                .map(UserPlan::getPlanId)
                .collect(Collectors.toSet());
        Map<Long, Plan> planById = planIds.isEmpty() ? Map.of()
                : planRepository.findAllById(planIds).stream()
                        .collect(Collectors.toMap(Plan::getId, plan -> plan));

        Map<Long, CustomerProfile> profiles = new HashMap<>();
        for (User user : users) {
            UserPlan activePlan = user.getCompanyId() == null
                    ? planByUserId.get(user.getId())
                    : planByCompanyId.get(user.getCompanyId());
            profiles.put(user.getId(), new CustomerProfile(
                    user.getName(), user.getEmail(), resolvePlanName(activePlan, planById), user.getCreatedAt()));
        }
        return profiles;
    }

    /**
     * 표시용 요금제 이름 — 구독이 없거나 참조 {@code Plan} 행이 사라진 경우는 null(관리자 화면에서 "-").
     * 여기서 500을 던지지 않는 것은 {@link #requireCounselorAccess} 와 의도적으로 다르다: 저쪽은 권한
     * 판정이라 정합성 오류를 숨기면 안 되지만, 이쪽은 읽기 전용 목록이라 고객 한 명의 데이터 이상으로
     * 페이지 전체가 500이 되는 편이 더 나쁘다.
     */
    private String resolvePlanName(UserPlan activePlan, Map<Long, Plan> planById) {
        if (activePlan == null) {
            return null;
        }
        Plan plan = planById.get(activePlan.getPlanId());
        if (plan == null) {
            return null;
        }
        return paymentGraceService.resolveEffectivePlan(activePlan, plan).getName().name();
    }

    /**
     * 같은 주체에 활성 구독 행이 둘 이상일 때의 타이브레이커 — {@code startedAt} 최신을 고른다
     * ({@code findFirstBy...OrderByStartedAtDesc} 를 쓰는 권한 판정 경로와 같은 기준). 배치 조회에서
     * {@code Collectors.toMap} 의 기본 동작은 키 중복 시 예외라, 병합 함수 없이 두면 데이터 이상 하나로
     * 관리자 목록 전체가 500이 된다.
     */
    private static UserPlan latestPlan(UserPlan left, UserPlan right) {
        if (left.getStartedAt() == null) {
            return right;
        }
        if (right.getStartedAt() == null) {
            return left;
        }
        return right.getStartedAt().isAfter(left.getStartedAt()) ? right : left;
    }

    private record CustomerProfile(String name, String email, String planName, LocalDateTime joinedAt) {
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
        // ⚠️ 미결제 유예(#1177)면 요금제 이름이 유료여도 엔타이틀먼트는 FREE 다(리뷰 P1). 유료→유료
        // 하향은 결제 없이 대상 요금제를 발급하는데, 이 게이트가 원본 요금제를 그대로 읽으면
        // <b>상담사 연결을 무상으로</b> 쓸 수 있다 — QuotaService 의 한도 3종만 낮추는 것으로는 막히지
        // 않는 경로다(이 판정은 QuotaService 를 거치지 않는다). 판정은 PaymentGraceService 단일 소스.
        plan = paymentGraceService.resolveEffectivePlan(userPlan.get(), plan);
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
