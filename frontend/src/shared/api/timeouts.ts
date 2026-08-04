// HTTP 요청 상한(#1598) — 두 axios 인스턴스(shared/api/axios.ts의 api, shared/api/aiClient.ts의
// aiClient)와 장시간 호출부가 공유하는 단일 출처.
//
// ── 왜 필요한가 ────────────────────────────────────────────────────────────────
// axios의 timeout 기본값은 0 = 무한 대기다. 상한이 없으면 프록시·사내망·캡티브 포털이 응답을
// "삼켰을" 때(연결이 끊기지도, 응답이 오지도 않는 상태) 그 요청은 영원히 끝나지 않는다. 그러면
// 호출부의 catch/finally가 실행되지 않아 로딩 상태가 영구 고착된다 — 새로고침 전에는 복구 불가.
// 실제로 RAG 복원 GET 지연(#1590)과 상담원 getTicket 백필로 인한 입력창 무기한 잠김(#1596 검수)
// 두 번이 국소 증상으로 발견됐고, 이 파일이 그 근본 원인을 전역에서 닫는다.
//
// ── 값을 정한 원칙 ─────────────────────────────────────────────────────────────
// 상한이 너무 짧으면 "정상적으로 오래 걸리는 요청"을 끊어 지금보다 나빠진다. 그래서 모든 값은
// 추측이 아니라 **백엔드가 스스로 선언한 상한에서 역산**한다 — 서버가 아직 응답을 줄 수 있는
// 시간 동안 브라우저가 먼저 포기하면 안 되기 때문이다. 근거는 각 상수 주석에 명시한다.
// 참조: backend/src/main/resources/application.yml, frontend/nginx/*.conf

/**
 * 일반 API 요청(비-AI·비-업로드) 상한 — `api` 인스턴스 기본값.
 *
 * 역산 근거:
 * 1. 가장 느린 정상 경로는 사업자 진위확인 실시간 경로(POST /auth/business-verification)다.
 *    application.yml `biz-verify`: connect-timeout-ms 3000 + realtime-read-timeout-ms 8000이고,
 *    NtsBusinessVerifyClient.java:346이 maxAttempts = retryMaxAttempts(1) + 1 = 2회로 재시도하며
 *    retry-backoff-ms 300을 끼운다 → 최악 (3000+8000)×2 + 300 = 22.3초.
 *    (그 다음이 토스 결제 승인: connect 3000 + read 10000 = 13초.)
 * 2. 따라서 상한은 22.3초보다 커야 한다. 30초는 약 7.7초의 여유를 남겨 스프링 처리·DB·네트워크
 *    왕복을 흡수한다.
 * 3. 위로는 nginx가 /api/ location에 proxy_read_timeout을 명시하지 않아 nginx 기본값 60초가
 *    적용된다(frontend/nginx/*.conf에서 3600s를 지정한 곳은 /ws WebSocket location뿐이다).
 *    30초는 그 60초 안쪽이라, nginx가 아직 정상 중계 중인 요청을 브라우저가 먼저 끊는 일은 없다.
 *
 * 이 상한을 넘겨야 하는 경로는 아래 AI_TIMEOUT_MS / uploadTimeoutMs()로 요청 단위 override 한다.
 */
export const API_TIMEOUT_MS = 30_000;

