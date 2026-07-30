package com.hajacheck.membership.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * {@link PlanExpiryProperties} 의 <b>부팅 시점 검증 배선</b>을 고정한다(#1145 / HAJA-549, 리뷰 P3-B).
 *
 * <p><b>왜 별도 테스트인가</b>: P1-2("컷오프 없이 ENFORCE 금지")의 강제 수단이 곧 <b>기동 실패</b>다.
 * 그런데 {@code PlanExpirySchedulerTest} 의 검증 테스트들은 {@code Validation.buildDefaultValidatorFactory()}
 * 로 <b>제약 선언만</b> 확인한다 — 누군가 {@code @Validated} 를 지우거나 {@code @EnableConfigurationProperties}
 * 등록을 옮기면 제약은 그대로인데 <b>아무도 그것을 실행하지 않는</b> 상태가 되고, "ENFORCE + 컷오프
 * 미설정"이 조용히 기동해 P1-2 가 회귀한다. 여기서는 실제 바인딩 경로(@ConfigurationProperties +
 * @Validated + JSR-303)를 태워 <b>컨텍스트가 정말 뜨지 않는지</b>를 확인한다.
 */
class PlanExpiryPropertiesBindingTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(MembershipConfig.class);

    @Test
    @DisplayName("실제 강등 조합(enabled=true + ENFORCE)인데 컷오프가 없으면 컨텍스트 기동이 실패한다")
    void 강등조합에_컷오프가_없으면_기동실패() {
        contextRunner
                .withPropertyValues(
                        "hajacheck.plan.expiry.enabled=true",
                        "hajacheck.plan.expiry.mode=ENFORCE")
                .run(context -> assertThat(context)
                        .as("이 검증이 실행되지 않으면 V27 백필 추정치 전 구간이 첫 회차에 일괄 강등된다")
                        .hasFailed());
    }

    @Test
    @DisplayName("컷오프를 주면 정상 기동한다")
    void 컷오프가_있으면_기동한다() {
        contextRunner
                .withPropertyValues(
                        "hajacheck.plan.expiry.enabled=true",
                        "hajacheck.plan.expiry.mode=ENFORCE",
                        "hajacheck.plan.expiry.not-before=2026-08-01T00:00:00Z")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(PlanExpiryProperties.class).getNotBefore())
                            .isEqualTo(Instant.parse("2026-08-01T00:00:00Z"));
                });
    }

    @Test
    @DisplayName("전 구간을 명시적으로 선언하면(not-before-unbounded) 정상 기동한다")
    void 명시적_전구간_선언은_기동한다() {
        contextRunner
                .withPropertyValues(
                        "hajacheck.plan.expiry.enabled=true",
                        "hajacheck.plan.expiry.mode=ENFORCE",
                        "hajacheck.plan.expiry.not-before-unbounded=true")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    @DisplayName("배치가 꺼져 있으면(enabled=false) mode=ENFORCE에 컷오프가 없어도 앱은 정상 기동한다")
    void 꺼진_배치설정으로는_앱이_죽지_않는다() {
        // 리뷰 NEW-A — 이 조합은 배치가 아무 일도 할 수 없는 상태라 막을 이유가 없다. 여기서 기동을
        // 막으면 "꺼진 배치의 설정 하나"로 서비스 전체가 내려간다(#531 형태의 기동 실패, arm1 자동배포).
        contextRunner
                .withPropertyValues(
                        "hajacheck.plan.expiry.enabled=false",
                        "hajacheck.plan.expiry.mode=ENFORCE")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    @DisplayName("not-before와 not-before-unbounded를 동시에 주면 기동이 실패한다(의도 모호)")
    void 컷오프_이중선언은_기동실패() {
        contextRunner
                .withPropertyValues(
                        "hajacheck.plan.expiry.enabled=true",
                        "hajacheck.plan.expiry.mode=ENFORCE",
                        "hajacheck.plan.expiry.not-before=2026-08-01T00:00:00Z",
                        "hajacheck.plan.expiry.not-before-unbounded=true")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("유예 기간이 음수면 기동이 실패한다")
    void 음수_유예는_기동실패() {
        contextRunner
                .withPropertyValues("hajacheck.plan.expiry.grace-period=-P30D")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("설정을 하나도 주지 않으면 안전한 기본값(비활성 + DRY_RUN)으로 기동한다")
    void 기본값으로_기동한다() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            PlanExpiryProperties properties = context.getBean(PlanExpiryProperties.class);
            assertThat(properties.isEnabled()).isFalse();
            assertThat(properties.getMode()).isEqualTo(PlanExpiryProperties.Mode.DRY_RUN);
            assertThat(properties.isEnforcing()).isFalse();
        });
    }
}
