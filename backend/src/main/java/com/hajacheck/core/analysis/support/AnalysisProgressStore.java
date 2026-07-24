package com.hajacheck.core.analysis.support;

import com.hajacheck.core.analysis.dto.AnalysisStatusResponse;
import java.util.Optional;

/**
 * AI 분석 진행 상태 캐시(dev-05-04) — 구현을 인터페이스 뒤로 감춰 Redis 의존 없이(test 프로파일)
 * {@link com.hajacheck.core.analysis.service.InspectionAnalysisService}/
 * {@link com.hajacheck.core.analysis.service.InspectionAnalysisWorker}를 단위 테스트할 수 있게
 * 한다(코드 리뷰 P2 픽스 — 이전에는 세 빈 모두 {@code @Profile("!test")}로 컨텍스트에서 배제해
 * 자동화 테스트가 전혀 없었다). 운영은 {@link RedisAnalysisProgressStore}, 테스트는
 * {@link InMemoryAnalysisProgressStore}가 각각 프로파일별로 유일하게 활성화된다.
 */
public interface AnalysisProgressStore {

    void save(AnalysisStatusResponse progress);

    Optional<AnalysisStatusResponse> find(Long inspectionId);

    /** 큐잉 실패로 롤백하거나(TaskRejectedException) 전체 실패로 상태를 되돌릴 때 잔여 캐시를 지운다. */
    void delete(Long inspectionId);

    /**
     * 저장소 자체가 지금 정상 응답 중인지(코드 리뷰 P2, 사용자 확인 완료) — find()가 fail-soft라
     * "캐시가 진짜 없음"과 "장애로 못 읽음"을 Optional만으로는 구분할 수 없다.
     * InspectionAnalysisService의 ANALYZING 고착 판정이 이 값으로 둘을 나눈다: 저장소가 정상인데
     * 캐시가 없으면 진짜 고착(재시작 허용), 저장소 자체가 불안정하면 진행 중인 잡을 오판할 위험이
     * 있으니 보수적으로 "아직 진행 중"으로 본다(ALREADY_RUNNING) — 저장소가 복구되면 그 다음
     * 재시도부터 정상적으로 고착 판정이 동작한다.
     */
    boolean isAvailable();

    /**
     * 이번 실행(선점)에 발급된 세대 토큰을 기록한다(코드 리뷰 P1 — 워커 펜싱).
     *
     * <p>고착 복구 분기는 원본 워커가 실제로 죽었는지 확인할 방법 없이 상태만 UPLOADING으로
     * 되돌린 뒤 재선점한다({@link com.hajacheck.core.analysis.service.InspectionAnalysisService}
     * 참고) — 하트비트 판정이 오탐이면(GC 정지, 실행기 큐 적체 등으로 heartbeat만 지연) 원본 워커가
     * 여전히 살아서 돌고 있는 채로 새 워커가 하나 더 뜬다. 두 워커가 같은 회차에 defect를 동시에
     * 쓰면 append 중복·유령 행으로 데이터가 손상된다. 이를 막기 위해 재선점마다 새 토큰을 발급해
     * 두고, {@link com.hajacheck.core.analysis.service.InspectionAnalysisWorker}가 DB에 쓰기
     * 직전마다 자신이 받은 토큰과 여기 저장된 "현재" 토큰을 비교해, 다르면(추월당함) 스스로 중단한다.
     */
    void saveGeneration(Long inspectionId, String generation);

    /**
     * 현재 유효한 세대 토큰 — {@link #saveGeneration}으로 마지막에 기록된 값. 저장소가 비어있거나
     * (TTL 만료 등) 장애로 못 읽으면 {@code Optional.empty()}를 반환한다(fail-soft, find()/isAvailable()과
     * 동일 원칙) — 호출부(워커)는 "세대를 확인할 수 없음"을 "펜싱 정보 없음 = 계속 진행"으로 보수적으로
     * 취급해, 이 저장소 장애가 정상 진행 중인 분석 잡까지 막아버리지 않게 한다.
     */
    Optional<String> findGeneration(Long inspectionId);
}
