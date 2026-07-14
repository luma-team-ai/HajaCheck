import { describe, expect, it, vi } from 'vitest';
import { fetchWithFallback } from './fetchWithFallback';

describe('fetchWithFallback', () => {
  it('실 API 호출이 성공하면 그 결과를 반환한다', async () => {
    const result = await fetchWithFallback(() => Promise.resolve({ value: 1 }), { value: 0 });

    expect(result).toEqual({ value: 1 });
  });

  it('404(백엔드 미구현)로 실패하면 예제 데이터를 반환한다', async () => {
    const fallback = { value: 0 };

    const result = await fetchWithFallback(
      () => Promise.reject({ status: 404, code: 'NOT_FOUND', message: 'Not Found' }),
      fallback,
    );

    expect(result).toBe(fallback);
  });

  it('500(서버 오류)로 실패하면 예제 데이터로 가리지 않고 에러를 던진다', async () => {
    const consoleErrorSpy = vi.spyOn(console, 'error').mockImplementation(() => {});
    const error = { status: 500, code: 'INTERNAL_ERROR', message: 'Internal Server Error' };

    await expect(
      fetchWithFallback(() => Promise.reject(error), { value: 0 }),
    ).rejects.toEqual(error);

    consoleErrorSpy.mockRestore();
  });

  it('status가 없는 네트워크 에러는 예제 데이터로 가리지 않고 에러를 던진다', async () => {
    const consoleErrorSpy = vi.spyOn(console, 'error').mockImplementation(() => {});
    const error = { code: 'NETWORK_ERROR', message: '네트워크 오류가 발생했습니다.' };

    await expect(
      fetchWithFallback(() => Promise.reject(error), { value: 0 }),
    ).rejects.toEqual(error);

    consoleErrorSpy.mockRestore();
  });
});
