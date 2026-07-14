import { describe, expect, it } from 'vitest';
import { fetchWithFallback } from './fetchWithFallback';

describe('fetchWithFallback', () => {
  it('실 API 호출이 성공하면 그 결과를 반환한다', async () => {
    const result = await fetchWithFallback(() => Promise.resolve({ value: 1 }), { value: 0 });

    expect(result).toEqual({ value: 1 });
  });

  it('실 API 호출이 실패하면 예제 데이터를 반환한다(백엔드 미구현 폴백)', async () => {
    const fallback = { value: 0 };

    const result = await fetchWithFallback(() => Promise.reject(new Error('Not Found')), fallback);

    expect(result).toBe(fallback);
  });
});
