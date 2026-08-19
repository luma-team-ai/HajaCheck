package com.hajacheck.notification.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.hajacheck.core.analysis.support.InspectionAnalysisNotificationPayload;
import com.hajacheck.core.inspection.repository.InspectionRepository;
import com.hajacheck.core.inspection.repository.InspectionRoundNoProjection;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import com.hajacheck.notification.dto.NotificationResponse;
import com.hajacheck.notification.entity.Notification;
import com.hajacheck.notification.entity.NotificationType;
import com.hajacheck.notification.repository.NotificationRepository;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class NotificationService {

    // 알림 센터는 별도 페이지네이션 UI 없이 최근 이력만 보여주면 되므로(AP-020, PRD FR-9 인앱 폴링
    // 목록), 대시보드 위젯들과 동일하게 상한을 고정한 상위 N건 조회로 충분하다(DashboardService의
    // RECENT_LIMIT 패턴과 동일).
    // openapi.yaml "GET /api/notifications" 설명의 "상위 30건"과 동기화 유지 — 이 값을 바꾸면 계약도 함께 갱신.
    private static final int LIST_LIMIT = 30;

    /**
     * 회차 표기를 갖는 알림 유형(#1706). 이 둘만 payload에 {@code inspectionId}가 있고 부제목이
     * "{roundNo}회차"다 — 나머지 유형(INSPECTION_DUE·COUNSEL_REPLIED·PLAN_* 등)의 payload는 건드리지 않는다.
     */
    private static final Set<NotificationType> ROUND_AWARE_TYPES =
            EnumSet.of(NotificationType.ANALYSIS_DONE, NotificationType.REVIEW_PENDING);

    private final NotificationRepository notificationRepository;
    private final InspectionRepository inspectionRepository;

    /**
     * 로그인 사용자에게 온 알림을 읽음/미읽음 모두 포함해 최신순 상위 {@value #LIST_LIMIT}건 반환한다(AP-020).
     *
     * <p>#1706 — ANALYSIS_DONE/REVIEW_PENDING의 회차 표기는 저장된 payload 문자열이 아니라 <b>조회 시점의
     * 현재 회차</b>로 다시 계산한다. #1702가 점검일 소급 입력 시 회차 번호를 재정렬하므로, 발행 당시 굳혀
     * 저장한 "3회차"가 알림을 클릭해 들어간 화면의 회차와 어긋날 수 있기 때문이다.
     *
     * <p>목록 경로라 알림 건별 단건 조회(N+1)는 금지 — 대상 {@code inspectionId}를 전부 모아 회차 번호만
     * 한 번에 배치 조회한다(최대 {@value #LIST_LIMIT}건이라 IN 절 크기도 유계다).
     *
     * <p>스코프: 알림은 이미 {@code userId} 기준으로만 조회되고, 여기서 추가로 읽는 값은 그 알림 payload에
     * 원래부터 들어 있던 회차 번호뿐이다(점검 본문·회사 정보 등 다른 필드는 읽지 않는다) — 새로 노출되는
     * 데이터가 없으므로 cross-company 유출 경로가 생기지 않는다.
     */
    public List<NotificationResponse> getNotifications(Long userId) {
        List<NotificationResponse> responses = notificationRepository
                .findAllByUserIdOrderByCreatedAtDescIdDesc(userId, PageRequest.of(0, LIST_LIMIT))
                .stream()
                .map(NotificationResponse::from)
                .toList();

        Map<Long, Integer> currentRoundNos = currentRoundNoByInspectionId(responses);
        if (currentRoundNos.isEmpty()) {
            return responses;
        }
        return responses.stream()
                .map(response -> withCurrentRound(response, currentRoundNos))
                .toList();
    }

    /** 회차 표기 알림들의 대상 점검 회차를 한 번에 읽는다(#1706, N+1 방지). */
    private Map<Long, Integer> currentRoundNoByInspectionId(List<NotificationResponse> responses) {
        Set<Long> inspectionIds = responses.stream()
                .map(NotificationService::roundAwareInspectionId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (inspectionIds.isEmpty()) {
            return Map.of();
        }
        return inspectionRepository.findRoundNosByIds(inspectionIds).stream()
                .filter(projection -> projection.getRoundNo() != null)
                .collect(Collectors.toMap(
                        InspectionRoundNoProjection::getId, InspectionRoundNoProjection::getRoundNo));
    }

    /**
     * 대상 점검이 조회되지 않으면(삭제·데모 리셋 등) 저장된 payload를 그대로 둔다 — 부제목을 통째로
     * 비우는 것보다 "발송 당시 회차"를 남기는 편이 사용자에게 정보량이 크고, 지워진 점검의 "현재 회차"는
     * 정의되지 않기 때문이다({@link InspectionAnalysisNotificationPayload#serialize} 주석과 세트).
     */
    private static NotificationResponse withCurrentRound(
            NotificationResponse response, Map<Long, Integer> currentRoundNos) {
        Long inspectionId = roundAwareInspectionId(response);
        if (inspectionId == null) {
            return response;
        }
        return response.withDescription(
                InspectionAnalysisNotificationPayload.describeRound(currentRoundNos.get(inspectionId)));
    }

    /** 회차 표기 대상 알림이면 payload의 inspectionId를, 아니면 null을 돌려준다. */
    private static Long roundAwareInspectionId(NotificationResponse response) {
        if (!ROUND_AWARE_TYPES.contains(NotificationType.valueOf(response.type()))) {
            return null;
        }
        JsonNode payload = response.payload();
        if (payload == null) {
            return null;
        }
        JsonNode inspectionId = payload.get("inspectionId");
        return inspectionId == null || !inspectionId.canConvertToLong() ? null : inspectionId.asLong();
    }

    /**
     * 알림을 읽음 처리한다(멱등). 이미 읽은 본인 알림 재호출도 성공 처리하며, 미존재 또는 타인 소유
     * 알림은 리소스 존재 열거(cross-user IDOR)를 막기 위해 NOTIFICATION_NOT_FOUND(404)로 통일한다.
     */
    @Transactional
    public void markAsRead(Long notificationId, Long userId) {
        if (notificationRepository.markAsReadIfUnread(notificationId, userId) > 0) {
            return;
        }
        if (!notificationRepository.existsByIdAndUserIdAndReadTrue(notificationId, userId)) {
            throw new BusinessException(ErrorCode.NOTIFICATION_NOT_FOUND);
        }
    }

    /**
     * 알림 1건을 삭제한다(알림 센터 개별 닫기 X). 미존재 또는 타인 소유 알림은 markAsRead 와 동일하게
     * 리소스 존재 열거(cross-user IDOR)를 막기 위해 NOTIFICATION_NOT_FOUND(404)로 통일한다.
     *
     * <p>읽음 여부와 무관하게 지운다 — 사용자가 X를 누른 알림은 다시 보이지 않아야 하므로, 읽음 플래그
     * 토글이 아니라 물리 삭제로 처리한다(알림은 원본이 아니라 파생 통지라 보존 가치가 없다).
     */
    @Transactional
    public void delete(Long notificationId, Long userId) {
        if (notificationRepository.deleteByIdAndUserId(notificationId, userId) == 0) {
            throw new BusinessException(ErrorCode.NOTIFICATION_NOT_FOUND);
        }
    }

    /**
     * 사용자에게 알림 1건을 발행한다(NOTI-01, #425). {@code Facility}·{@code INSPECTION_DUE} 같은 특정
     * 도메인/유형을 몰라도 되는 범용 진입점으로, 다른 도메인·다른 알림 유형도 그대로 호출할 수 있게 설계했다.
     * 시설물별 독립 커밋을 위해 클래스 기본값 대신 이 메서드에만 쓰기 트랜잭션을 건다(markAsRead 와 동일 패턴).
     *
     * <p>⚠️ {@code REQUIRES_NEW}로 항상 독립 트랜잭션을 강제한다 — 실측으로 확인된 두 가지 이유가 함께 있다.
     * (1) 호출부가 자체 {@code @Transactional} 메서드일 때 {@code REQUIRED}면 같은 물리 트랜잭션에 합류해,
     * 이 메서드의 DB 예외가 호출부를 rollback-only로 마킹하고 호출부가 커밋 시점에
     * {@code UnexpectedRollbackException}을 던진다(#493 P1). (2) 호출부의 {@code TransactionSynchronization}
     * {@code afterCommit} 콜백 안에서 호출할 때(예: CounselChatService.sendMessage, #993 P2 — "메시지가 실제
     * 커밋된 뒤에만 알림 발행") {@code REQUIRED}로는 이 메서드의 쓰기가 실제로 커밋되지 않고 조용히
     * 유실된다(afterCommit 시점엔 호출부의 트랜잭션 리소스가 스레드에 아직 바인딩돼 있어 REQUIRED가 새
     * 트랜잭션을 열지 않고 이미 커밋 처리 중인 리소스에 편승해버림 — Spring
     * {@code TransactionSynchronization#afterCommit} 공식 문서가 명시적으로 경고하는 함정, 통합테스트로
     * 실제 유실을 재현·확인함). 두 시나리오 모두 {@code REQUIRES_NEW}만이 안전하다.
     *
     * <p>⚠️ 이 메서드는 인가/소유권 검증이 없는 <b>시스템 전용 진입점</b>이다 — 사용자 입력을 직접 이 메서드에
     * 배선하지 말 것(향후 컨트롤러 등에서 임의 userId로 호출하면 알림 위조/IDOR가 된다).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void notify(Long userId, NotificationType type, String payloadJson) {
        notificationRepository.save(Notification.create(userId, type, payloadJson));
    }
}
