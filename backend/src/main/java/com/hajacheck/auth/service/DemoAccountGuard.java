package com.hajacheck.auth.service;

import com.hajacheck.auth.config.DemoProperties;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.repository.UserRepository;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 데모 계정 자기보호 가드(#1626) — 데모 계정을 대상으로 한 <b>변경 시도</b>를 한곳의 판정으로 차단한다.
 *
 * <p>데모 세션은 기업 ADMIN 전권을 그대로 가지므로, 방문자가 관리자 콘솔에서 데모 계정 자신을
 * 강등·정지하거나 비밀번호를 바꾸면 <b>다음 방문자의 원클릭 로그인이 통째로 깨진다</b>(비밀번호가
 * 바뀌는 순간 서버 보관 크레덴셜과 어긋난다). 그래서 대상이 데모 계정이면 role/status/비밀번호 변경을
 * {@code DEMO_ACCOUNT_PROTECTED}(409)로 통일 차단한다.
 *
 * <p>판별은 {@link DemoProperties#isDemoLoginId(String)}(설정 기반 loginId=email 매칭) 하나다 —
 * 스키마 변경 없이 시더·로그인·isDemo 응답과 같은 기준을 공유한다. <b>스위치(enabled)와 무관하게</b>
 * 항상 검사한다: 데모를 잠시 꺼둔 사이에도 시드된 계정이 변경되면 다시 켰을 때 로그인이 깨진다.
 * (데모를 아예 쓰지 않는 환경에는 이 loginId 의 계정 자체가 없어 가드가 발동할 일이 없다.)
 *
 * <p>적용 지점: {@code AdminUserService}·{@code PlatformAdminUserService} 의 changeRole/changeStatus,
 * {@code PasswordChangeService}(로그인 후 비밀번호 변경), 그리고 {@code CounselTicketService#createTicket}
 * (#1631 — 아래 "상담 티켓 생성" 문단 참고). 비로그인 이메일 재설정({@code PasswordResetService})은 응답
 * 통일 계약(항상 200/토큰 무효 통일) 때문에 409 를 던지지 않고 {@link #isDemoAccount(String)} 로 조용히
 * 걸러낸다. 탈퇴/회원삭제 엔드포인트는 현재 코드베이스에 존재하지 않는다(생기면 이 가드를 반드시 태울 것).
 *
 * <p><b>결제·플랜변경은 이 가드 대상이 아니다</b>(#1631 — #1626 P1-1 이 걸었던
 * {@code PaymentService.createOrder/confirm}·{@code AdminPlanService.changePlan/scheduleChange} 차단을
 * 해제). 데모 계정은 FREE 로 시작해 방문자가 직접 토스 샌드박스로 결제·업그레이드하는 것까지가 온보딩
 * 시연 범위이고, 실결제가 없는 샌드박스인 데다 매일 04:10 리셋({@code DemoResetService})이 비-FREE
 * plan/payments/scheduled 를 정리해 FREE 로 강제 정합하므로 안전판이 이미 있다.
 *
 * <p><b>상담 티켓 생성은 예외적으로 다시 막는다</b>(#1631 security P2). 결제가 열리면서 방문자가
 * STANDARD/ENTERPRISE(hasCounselorAccess=true)까지 얻을 수 있게 됐는데, {@code CounselTicketService
 * #createTicket}이 만드는 WAITING 대기열은 <b>실제 상담원 콘솔과 공유</b>되고({@code countByStatus}가
 * 회사 스코프 없이 전역 집계, {@code /topic/counsel-queue} 전역 브로드캐스트) 데모 티켓이 실사용자
 * 순번을 왜곡하고 실직원의 시간을 뺏는다 — 리셋이 티켓 행을 지워도 그날 실제로 낭비된 상담원 시간은
 * 되돌릴 수 없다(불가역). 그래서 이 진입점만은 {@link #requireNotDemoAccountUser(Long)} 로 다시 막는다.
 * 결제·플랜변경 자체(주문 생성·승인·변경)는 계속 열려 있다 — 막는 것은 "상담 티켓 생성" 하나뿐이다.
 *
 * <p>이 클래스가 막는 것은 결국 <b>데모 계정 자기보호</b>(비밀번호·역할·상태·삭제)와 <b>실직원 보호</b>
 * (상담 티켓 생성) 둘이다 — 방문자가 다음 방문자의 원클릭 로그인을 깨뜨리거나 실담당자의 대기열을
 * 어지럽히는 변경은 여전히 차단한다.
 */
@Component
@RequiredArgsConstructor
public class DemoAccountGuard {

    private final DemoProperties demoProperties;
    private final UserRepository userRepository;

    /** 데모 계정 여부(설정 loginId 매칭) — 응답 통일 계약이 있는 경로의 조용한 필터용. */
    public boolean isDemoAccount(String email) {
        return demoProperties.isDemoLoginId(email);
    }

    /** 대상이 데모 계정이면 {@code DEMO_ACCOUNT_PROTECTED}(409)로 차단한다. */
    public void requireNotDemoAccount(String email) {
        if (isDemoAccount(email)) {
            throw new BusinessException(ErrorCode.DEMO_ACCOUNT_PROTECTED);
        }
    }

    /**
     * userId 로 데모 계정 여부를 확인해 차단한다(#1631) — 상담 티켓 생성처럼 세션의 userId 만 알고
     * 이메일을 손에 쥐지 않은 진입부용. 미존재 userId 는 데모가 아니므로 통과(방어적 no-op).
     *
     * <p>읽기 전용 조회라 {@code readOnly=true} 로 연다 — 호출부가 자신의 쓰기 트랜잭션을 열기 전
     * 진입 가드로 부르므로, 여기서 별도 트랜잭션을 시작해도 부작용이 없다.
     */
    @Transactional(readOnly = true)
    public void requireNotDemoAccountUser(Long userId) {
        if (userId == null) {
            return;
        }
        userRepository.findById(userId)
                .map(User::getEmail)
                .filter(this::isDemoAccount)
                .ifPresent(email -> {
                    throw new BusinessException(ErrorCode.DEMO_ACCOUNT_PROTECTED);
                });
    }
}
