package com.hajacheck.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hajacheck.auth.config.DemoProperties;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 데모 계정 자기보호 가드(#1626) — 판별 규칙(loginId 매칭·대소문자 무시·스위치와 무관)을 고정한다.
 */
class DemoAccountGuardTest {

    private static final String DEMO_LOGIN_ID = "demo-admin@hajacheck.demo";

    private DemoProperties properties;
    private DemoAccountGuard guard;

    @BeforeEach
    void setUp() {
        properties = new DemoProperties();
        properties.setLoginId(DEMO_LOGIN_ID);
        guard = new DemoAccountGuard(properties);
    }

    @Test
    void 데모_loginId와_일치하면_409로_차단한다() {
        assertThatThrownBy(() -> guard.requireNotDemoAccount(DEMO_LOGIN_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.DEMO_ACCOUNT_PROTECTED);
    }

    @Test
    void 대소문자만_다른_이메일도_차단한다() {
        // 이메일은 대소문자 무시 매칭 — 로그인은 되는데 가드만 비켜가는 표기 차이를 없앤다.
        assertThatThrownBy(() -> guard.requireNotDemoAccount("Demo-Admin@HajaCheck.demo"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.DEMO_ACCOUNT_PROTECTED);
    }

    @Test
    void 데모_스위치가_꺼져_있어도_차단은_유지된다() {
        // 데모를 잠시 꺼둔 사이 계정이 변경되면 다시 켰을 때 원클릭 로그인이 깨진다 — enabled 와 무관.
        properties.setEnabled(false);

        assertThatThrownBy(() -> guard.requireNotDemoAccount(DEMO_LOGIN_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.DEMO_ACCOUNT_PROTECTED);
    }

    @Test
    void 다른_이메일과_null은_통과한다() {
        assertThatCode(() -> guard.requireNotDemoAccount("normal@haja.com")).doesNotThrowAnyException();
        assertThatCode(() -> guard.requireNotDemoAccount(null)).doesNotThrowAnyException();
        assertThat(guard.isDemoAccount("normal@haja.com")).isFalse();
        assertThat(guard.isDemoAccount(DEMO_LOGIN_ID)).isTrue();
    }
}
