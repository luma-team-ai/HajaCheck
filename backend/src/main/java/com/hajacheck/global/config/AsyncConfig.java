package com.hajacheck.global.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * {@code @Async} 활성화 + 메일 발송 전용 실행기. 현재 비동기 용도는 비밀번호 재설정 메일뿐이다(#194).
 *
 * <p>@Async 가 꺼지면 메서드가 <b>동기로 조용히 실행</b>되어(에러 없음) 재설정 1단계에 응답시간 기반
 * 계정 열거가 생긴다. 그래서 이 설정은 기능이 아니라 <b>보안 전제</b>다
 * (PasswordResetMailDispatcher 참조 — 어노테이션 부착 여부를 테스트로 고정해 둠).
 */
@Slf4j
@Configuration
@EnableAsync
public class AsyncConfig {

    /** 메일 발송 전용 실행기 이름 — {@code @Async(MAIL_TASK_EXECUTOR)} 로 참조한다. */
    public static final String MAIL_TASK_EXECUTOR = "mailTaskExecutor";

    /**
     * 메일 발송 전용 실행기.
     *
     * <p><b>왜 Boot 기본 실행기를 쓰지 않나</b>: 기본값은 큐가 무한({@code Integer.MAX_VALUE})이고 종료 시
     * 대기하지 않아, <b>재배포 때 큐에 남은 재설정 메일이 로그 한 줄 없이 사라진다</b>(사용자는 200 을 받았는데
     * 메일이 영영 오지 않음). 유한 큐 + 종료 시 드레인으로 바꾼다. 전용 풀이라 다른 비동기 작업과도 섞이지 않는다.
     *
     * <p>⚠️ <b>거부 정책은 이 기능의 보안과 직결된다.</b> 큐가 가득 찼을 때:
     * <ul>
     *   <li>{@code CallerRunsPolicy} — <b>금지</b>. 요청 스레드에서 동기 발송되어 존재하는 계정만 SMTP 왕복만큼
     *       느려진다 → 이 PR 이 막은 <b>타이밍 기반 계정 열거가 그대로 부활</b>한다.</li>
     *   <li>{@code AbortPolicy}(기본) — <b>그대로 쓰면 안 됨</b>. {@code TaskRejectedException} 이 요청 스레드로
     *       전파돼 존재하는 계정만 500 이 된다 → 이 역시 계정 존재 단서다.</li>
     *   <li>→ 그래서 <b>삼키고 WARN 만</b> 남긴다. 과부하 시 발송은 유실되지만, 응답은 계정 존재 여부와 무관하게
     *       동일하게 유지된다. 이 WARN 이 유실을 알아챌 유일한 신호이므로 알람을 붙일 지점이다.</li>
     * </ul>
     */
    @Bean(name = MAIL_TASK_EXECUTOR)
    public TaskExecutor mailTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        // 유한 큐 — 무한 큐는 SMTP 장애 시 "언젠가 갈 메일"을 메모리에 무한정 쌓는다.
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("mail-");
        // 재배포 시 큐에 남은 메일을 버리지 않고 드레인(최대 20초).
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(20);
        executor.setRejectedExecutionHandler((runnable, threadPoolExecutor) ->
                // ⚠️ 여기서 직접 실행(CallerRuns)하거나 예외를 던지면(Abort) 계정 열거가 되살아난다 — 반드시 삼킨다.
                log.warn("메일 발송 큐 포화 — 재설정 메일 1건을 버립니다(응답은 정상 유지). "
                                + "queueSize={} activeCount={} — 지속되면 큐/풀 크기나 SMTP 지연을 점검할 것.",
                        threadPoolExecutor.getQueue().size(), threadPoolExecutor.getActiveCount()));
        executor.initialize();
        return executor;
    }
}
