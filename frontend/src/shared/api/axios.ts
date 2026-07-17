// 공통 axios 인스턴스 — 컴포넌트에서 axios 직접 import 금지 (React_코드_컨벤션.md §3)
// 백엔드 envelope({ success, data, error })은 인터셉터에서 해제 — 컴포넌트/훅은 data만 다룬다
import axios from 'axios';
import { LOGIN_PATH } from '../constants/authPaths';
import type { ApiError, ApiResponse } from './types';

// 세션 탐지용 요청(getMe 등)은 401을 "미로그인"이라는 정상 신호로 받으므로 전역 하드 리다이렉트에서
// 제외한다 — 이 플래그를 request config에 실으면 아래 인터셉터가 401이어도 /login으로 튕기지 않는다.
// (공개 랜딩 '/'에서 AuthGate의 getMe 401이 랜딩을 못 보게 강제 이동시키던 회귀 방지, #276)
declare module 'axios' {
  export interface AxiosRequestConfig {
    skipAuthRedirect?: boolean;
  }
}

export const api = axios.create({
  baseURL: '/api',
  withCredentials: true, // 세션 쿠키
});

api.interceptors.response.use(
  (response) => {
    const body = response.data as ApiResponse<unknown>;
    if (body && body.success === false) {
      const apiError: ApiError = {
        ...(body.error ?? { code: 'UNKNOWN_ERROR', message: '알 수 없는 오류가 발생했습니다.' }),
        status: response.status,
      };
      return Promise.reject(apiError);
    }
    response.data = body.data;
    return response;
  },
  (error) => {
    const status = error.response?.status;
    // 세션 탐지 요청(skipAuthRedirect)은 401이어도 하드 리다이렉트하지 않는다 — 공개 라우트에서
    // getMe 401은 '미로그인'일 뿐이고, 보호 라우트 가드는 ProtectedRoute가 담당한다(#276).
    const skipAuthRedirect = error.config?.skipAuthRedirect === true;
    // 이미 로그인 경로면 리다이렉트 스킵 — 로그인 화면 세션체크·로그인 실패 401이 무한 리로드로 이어지는 것 방지
    // LOGIN_PATH가 basename까지 반영된 정확한 경로라 정확 일치로 비교(과매칭 방지 — 예: '/company/login')
    if (status === 401 && !skipAuthRedirect && window.location.pathname !== LOGIN_PATH) {
      window.location.href = LOGIN_PATH; // 401 일괄 처리
    }
    const apiError: ApiError = {
      ...(error.response?.data?.error ?? {
        code: 'NETWORK_ERROR',
        message: '네트워크 오류가 발생했습니다.',
      }),
      status,
    };
    return Promise.reject(apiError);
  },
);
