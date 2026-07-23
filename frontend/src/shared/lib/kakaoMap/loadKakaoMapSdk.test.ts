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
});
