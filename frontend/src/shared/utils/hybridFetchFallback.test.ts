import { describe, expect, it, vi } from 'vitest';
import { hybridFetchFallback } from './hybridFetchFallback';

describe('hybridFetchFallback', () => {
  const mockFallbackData = [{ id: 1, name: 'MSW 샘플 항목' }];

  it('DEV 환경에서 실 서버가 성공 응답(유효 데이터)을 돌려주면 실데이터를 반환한다', async () => {
    const realData = [{ id: 99, name: '실 서버 레코드' }];
    const fetcher = vi.fn().mockResolvedValue(realData);

    const result = await hybridFetchFallback({
      fetcher,
      fallback: mockFallbackData,
      env: { DEV: true },
    });

    expect(result).toEqual(realData);
    expect(fetcher).toHaveBeenCalledTimes(1);
  });

  it('DEV 환경에서 실 서버가 빈 배열([])을 응답하면 MSW 목 데이터로 폴백한다', async () => {
    const fetcher = vi.fn().mockResolvedValue([]);

    const result = await hybridFetchFallback({
      fetcher,
      fallback: mockFallbackData,
      env: { DEV: true },
      fallbackOnEmptyArray: true,
    });

    expect(result).toEqual(mockFallbackData);
  });

  it('페이지 응답도 isEmpty 판정으로 실데이터 우선 정책을 검증한다', async () => {
    const realPage = { content: [], page: 0, totalElements: 0 };
    const result = await hybridFetchFallback({
      fetcher: vi.fn().mockResolvedValue(realPage),
      fallback: { content: [{ id: 1 }], page: 0, totalElements: 1 },
      env: { DEV: true },
      isEmpty: (page) => page.content.length === 0,
    });

    expect(result.content).toEqual([{ id: 1 }]);
  });

  it('fallbackOnEmptyArray가 false인 경우, 실 서버의 빈 배열 응답을 그대로 유지한다', async () => {
    const fetcher = vi.fn().mockResolvedValue([]);

    const result = await hybridFetchFallback({
      fetcher,
      fallback: mockFallbackData,
      env: { DEV: true },
      fallbackOnEmptyArray: false,
    });

    expect(result).toEqual([]);
  });

  it('DEV 환경에서 실 서버가 404 Not Found 에러를 던지면 목 데이터로 폴백한다', async () => {
    const error404 = { response: { status: 404 } };
    const fetcher = vi.fn().mockRejectedValue(error404);

    const result = await hybridFetchFallback({
      fetcher,
      fallback: () => mockFallbackData,
      env: { DEV: true },
    });

    expect(result).toEqual(mockFallbackData);
  });

  it('DEV 환경에서 NETWORK_ERROR(서버 미기동) 발생 시 목 데이터로 폴백한다', async () => {
    const networkErr = { code: 'NETWORK_ERROR' };
    const fetcher = vi.fn().mockRejectedValue(networkErr);

    const result = await hybridFetchFallback({
      fetcher,
      fallback: mockFallbackData,
      env: { DEV: true },
    });

    expect(result).toEqual(mockFallbackData);
  });

  it.each([401, 403, 500])('%i 에러 발생 시 폴백하지 않고 에러를 전파한다', async (status) => {
    const error = { response: { status } };
    const fetcher = vi.fn().mockRejectedValue(error);

    await expect(
      hybridFetchFallback({
        fetcher,
        fallback: mockFallbackData,
        env: { DEV: true },
      }),
    ).rejects.toEqual(error);
  });

  it.each([502, 503])('%i 게이트웨이 에러 발생 시 목 데이터로 폴백한다', async (status) => {
    const error = { response: { status } };
    const fetcher = vi.fn().mockRejectedValue(error);

    const result = await hybridFetchFallback({
      fetcher,
      fallback: mockFallbackData,
      env: { DEV: true },
    });

    expect(result).toEqual(mockFallbackData);
  });

  it('PROD 환경(DEV: false)에서는 404나 NETWORK_ERROR가 나도 절대 목 데이터로 폴백하지 않는다 (#213 가드)', async () => {
    const networkErr = { code: 'NETWORK_ERROR' };
    const fetcher = vi.fn().mockRejectedValue(networkErr);

    await expect(
      hybridFetchFallback({
        fetcher,
        fallback: mockFallbackData,
        env: { DEV: false },
      }),
    ).rejects.toEqual(networkErr);
  });

  it('env.VITE_ENABLE_MSW === "false"일 때는 DEV === true여도 절대 목 데이터로 폴백하지 않는다 (#868 P2 가드)', async () => {
    const fetcher = vi.fn().mockResolvedValue([]);

    const result = await hybridFetchFallback({
      fetcher,
      fallback: mockFallbackData,
      env: { DEV: true, VITE_ENABLE_MSW: 'false' },
      fallbackOnEmptyArray: true,
    });

    expect(result).toEqual([]);
  });

  it.each([
    { label: '404', error: { response: { status: 404 } } },
    { label: 'NETWORK_ERROR', error: { code: 'NETWORK_ERROR' } },
    { label: '503', error: { response: { status: 503 } } },
  ])('MSW가 비활성화된 DEV 환경에서는 $label 오류를 그대로 전파한다', async ({ error }) => {
    const fetcher = vi.fn().mockRejectedValue(error);

    await expect(
      hybridFetchFallback({
        fetcher,
        fallback: mockFallbackData,
        env: { DEV: true, VITE_ENABLE_MSW: 'false' },
      }),
    ).rejects.toEqual(error);
  });

  it('오류 status가 response 밖에 있어도 DEV fallback 대상이면 목 데이터를 반환한다', async () => {
    const result = await hybridFetchFallback({
      fetcher: vi.fn().mockRejectedValue({ status: 502 }),
      fallback: mockFallbackData,
      env: { DEV: true },
    });

    expect(result).toEqual(mockFallbackData);
  });
});
