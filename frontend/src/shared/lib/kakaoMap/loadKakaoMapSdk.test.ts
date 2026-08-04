// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const SCRIPT_ID = 'kakao-map-sdk';

async function importFreshModule() {
  vi.resetModules();
  return import('./loadKakaoMapSdk');
}

describe('loadKakaoMapSdk', () => {
  beforeEach(() => {
    document.head.innerHTML = '';
    delete (window as unknown as { kakao?: unknown }).kakao;
    vi.stubEnv('VITE_KAKAO_MAP_APP_KEY', 'test-app-key');
  });

  afterEach(() => {
    document.head.innerHTML = '';
    delete (window as unknown as { kakao?: unknown }).kakao;
    vi.unstubAllEnvs();
  });

  it('키 미설정 시 KakaoMapKeyMissingError로 reject한다', async () => {
    vi.stubEnv('VITE_KAKAO_MAP_APP_KEY', '');
    const { loadKakaoMapSdk, KakaoMapKeyMissingError } = await importFreshModule();

    await expect(loadKakaoMapSdk()).rejects.toBeInstanceOf(KakaoMapKeyMissingError);
  });

  it('중복 호출 시 동일 Promise(싱글턴)를 반환한다', async () => {
    const { loadKakaoMapSdk } = await importFreshModule();

    const first = loadKakaoMapSdk();
    const second = loadKakaoMapSdk();
    expect(first).toBe(second);

    const script = document.getElementById(SCRIPT_ID) as HTMLScriptElement;
    (window as unknown as { kakao: { maps: { load: (cb: () => void) => void } } }).kakao = {
      maps: { load: (cb: () => void) => cb() },
    };
    script.onload?.(new Event('load'));
    await expect(first).resolves.toBeUndefined();
  });

  it('신규 script onerror 시 loadPromise를 리셋해 재시도를 허용한다', async () => {
    const { loadKakaoMapSdk } = await importFreshModule();

    const firstCall = loadKakaoMapSdk();
    const script = document.getElementById(SCRIPT_ID) as HTMLScriptElement;
    script.onerror?.(new Event('error'));
    await expect(firstCall).rejects.toThrow('Kakao Maps SDK 로드에 실패했습니다.');

    // 재시도 시 새 script 태그로 다시 로드를 시도해야 한다 (loadPromise가 null로 리셋됨)
    const secondCall = loadKakaoMapSdk();
    expect(secondCall).not.toBe(firstCall);

    const retryScript = document.getElementById(SCRIPT_ID) as HTMLScriptElement;
    (window as unknown as { kakao: { maps: { load: (cb: () => void) => void } } }).kakao = {
      maps: { load: (cb: () => void) => cb() },
    };
    retryScript.onload?.(new Event('load'));
    await expect(secondCall).resolves.toBeUndefined();
  });

  it('이미 로드 완료된 window.kakao.maps 가 있으면 즉시 resolve한다', async () => {
    (window as unknown as { kakao: { maps: unknown } }).kakao = { maps: {} };
    const { loadKakaoMapSdk } = await importFreshModule();

    await expect(loadKakaoMapSdk()).resolves.toBeUndefined();
    expect(document.getElementById(SCRIPT_ID)).toBeNull();
  });

  it('기존 script 재사용 분기의 error 이벤트에서도 loadPromise를 리셋한다', async () => {
    const existing = document.createElement('script');
    existing.id = SCRIPT_ID;
    document.head.appendChild(existing);

    const { loadKakaoMapSdk } = await importFreshModule();

    const firstCall = loadKakaoMapSdk();
    existing.dispatchEvent(new Event('error'));
    await expect(firstCall).rejects.toThrow('Kakao Maps SDK 로드에 실패했습니다.');

    // 실패한 태그가 제거되어 재진입 시 새 script로 재시도할 수 있어야 한다
    expect(document.getElementById(SCRIPT_ID)).toBeNull();
    const secondCall = loadKakaoMapSdk();
    expect(secondCall).not.toBe(firstCall);
  });

  // #1590 P2 — load/error 어느 쪽도 발화하지 않는 스크립트(사내망·차단 확장 등)에서 Promise가
  // 영구 pending되면 호출부(FacilityFormModal 등록 모달)가 복구 불가 상태로 멈춘다.
  it('타임아웃(load/error 미발화) 시 KakaoMapSdkTimeoutError로 reject하고 재시도를 허용한다', async () => {
    vi.useFakeTimers();
    try {
      const { loadKakaoMapSdk, KakaoMapSdkTimeoutError, KAKAO_MAP_SDK_LOAD_TIMEOUT_MS } =
        await importFreshModule();

      const firstCall = loadKakaoMapSdk();
      // 타이머를 진행시키기 전에 rejection 핸들러를 붙여둔다 — 나중에 붙이면 그 사이 rejection이
      // unhandled로 보고돼 테스트 러너가 에러로 잡는다.
      const rejection = firstCall.catch((err: unknown) => err);
      // 스크립트 태그는 붙었지만 onload/onerror 어느 쪽도 발화하지 않는 상황
      expect(document.getElementById(SCRIPT_ID)).not.toBeNull();

      await vi.advanceTimersByTimeAsync(KAKAO_MAP_SDK_LOAD_TIMEOUT_MS);

      expect(await rejection).toBeInstanceOf(KakaoMapSdkTimeoutError);
      // 매달린 태그를 제거해야 재진입이 새 script로 재시도할 수 있다
      expect(document.getElementById(SCRIPT_ID)).toBeNull();

      const secondCall = loadKakaoMapSdk();
      expect(secondCall).not.toBe(firstCall);

      const retryScript = document.getElementById(SCRIPT_ID) as HTMLScriptElement;
      (window as unknown as { kakao: { maps: { load: (cb: () => void) => void } } }).kakao = {
        maps: { load: (cb: () => void) => cb() },
      };
      retryScript.onload?.(new Event('load'));
      await expect(secondCall).resolves.toBeUndefined();
    } finally {
      vi.useRealTimers();
    }
  });

  it('정상 로드되면 타임아웃 타이머가 해제돼 이후에 reject되지 않는다', async () => {
    vi.useFakeTimers();
    try {
      const { loadKakaoMapSdk, KAKAO_MAP_SDK_LOAD_TIMEOUT_MS } = await importFreshModule();

      const call = loadKakaoMapSdk();
      const script = document.getElementById(SCRIPT_ID) as HTMLScriptElement;
      (window as unknown as { kakao: { maps: { load: (cb: () => void) => void } } }).kakao = {
        maps: { load: (cb: () => void) => cb() },
      };
      script.onload?.(new Event('load'));
      await expect(call).resolves.toBeUndefined();

      // 타임아웃 시각을 지나도 (이미 resolve된) Promise가 뒤늦게 reject되거나 태그가 지워지면 안 된다
      await vi.advanceTimersByTimeAsync(KAKAO_MAP_SDK_LOAD_TIMEOUT_MS * 2);
      await expect(call).resolves.toBeUndefined();
      expect(document.getElementById(SCRIPT_ID)).not.toBeNull();
    } finally {
      vi.useRealTimers();
    }
  });

  it('script.onload 발생 시 kakao.maps가 생성되어도 load() 콜백 전 동시 재호출은 pending Promise를 공유한다 (#835 P2 가드)', async () => {
    const { loadKakaoMapSdk } = await importFreshModule();

    let mapLoadCallback: (() => void) | null = null;
    const firstCall = loadKakaoMapSdk();

    const script = document.getElementById(SCRIPT_ID) as HTMLScriptElement;
    // script.onload에서 window.kakao.maps 네임스페이스는 생겼지만 load() 콜백은 아직 실행되지 않은 상태 지연
    (window as unknown as { kakao: { maps: { load: (cb: () => void) => void } } }).kakao = {
      maps: {
        load: (cb: () => void) => {
          mapLoadCallback = cb;
        },
      },
    };
    script.onload?.(new Event('load'));

    // window.kakao.maps는 존재하지만 지연 상태에서 두 번째 호출 수행
    const secondCall = loadKakaoMapSdk();
    expect(secondCall).toBe(firstCall);

    // 콜백이 실행되기 전에는 두 Promise 모두 unresolved
    let resolved = false;
    firstCall.then(() => {
      resolved = true;
    });
    expect(resolved).toBe(false);

    // 지연된 콜백 실행 시 비로소 resolve
    const invokeCallback = mapLoadCallback as (() => void) | null;
    invokeCallback?.();
    await expect(firstCall).resolves.toBeUndefined();
    await expect(secondCall).resolves.toBeUndefined();
  });
});
