// @vitest-environment jsdom
// shared/lib/kakaoMap/loadKakaoMapSdk.test.ts와 동일한 싱글턴/에러 계약을 검증한다. 실 SDK는
// 내부적으로 <script> 태그를 주입해 네트워크 요청을 일으키므로, 모듈 자체를 목으로 교체해
// 순수하게 우리 로더의 싱글턴/에러 처리 로직만 검증한다.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const loadTossPaymentsMock = vi.fn();

vi.mock('@tosspayments/tosspayments-sdk', () => ({
  loadTossPayments: (...args: unknown[]) => loadTossPaymentsMock(...args),
}));

async function importFreshModule() {
  vi.resetModules();
  return import('./loadTossPaymentsSdk');
}

describe('loadTossPaymentsSdk', () => {
  beforeEach(() => {
    loadTossPaymentsMock.mockReset();
    vi.stubEnv('VITE_TOSS_CLIENT_KEY', 'test-client-key');
  });

  afterEach(() => {
    vi.unstubAllEnvs();
  });

  it('키 미설정 시 TossClientKeyMissingError로 reject하고 SDK를 로드하지 않는다', async () => {
    vi.stubEnv('VITE_TOSS_CLIENT_KEY', '');
    const { loadTossPaymentsSdk, TossClientKeyMissingError } = await importFreshModule();

    await expect(loadTossPaymentsSdk()).rejects.toBeInstanceOf(TossClientKeyMissingError);
    expect(loadTossPaymentsMock).not.toHaveBeenCalled();
  });

  it('중복 호출 시 동일 Promise(싱글턴)를 반환하고 SDK는 1회만 로드한다', async () => {
    const fakeSdk = { payment: vi.fn() };
    loadTossPaymentsMock.mockResolvedValue(fakeSdk);
    const { loadTossPaymentsSdk } = await importFreshModule();

    const first = loadTossPaymentsSdk();
    const second = loadTossPaymentsSdk();
    expect(first).toBe(second);

    await expect(first).resolves.toBe(fakeSdk);
    expect(loadTossPaymentsMock).toHaveBeenCalledTimes(1);
    expect(loadTossPaymentsMock).toHaveBeenCalledWith('test-client-key');
  });

  it('로드 실패 시 싱글턴을 리셋해 재시도를 허용한다', async () => {
    loadTossPaymentsMock.mockRejectedValueOnce(new Error('네트워크 오류'));
    const { loadTossPaymentsSdk } = await importFreshModule();

    const firstCall = loadTossPaymentsSdk();
    await expect(firstCall).rejects.toThrow('네트워크 오류');

    const fakeSdk = { payment: vi.fn() };
    loadTossPaymentsMock.mockResolvedValueOnce(fakeSdk);
    const secondCall = loadTossPaymentsSdk();
    expect(secondCall).not.toBe(firstCall);
    await expect(secondCall).resolves.toBe(fakeSdk);
    expect(loadTossPaymentsMock).toHaveBeenCalledTimes(2);
  });
});
