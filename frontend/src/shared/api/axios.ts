// 공통 axios 인스턴스 — 컴포넌트에서 axios 직접 import 금지 (React_코드_컨벤션.md §3)
// 백엔드 envelope({ success, data, error })은 인터셉터에서 해제 — 컴포넌트/훅은 data만 다룬다
import axios from 'axios';
import type { ApiError, ApiResponse } from './types';

export const api = axios.create({
  baseURL: '/api',
  withCredentials: true, // 세션 쿠키
});

api.interceptors.response.use(
  (response) => {
    const body = response.data as ApiResponse<unknown>;
    if (body && body.success === false) {
      const apiError: ApiError = body.error ?? {
        code: 'UNKNOWN_ERROR',
        message: '알 수 없는 오류가 발생했습니다.',
      };
      return Promise.reject(apiError);
    }
    response.data = body.data;
    return response;
  },
  (error) => {
    if (error.response?.status === 401) {
      window.location.href = '/login'; // 401 일괄 처리
    }
    const apiError: ApiError = error.response?.data?.error ?? {
      code: 'NETWORK_ERROR',
      message: '네트워크 오류가 발생했습니다.',
    };
    return Promise.reject(apiError);
  },
);
