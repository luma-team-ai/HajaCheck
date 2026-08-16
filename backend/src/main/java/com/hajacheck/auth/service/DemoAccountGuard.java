package com.hajacheck.auth.service;

import com.hajacheck.auth.config.DemoProperties;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

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
 * {@code PasswordChangeService}(로그인 후 비밀번호 변경). 비로그인 이메일 재설정
 * ({@code PasswordResetService})은 응답 통일 계약(항상 200/토큰 무효 통일) 때문에 409 를 던지지 않고
 * {@link #isDemoAccount(String)} 로 조용히 걸러낸다. 탈퇴/회원삭제 엔드포인트는 현재 코드베이스에
 * 존재하지 않는다(생기면 이 가드를 반드시 태울 것).
 *
 * <p><b>결제·플랜변경은 이 가드 대상이 아니다</b>(#1631 — #1626 P1-1 이 걸었던
 * {@code PaymentService.createOrder/confirm}·{@code AdminPlanService.changePlan/scheduleChange} 차단을
 * 해제). 데모 계정은 FREE 로 시작해 방문자가 직접 토스 샌드박스로 결제·업그레이드하는 것까지가 온보딩
 * 시연 범위이고, 실결제가 없는 샌드박스인 데다 매일 04:10 리셋({@code DemoResetService})이 비-FREE
 * plan/payments/scheduled 를 정리해 FREE 로 강제 정합하므로 안전판이 이미 있다. 이 클래스가 여전히
 * 막는 것은 <b>데모 계정 자기보호</b>(비밀번호·역할·상태·삭제)뿐이다 — 방문자가 다음 방문자의 원클릭
 * 로그인을 깨뜨리는 변경은 여전히 차단한다.
 */
@Component
@RequiredArgsConstructor
public class DemoAccountGuard {

    private final DemoProperties demoProperties;

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
}
