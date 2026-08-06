// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { ChunkReloadGuardDeps } from './chunkLoadRecovery';
import { recoverFromChunkLoadError, registerChunkLoadRecovery } from './chunkLoadRecovery';

function createDeps(overrides: Partial<ChunkReloadGuardDeps> = {}): ChunkReloadGuardDeps {
  const store = new Map<string, string>();
  return {
    now: vi.fn(() => 0),
    getItem: vi.fn((key: string) => store.get(key) ?? null),
    setItem: vi.fn((key: string, value: string) => {
      store.set(key, value);
    }),
    reload: vi.fn(),
    ...overrides,
  };
}

describe('recoverFromChunkLoadError', () => {
  it('최근 리로드 이력이 없으면 리로드를 실행하고 시각을 기록한다', () => {
    const deps = createDeps({ now: () => 1_000 });

    const result = recoverFromChunkLoadError(deps);

    expect(result).toBe(true);
    expect(deps.reload).toHaveBeenCalledTimes(1);
    expect(deps.setItem).toHaveBeenCalledWith('chunkReloadedAt', '1000');
  });

  it('가드 윈도(기본 30초) 내 재발이면 리로드를 재시도하지 않는다', () => {
    const store = new Map<string, string>();
    const deps = createDeps({
      now: () => 10_000,
      getItem: (key) => store.get(key) ?? null,
      setItem: (key, value) => {
        store.set(key, value);
      },
    });
    store.set('chunkReloadedAt', '5000'); // 5초 전 리로드(10_000 - 5_000 = 5_000ms < 30_000ms)

    const result = recoverFromChunkLoadError(deps);

    expect(result).toBe(false);
    expect(deps.reload).not.toHaveBeenCalled();
  });

  it('가드 윈도를 벗어나면(오래된 이력) 다시 리로드를 실행한다', () => {
    const store = new Map<string, string>();
    const deps = createDeps({
      now: () => 100_000,
      getItem: (key) => store.get(key) ?? null,
      setItem: vi.fn((key, value) => {
        store.set(key, value);
      }),
    });
    store.set('chunkReloadedAt', '50000'); // 50000ms 전(100_000-50_000=50_000ms > 30_000ms)

    const result = recoverFromChunkLoadError(deps);

    expect(result).toBe(true);
    expect(deps.reload).toHaveBeenCalledTimes(1);
    expect(deps.setItem).toHaveBeenCalledWith('chunkReloadedAt', '100000');
  });

  it('저장된 값이 손상돼(숫자 아님) 있으면 가드 없이 리로드를 실행한다(fail-open)', () => {
    const deps = createDeps({
      now: () => 1_000,
      getItem: () => 'not-a-number',
    });

    const result = recoverFromChunkLoadError(deps);

    expect(result).toBe(true);
    expect(deps.reload).toHaveBeenCalledTimes(1);
  });

  it('guardWindowMs를 커스텀하면 그 값 기준으로 판정한다', () => {
    const store = new Map<string, string>();
    store.set('chunkReloadedAt', '0');
    const deps = createDeps({
      now: () => 5_000,
      getItem: (key) => store.get(key) ?? null,
      guardWindowMs: 10_000, // 5_000ms < 10_000ms → 가드에 걸림
    });

    const result = recoverFromChunkLoadError(deps);

    expect(result).toBe(false);
    expect(deps.reload).not.toHaveBeenCalled();
  });
});

describe('registerChunkLoadRecovery', () => {
  // 실제 window 대신 테스트마다 격리된 EventTarget을 써서 리스너가 테스트 간에 누적되지 않게 한다
  // (jsdom의 window는 파일 내 전체 테스트가 공유하므로, window에 직접 등록하면 이전 테스트의
  // 리스너가 남아 이후 dispatch에서 중복 호출된다).
  let eventTarget: EventTarget;
  let dispatchPreloadError: () => void;

  beforeEach(() => {
    eventTarget = new EventTarget();
    dispatchPreloadError = () => {
      const event = new Event('vite:preloadError', { cancelable: true });
      eventTarget.dispatchEvent(event);
    };
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('vite:preloadError 발생 시 기본 동작을 막고 리로드를 실행한다', () => {
    const deps = createDeps({ now: () => 1_000 });
    registerChunkLoadRecovery(deps, eventTarget);

    dispatchPreloadError();

    expect(deps.reload).toHaveBeenCalledTimes(1);
    expect(deps.setItem).toHaveBeenCalledWith('chunkReloadedAt', '1000');
  });

  it('가드 윈도 내에서 두 번째 이벤트가 발생하면 리로드를 재시도하지 않고 콘솔 에러만 남긴다', () => {
    const consoleErrorSpy = vi.spyOn(console, 'error').mockImplementation(() => {});
    let currentTime = 0;
    const deps = createDeps({ now: () => currentTime });
    registerChunkLoadRecovery(deps, eventTarget);

    currentTime = 1_000;
    dispatchPreloadError();
    expect(deps.reload).toHaveBeenCalledTimes(1);

    currentTime = 1_500; // 500ms 후 재발 — 30초 가드 윈도 내
    dispatchPreloadError();

    expect(deps.reload).toHaveBeenCalledTimes(1); // 재시도 안 함
    expect(consoleErrorSpy).toHaveBeenCalledTimes(1);
  });
});
