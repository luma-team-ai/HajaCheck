// 공통 axios 인스턴스 — 컴포넌트에서 axios 직접 import 금지 (React_코드_컨벤션.md §3)
// 백엔드 envelope({ success, data, error })은 인터셉터에서 해제 — 컴포넌트/훅은 data만 다룬다
import axios, { type AxiosError, type AxiosResponse } from 'axios';
import type { ApiError, ApiResponse } from './types';

export const api = axios.create({
  baseURL: '/api',
  withCredentials: true, // 세션 쿠키
});

// #164: 테스트 가능하도록 named 함수로 분리 export (axios.test.ts에서 직접 검증)
export function unwrapEnvelope(response: AxiosResponse): AxiosResponse | Promise<never> {
  const body = response.data as ApiResponse<unknown>;
  if (body && body.success === false) {
    const apiError: ApiError = {
      // #164: success:false(200)에서도 status 일관 설정 — fetchWithFallback의 status===404 폴백 조건과 정합
      ...(body.error ?? {
        code: 'UNKNOWN_ERROR',
        message: '알 수 없는 오류가 발생했습니다.',
      }),
      status: response.status,
    };
    return Promise.reject(apiError);
  }
  response.data = body.data;
  return response;
}

export function normalizeError(error: AxiosError): Promise<never> {
  if (error.response?.status === 401) {
    window.location.href = '/login'; // 401 일괄 처리
  }
  const apiError: ApiError = {
    ...((error.response?.data as ApiResponse<unknown> | undefined)?.error ?? {
      code: 'NETWORK_ERROR',
      message: '네트워크 오류가 발생했습니다.',
    }),
    status: error.response?.status,
  };
  return Promise.reject(apiError);
}

api.interceptors.response.use(unwrapEnvelope, normalizeError);
