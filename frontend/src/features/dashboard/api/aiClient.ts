// ai-server 전용 axios 인스턴스 — AI_개발_컨벤션.md §5 AIResponse({success,data,usage,error}) envelope 해제
// shared/api/axios.ts(baseURL=/api)와 별도: ai-server 라우트는 /ai/* prefix(vite.config.ts 프록시 참고)
// 대시보드 AI 브리핑 1건만 사용 중 — 다른 feature도 AI 호출이 필요해지면 shared/로 승격 검토(FE 리드 협의)
import axios from 'axios';
import type { ApiError } from '../../../shared/api/types';

export const aiClient = axios.create({
  baseURL: '/ai',
  withCredentials: true,
});

aiClient.interceptors.response.use(
  (response) => {
    const body = response.data as { success: boolean; data: unknown; error?: ApiError };
    if (body && body.success === false) {
      const apiError: ApiError = body.error ?? {
        code: 'LLM_INVALID_OUTPUT',
        message: 'AI 분석을 불러올 수 없습니다. 잠시 후 다시 시도해 주세요.',
      };
      return Promise.reject(apiError);
    }
    response.data = body.data;
    return response;
  },
  (error) => {
    const apiError: ApiError = error.response?.data?.error ?? {
      code: 'NETWORK_ERROR',
      message: 'AI 분석을 불러올 수 없습니다. 잠시 후 다시 시도해 주세요.',
    };
    return Promise.reject(apiError);
  },
);
