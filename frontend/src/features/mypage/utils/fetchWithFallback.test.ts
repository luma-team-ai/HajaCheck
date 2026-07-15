import { describe, expect, it } from 'vitest';
import { fetchWithFallback } from './fetchWithFallback';

describe('fetchWithFallback (mypage)', () => {
  it('실 API 호출이 성공하면 그 결과를 반환한다', async () => {
    const result = await fetchWithFallback(() => Promise.resolve({ value: 1 }), { value: 0 });

    expect(result).toEqual({ value: 1 });
  });

  it("code가 'NETWORK_ERROR'(백엔드 미배포 등 진짜 네트워크 오류)이면 예제 데이터로 폴백한다", async () => {
    const fallback = { value: 0 };

    const result = await fetchWithFallback(
      () => Promise.reject({ status: 404, code: 'NETWORK_ERROR', message: '네트워크 오류가 발생했습니다.' }),
      fallback,
    );

    expect(result).toBe(fallback);
  });

  it("code가 'PLAN_NOT_FOUND'(활성 구독 없음 — 정상 도메인 상태)이면 폴백하지 않고 그대로 던진다", async () => {
    const error = { status: 404, code: 'PLAN_NOT_FOUND', message: '활성 구독이 없습니다.' };

    await expect(fetchWithFallback(() => Promise.reject(error), { value: 0 })).rejects.toEqual(
      error,
    );
  });

  it("code가 'PLAN_FORBIDDEN'(소유자 아님)이면 폴백하지 않고 그대로 던진다", async () => {
    const error = { status: 403, code: 'PLAN_FORBIDDEN', message: '플랜 소유자만 가능합니다.' };

    await expect(fetchWithFallback(() => Promise.reject(error), { value: 0 })).rejects.toEqual(
      error,
    );
  });

  it('그 외 에러(예: 500)는 예제 데이터로 가리지 않고 그대로 던진다', async () => {
    const error = { status: 500, code: 'INTERNAL_ERROR', message: 'Internal Server Error' };

    await expect(fetchWithFallback(() => Promise.reject(error), { value: 0 })).rejects.toEqual(
      error,
    );
  });
});
