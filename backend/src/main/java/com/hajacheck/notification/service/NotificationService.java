package com.hajacheck.notification.service;

import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import com.hajacheck.notification.dto.NotificationResponse;
import com.hajacheck.notification.entity.Notification;
import com.hajacheck.notification.entity.NotificationType;
import com.hajacheck.notification.repository.NotificationRepository;
import java.util.List;
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

    private final NotificationRepository notificationRepository;

    /** 로그인 사용자에게 온 알림을 읽음/미읽음 모두 포함해 최신순 상위 {@value #LIST_LIMIT}건 반환한다(AP-020). */
    public List<NotificationResponse> getNotifications(Long userId) {
        return notificationRepository
                .findAllByUserIdOrderByCreatedAtDescIdDesc(userId, PageRequest.of(0, LIST_LIMIT))
                .stream()
                .map(NotificationResponse::from)
                .toList();
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