/**
 * LLM·임베딩이 개입하는 요청 상한 — `aiClient` 인스턴스 기본값이자, `api`를 타는 AI 경유
 * 엔드포인트의 요청 단위 override 값.
 *
 * 역산 근거:
 * 1. 스프링이 ai-server를 호출할 때 스스로 정한 상한이 곧 이 경로의 실질 천장이다.
 *    application.yml `ai.server`: connect-timeout-ms 3000 + read-timeout-ms 150000 = 153초.
 *    read-timeout 150초는 "HF Inference(Qwen3 reasoning)가 정상 응답에도 HF_TIMEOUT(기본 120초)
 *    까지 걸릴 수 있어 그보다 작으면 스프링이 정상 응답조차 실패 처리한다(#448 P1)"는 근거로
 *    정해진 값이다 — 즉 최대 153초까지는 **정상 응답이 올 수 있는 시간**이다.
 * 2. 브라우저가 153초보다 먼저 끊으면 백엔드가 답을 줄 수 있었던 요청을 실패로 만든다. 따라서
 *    상한은 153초보다 커야 한다. 180초는 약 27초의 여유로 스프링 자체 처리·큐 대기·네트워크를
 *    흡수한다.
 *
 * ⚠️ 참고(프론트 범위 밖): 운영 nginx의 /api/ location은 기본 60초라 이 경로가 브라우저 상한에
 *    닿기 전에 504로 끊길 수 있다. 그건 인프라 설정 불일치이고 별건이다 — 다만 그때도 응답(504)이
 *    오므로 요청은 정상적으로 종료된다. 이 상수가 막으려는 건 "응답이 아예 오지 않는" 경우다.
 */
export const AI_TIMEOUT_MS = 180_000;

/**
 * 업로드 본문 전송에 허용하는 최소 처리량(bytes/ms).
 *
 * 근거: 이 제품의 업로드 주체는 현장 점검자이고, 촬영 데이터를 현장에서 모바일 회선으로 올린다.
 * 5Mbps(= 625,000 bit/s ÷ 8 = 625 bytes/ms)를 "이보다 느리면 회선 장애로 본다"는 보수적 하한으로
 * 잡는다. 실제 회선이 이보다 빠르면 그만큼 상한에 여유가 생길 뿐 불이익은 없다.
 */
const MIN_UPLOAD_THROUGHPUT_BYTES_PER_MS = 625;

/**
 * 업로드 요청 상한 = 서버 처리 천장 + 본문 전송 허용 시간.
 *
 * 왜 고정값이 아닌가:
 * 브라우저에서 axios의 timeout은 XMLHttpRequest.timeout으로 매핑되고, 이는 **본문 업로드 시간까지
 * 포함한 요청 전체**를 잰다. 그런데 이 제품이 계약상 받아주는 업로드 크기는 경로마다 100배 차이가
 * 난다 — 점검 미디어는 nginx client_max_body_size 1005m + 스프링 max-request-size 1005MB로
 * 최대 50장×20MB ≈ 1000MB까지 허용하고(inspection/constants.ts), 하자 조치 사진은 10MB 한 장이다.
 *
 * 여기에 고정값을 쓰면 반드시 둘 중 하나가 깨진다:
 *  - 120초처럼 짧게 잡으면 → 1000MB 업로드가 계약상 정상인데도 매번 끊긴다(지금보다 나쁨).
 *  - 30분처럼 길게 잡으면 → 사진 한 장짜리 업로드가 사실상 무한 대기로 돌아간다(이 이슈의 원상 복귀).
 * 그래서 상한을 페이로드 크기에 비례시킨다. 작은 업로드는 빨리 끊기고, 큰 업로드는 끝까지 간다.
 *
 * @param totalBytes 이 요청이 실제로 전송할 본문 크기 합계
 * @param processingCeilingMs 바이트가 다 도착한 뒤 서버가 쓰는 처리 시간의 천장.
 *        기본값 API_TIMEOUT_MS는 점검 미디어 업로드의 서버측 후처리(매직바이트 검증·EXIF·썸네일/
 *        상세 이미지 인코딩 — inspection/constants.ts:5-7)를 덮는다. OCR·임베딩처럼 업로드 뒤
 *        LLM이 붙는 경로는 AI_TIMEOUT_MS를 넘긴다.
 */
export function uploadTimeoutMs(
  totalBytes: number,
  processingCeilingMs: number = API_TIMEOUT_MS,
): number {
  const transferAllowanceMs = Math.ceil(
    Math.max(totalBytes, 0) / MIN_UPLOAD_THROUGHPUT_BYTES_PER_MS,
  );
  return processingCeilingMs + transferAllowanceMs;
}

/** File[] 합계 크기 — uploadTimeoutMs에 넘길 바이트 수 계산용. */
export function totalBytesOf(files: readonly File[]): number {
  return files.reduce((sum, file) => sum + file.size, 0);
}
