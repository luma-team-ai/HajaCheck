// vite:preloadError(동적 import/모듈 프리로드 실패 시 window에 발화) 복구 유틸 — main.tsx에서
// 분리해 단위 테스트 가능하게 함(#1619).
//
// 배경: 배포 직후, 배포 전부터 접속 중이던 브라우저(구번들 런타임)가 lazy 라우트 청크(구해시
// /assets/XxxPage-*.js)를 요청하면 새 배포엔 그 파일이 없다. nginx가 index.html(text/html)을
// 200으로 폴백해주면 strict MIME 체크에 걸려 모듈 로드가 실패한다(nginx 쪽 /assets/ 404 폴백
// 차단은 frontend/nginx/*.conf에서 별도 처리 — 이 유틸은 그래도 실패가 발생한 "이미 열려 있던
// 구브라우저 세션"을 자동 복구하는 클라이언트 측 안전망).
//
// 무한 리로드 방지: 새 배포로 새로고침해도 여전히 실패하는 경우(예: 네트워크 문제·CDN 전파 지연)
// 리로드를 반복하면 사용자가 새로고침 루프에 갇힌다 — sessionStorage에 마지막 리로드 시각을 남겨
// 짧은 시간(기본 30초) 내 재발이면 재시도하지 않고 콘솔 에러만 남긴다.

const STORAGE_KEY = 'chunkReloadedAt';
const DEFAULT_GUARD_WINDOW_MS = 30_000;

export interface ChunkReloadGuardDeps {
  now: () => number;
  getItem: (key: string) => string | null;
  setItem: (key: string, value: string) => void;
  reload: () => void;
  guardWindowMs?: number;
}

/**
 * sessionStorage에 기록된 마지막 리로드 시각을 확인해 "최근에 이미 리로드했는지" 판정하고,
 * 가드에 걸리지 않으면 리로드를 실행한다.
 * @returns true = 리로드를 실행함 / false = 가드에 걸려 재시도하지 않음
 */
export function recoverFromChunkLoadError(deps: ChunkReloadGuardDeps): boolean {
  const guardWindowMs = deps.guardWindowMs ?? DEFAULT_GUARD_WINDOW_MS;
  const nowMs = deps.now();
  const rawLastReloadedAt = deps.getItem(STORAGE_KEY);

  if (rawLastReloadedAt !== null) {
    const lastReloadedAt = Number(rawLastReloadedAt);
    // 값이 손상돼(숫자 아님) 있으면 가드 없이 진행한다(fail-open) — 파싱 실패를 "리로드 없음"으로 취급.
    if (Number.isFinite(lastReloadedAt) && nowMs - lastReloadedAt < guardWindowMs) {
      return false;
    }
  }

  deps.setItem(STORAGE_KEY, String(nowMs));
  deps.reload();
  return true;
}

function defaultDeps(): ChunkReloadGuardDeps {
  return {
    now: () => Date.now(),
    getItem: (key) => window.sessionStorage.getItem(key),
    setItem: (key, value) => window.sessionStorage.setItem(key, value),
    reload: () => window.location.reload(),
  };
}

/**
 * main.tsx 부트스트랩에서 1회 호출 — `vite:preloadError` 리스너를 등록한다.
 * deps를 넘기지 않으면 실제 window/sessionStorage를 사용하는 기본 구현을 쓴다(테스트에서 override).
 * eventTarget도 테스트 전용 훅 — 기본값은 실제 window(테스트에서는 격리된 EventTarget으로 대체해
 * jsdom의 window가 테스트 파일 전체에서 공유되며 리스너가 누적되는 것을 방지한다).
 */
export function registerChunkLoadRecovery(
  deps: ChunkReloadGuardDeps = defaultDeps(),
  eventTarget: Pick<EventTarget, 'addEventListener'> = window,
): void {
  eventTarget.addEventListener('vite:preloadError', (event) => {
    // 기본 동작(브라우저 콘솔에 처리되지 않은 에러로 표시)을 막고 우리가 직접 복구를 시도한다.
    event.preventDefault();

    const reloaded = recoverFromChunkLoadError(deps);
    if (!reloaded) {
      console.error(
        `청크 로드 실패 — 최근 ${(deps.guardWindowMs ?? DEFAULT_GUARD_WINDOW_MS) / 1000}초 내 이미 자동 새로고침한 이력이 있어 재시도하지 않습니다(무한 리로드 방지).`,
        event,
      );
    }
  });
}
