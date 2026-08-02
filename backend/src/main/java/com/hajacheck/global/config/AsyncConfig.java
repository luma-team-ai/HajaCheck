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

    /** AI 하자 탐지 분석 잡(dev-05-04) 전용 실행기 — {@code @Async(ANALYSIS_TASK_EXECUTOR)}로 참조한다. */
    public static final String ANALYSIS_TASK_EXECUTOR = "analysisTaskExecutor";

    /**
     * 분석 잡 전용 실행기. mailTaskExecutor와 풀을 공유하지 않는다(장기 실행 CPU 바운드 작업이
     * 짧은 메일 발송 큐를 막으면 안 됨).
     *
     * <p>동시 잡 수를 작게 유지하는 이유: FastAPI 쪽 YOLO 추론은 CPU 바운드 단일 워커 전제(§AI 서버
     * 워커 분리 문서)라, 여기서 과도하게 병렬 호출해도 FastAPI에서 직렬화될 뿐 처리량이 늘지 않고
     * 요청만 쌓인다. 큐가 가득 차면(동시 분석 요청 폭주) 기본 AbortPolicy로 예외를 던져 컨트롤러가
     * 그대로 클라이언트에 실패를 알린다(메일과 달리 사용자 열거 이슈가 없어 조용히 삼킬 이유가 없음).
     *
     * <p><b>테넌트 격리(코드 리뷰 P2 4차)</b>: 이 풀 자체는 여전히 회사 구분 없는 전역 공유다 — 회사별
     * 파티셔닝은 아니다. 대신 {@link com.hajacheck.core.analysis.service.InspectionAnalysisService}가
     * 이 풀에 넣기 전에 회사별 동시 실행 상한(코어 스레드 수와 동일하게 맞춤)으로 한 회사가 큐 전체를
     * 독점하지 못하도록 최소 방어선을 둔다 — 완전한 격리가 필요해지면 회사별 큐 분리로 승격할 것.
     */
    @Bean(name = ANALYSIS_TASK_EXECUTOR)
    public TaskExecutor analysisTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("analysis-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

    /** RAG 임베딩 완료 확인 폴러(#1328) 전용 실행기 — {@code @Async(RAG_EMBED_TASK_EXECUTOR)}로 참조한다. */
    public static final String RAG_EMBED_TASK_EXECUTOR = "ragEmbedTaskExecutor";

    /**
     * RagEmbeddingCompletionPoller 전용 실행기(#1328). FastAPI가 청킹만 동기로 끝내고 실제 임베딩은
     * BackgroundTasks로 넘기게 되면서(ai-server 16ffe3bb), Spring이 그 응답만 보고 completeEmbedding()을
     * 호출하면 아직 Chroma에 반영되지 않은 상태를 DONE으로 잘못 마킹하는 거짓 완료가 생긴다. 이 실행기는
     * 짧은 간격(2~3초)으로 최대 10회 재시도 폴링(Thread.sleep 포함)하는 동안 요청 스레드와 완전히 분리된
     * 별도 스레드에서 동작해야 한다 — HTTP 응답은 이미 즉시 나간 뒤이므로 여기서 sleep해도 nginx/사용자
     * 타임아웃과 무관하다.
     *
     * <p>mailTaskExecutor/analysisTaskExecutor와 풀을 공유하지 않는다(폴링 중 sleep으로 스레드를 오래
     * 점유하는 성격이 다른 두 실행기의 처리량에 영향을 주면 안 됨). 큐가 가득 차면 기본 AbortPolicy로
     * 예외를 던진다 — 계정 열거 등 보안 민감 경로가 아니므로 mailTaskExecutor처럼 조용히 삼킬 이유가
     * 없고, 폴러가 아예 시작되지 못한 경우는 문서가 EMBEDDING 상태로 남아 관리자가 재임베딩으로 복구
     * 가능하다(idempotent 설계, RagDocumentService 참고).
     */
    @Bean(name = RAG_EMBED_TASK_EXECUTOR)
    public TaskExecutor ragEmbedTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("rag-embed-poll-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
