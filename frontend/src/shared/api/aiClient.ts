// ai-server 전용 axios 인스턴스 — AI_개발_컨벤션.md §5 AIResponse({success,data,usage,error}) envelope 해제
// shared/api/axios.ts(baseURL=/api)와 별도 인스턴스지만, 이제 스프링 인증 프록시 /api/ai/* 경유(vite.config.ts 프록시 참고)
// React_코드_컨벤션.md §3에 따라 shared로 승격 — AI 호출이 필요한 모든 feature에서 공용으로 사용
import axios from 'axios';
import { AI_TIMEOUT_MS } from './timeouts';
import type { ApiError } from './types';

export const aiClient = axios.create({
  baseURL: '/api/ai',
  withCredentials: true,
  // 무한 대기 차단(#1598). 이 인스턴스를 타는 호출은 전부 LLM 경유(defect-explain·briefing·
  // rag-chat)라 인스턴스 기본값으로 AI 상한을 두면 충분하다 — 요청 단위 override가 필요 없다.
  // 180초를 고른 근거(스프링 ai.server connect 3s + read 150s = 153초 천장에서 역산)는 timeouts.ts.
  timeout: AI_TIMEOUT_MS,
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
