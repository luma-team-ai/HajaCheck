// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from 'vitest';

function resetDomAndGlobals() {
  document.head.innerHTML = '';
  delete (window as unknown as { daum?: unknown }).daum;
}

describe('loadFacilityPostcodeScript', () => {
  // 모듈 스코프의 loadPromise 캐시가 테스트 간 공유되지 않도록 매번 모듈을 새로 로드한다
  beforeEach(() => {
    resetDomAndGlobals();
    vi.resetModules();
  });

  it('스크립트를 head에 추가하고, onload 후 resolve된다', async () => {
    const { loadFacilityPostcodeScript } = await import('./loadFacilityPostcodeScript');

    const promise = loadFacilityPostcodeScript();
    const script = document.head.querySelector<HTMLScriptElement>('script[src*="postcode"]');
    expect(script).not.toBeNull();

    script?.onload?.(new Event('load'));
    await expect(promise).resolves.toBeUndefined();
  });

  it('이미 window.daum.Postcode가 있으면 스크립트를 추가하지 않는다', async () => {
    (window as unknown as { daum: { Postcode: unknown } }).daum = { Postcode: class {} };
    const { loadFacilityPostcodeScript } = await import('./loadFacilityPostcodeScript');

    await loadFacilityPostcodeScript();

    expect(document.head.querySelector('script[src*="postcode"]')).toBeNull();
  });

  it('스크립트 로드 실패 시 reject되고 이후 재시도가 가능하다', async () => {
    const { loadFacilityPostcodeScript } = await import('./loadFacilityPostcodeScript');

    const failingPromise = loadFacilityPostcodeScript();
    const script = document.head.querySelector<HTMLScriptElement>('script[src*="postcode"]');
    script?.onerror?.(new Event('error'));

    await expect(failingPromise).rejects.toThrow();

    // 재시도 — window.daum이 없으므로 다시 스크립트를 추가해야 한다
    document.head.innerHTML = '';
    const retryPromise = loadFacilityPostcodeScript();
    const retryScript = document.head.querySelector<HTMLScriptElement>('script[src*="postcode"]');
    expect(retryScript).not.toBeNull();
    retryScript?.onload?.(new Event('load'));
    await expect(retryPromise).resolves.toBeUndefined();
  });
});
