// @vitest-environment jsdom
// #164: 인터셉터(unwrapEnvelope/normalizeError) status 매핑 단위 테스트
import type { AxiosError, AxiosResponse } from 'axios';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { normalizeError, unwrapEnvelope } from './axios';
import type { ApiError, ApiResponse } from './types';

function makeAxiosResponse<T>(body: ApiResponse<T>, status: number): AxiosResponse {
  return {
    data: body,
    status,
    statusText: '',
    headers: {},
    config: {} as AxiosResponse['config'],
  } as AxiosResponse;
}

function makeAxiosError(status: number | undefined, error?: ApiError): AxiosError {
  const axiosError = {
    isAxiosError: true,
    name: 'AxiosError',
    message: 'error',
    config: {},
    response:
      status === undefined
        ? undefined
        : makeAxiosResponse({ success: false, data: undefined, error }, status),
  } as AxiosError;
  return axiosError;
}

describe('normalizeError', () => {
  it('HTTP 404 에러는 status 404로 reject된다', async () => {
    const error = makeAxiosError(404, { code: 'NOT_FOUND', message: '없음' });

    await expect(normalizeError(error)).rejects.toMatchObject({ status: 404 });
  });

  it('HTTP 500 에러는 status 500으로 reject된다', async () => {
    const error = makeAxiosError(500, { code: 'INTERNAL_ERROR', message: '서버 오류' });

    await expect(normalizeError(error)).rejects.toMatchObject({ status: 500 });
  });

  it('네트워크 오류(error.response 없음)는 status undefined로 reject된다', async () => {
    const error = makeAxiosError(undefined);

    await expect(normalizeError(error)).rejects.toMatchObject({
      code: 'NETWORK_ERROR',
      status: undefined,
    });
  });

  describe('401 응답', () => {
    beforeEach(() => {
      vi.stubGlobal('location', { href: '' });
    });

    afterEach(() => {
      vi.unstubAllGlobals();
    });

    it('status 401로 reject되고 로그인 페이지로 리다이렉트한다', async () => {
      const error = makeAxiosError(401, { code: 'UNAUTHORIZED', message: '인증 필요' });

      await expect(normalizeError(error)).rejects.toMatchObject({ status: 401 });
      expect(window.location.href).toBe('/login');
    });
  });
});

describe('unwrapEnvelope', () => {
  it('success:true 응답은 data만 꺼내 response.data에 채운다', () => {
    const response = makeAxiosResponse({ success: true, data: { value: 1 } }, 200);

    const result = unwrapEnvelope(response) as AxiosResponse;

    expect(result).toBe(response);
    expect(result.data).toEqual({ value: 1 });
  });

  // #164 핵심 케이스: success:false + HTTP 200(예: NOT_IMPLEMENTED)에서도 status가 채워져야
  // fetchWithFallback의 status===404 폴백 조건과 정합된다.
  it('success:false 200 응답은 status 200을 채워 reject된다', async () => {
    const response = makeAxiosResponse(
      { success: false, data: undefined, error: { code: 'NOT_IMPLEMENTED', message: '미구현' } },
      200,
    );

    await expect(unwrapEnvelope(response)).rejects.toEqual({
      code: 'NOT_IMPLEMENTED',
      message: '미구현',
      status: 200,
    });
  });

  it('success:false 404 응답은 status 404를 채워 reject된다', async () => {
    const response = makeAxiosResponse(
      { success: false, data: undefined, error: { code: 'NOT_FOUND', message: '없음' } },
      404,
    );

    await expect(unwrapEnvelope(response)).rejects.toMatchObject({ status: 404 });
  });
});
