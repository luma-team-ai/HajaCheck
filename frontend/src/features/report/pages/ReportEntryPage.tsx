import { useCallback, useEffect, useMemo, useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { AIErrorFallback } from '../../../shared/components/AIErrorFallback';
import { AILoadingIndicator } from '../../../shared/components/AILoadingIndicator';
import { Button } from '../../../shared/components/Button';
import { AlertModal } from '../../../shared/components/Modal';
import { DistributionBar } from '../../../shared/components/charts/DistributionBar';
import { CHART_GRADE_COLORS } from '../../../shared/components/charts/palette';
import { useInspectionResultReal } from '../../inspection/hooks/useInspectionResultReal';
import { useInspectionStore } from '../../inspection/store/inspectionStore';
import type { DefectType } from '../../inspection/types';
import { reportApi, type ReportSummaryResponse } from '../api/reportApi';
import { WARNING_TRIANGLE } from '../constants';

// 등급 라벨 — 이 페이지 전용 상수 (다른 feature와의 직접 import 금지 — #832)
const GRADE_LABELS: Record<string, string> = {
  A: '경미',
  B: '양호',
  C: '보통',
  D: '주의',
  E: '심각',
};

// 등급 배지(옅은 배경+진한 텍스트 알약) 전용 색상 — shared/chart/palette.ts의
// CHART_GRADE_COLORS와 동일한 hex로 맞춘다(node 180:5080, 180:5131 등, #925).
const GRADE_BADGE_STYLE: Record<string, { bg: string; text: string }> = {
  A: { bg: '#e3f5e6', text: '#16a34a' },
  B: { bg: '#eef6df', text: '#65a30d' },
  C: { bg: '#fef9c3', text: '#a16207' },
  D: { bg: '#ffedd5', text: '#c2410c' },
  E: { bg: '#fef2f2', text: '#dc2626' },
};

const GRADE_ORDER = ['A', 'B', 'C', 'D', 'E'] as const;



// 유형별 카드 — Figma는 5종을 항상 고정 노출하므로 0건 유형도 렌더한다(AI 자동탐지는 3종이고
// 누수·백태/도장 손상은 수동 추가로만 생기지만, 칸이 사라지면 레이아웃이 흔들린다).
// `type`은 백엔드가 내려주는 DefectType(한글) 원본값이라 매칭 키로 그대로 쓰고,
// `label`은 Figma 표기(가운뎃점·띄어쓰기)라 따로 둔다 — 둘을 합치면 매칭이 조용히 깨진다.
const DEFECT_TYPES: { type: DefectType; label: string }[] = [
  { type: '균열', label: '균열' },
  { type: '박리박락', label: '박리·박락' },
  { type: '누수·백태', label: '누수·백태' },
  { type: '철근노출', label: '철근 노출' },
  { type: '도장 손상', label: '도장 손상' },
];

function extractErrorMessage(err: unknown, fallback: string): string {
  if (err && typeof err === 'object' && 'message' in err && typeof err.message === 'string' && err.message) {
    return err.message;
  }
  if (err instanceof Error && err.message) {
    return err.message;
  }
  return fallback;
}

// Figma node 180:4977 원본 아이콘 에셋을 그대로 인라인 SVG로 재현(#925 — 아이콘 12곳 누락 수정).
// path 데이터는 Figma MCP get_design_context가 반환한 원본 그대로.
type IconSpec = { viewBox: string; path: string };
const ICONS = {
  sparkle: {
    viewBox: '0 0 13.3333 12',
    path: 'M5.33333 7.45L6 6L7.45 5.33333L6 4.66667L5.33333 3.21667L4.66667 4.66667L3.21667 5.33333L4.66667 6L5.33333 7.45V7.45M5.33333 10.6667L3.66667 7L0 5.33333L3.66667 3.66667L5.33333 0L7 3.66667L10.6667 5.33333L7 7L5.33333 10.6667V10.6667M10.6667 12L9.83333 10.1667L8 9.33333L9.83333 8.5L10.6667 6.66667L11.5 8.5L13.3333 9.33333L11.5 10.1667L10.6667 12V12M5.33333 5.33333V5.33333V5.33333V5.33333V5.33333V5.33333V5.33333V5.33333V5.33333V5.33333',
  },
  image: {
    viewBox: '0 0 16.6667 16.6667',
    path: 'M5.83333 10H14.1667L11.2917 6.25L9.375 8.75L8.08333 7.08333L5.83333 10V10M5 13.3333C4.54167 13.3333 4.14931 13.1701 3.82292 12.8438C3.49653 12.5174 3.33333 12.125 3.33333 11.6667V1.66667C3.33333 1.20833 3.49653 0.815972 3.82292 0.489583C4.14931 0.163194 4.54167 0 5 0H15C15.4583 0 15.8507 0.163194 16.1771 0.489583C16.5035 0.815972 16.6667 1.20833 16.6667 1.66667V11.6667C16.6667 12.125 16.5035 12.5174 16.1771 12.8438C15.8507 13.1701 15.4583 13.3333 15 13.3333H5V13.3333M5 11.6667H15V11.6667V11.6667V1.66667V1.66667V1.66667H5V1.66667V1.66667V11.6667V11.6667V11.6667V11.6667M1.66667 16.6667C1.20833 16.6667 0.815972 16.5035 0.489583 16.1771C0.163194 15.8507 0 15.4583 0 15V3.33333H1.66667V15V15V15H13.3333V16.6667H1.66667V16.6667M5 1.66667V1.66667V1.66667V1.66667V11.6667V11.6667V11.6667V11.6667V11.6667V11.6667V1.66667V1.66667V1.66667V1.66667',
  },
  magnifier: {
    viewBox: '0 0 15 15',
    path: 'M4.95833 7.33333L2.9375 5.33333L3.8125 4.45833L4.9375 5.58333L7.02083 3.5L7.89583 4.375L4.95833 7.33333V7.33333M13.8333 15L8.58333 9.75C8.16667 10.0833 7.6875 10.3472 7.14583 10.5417C6.60417 10.7361 6.02778 10.8333 5.41667 10.8333C3.90278 10.8333 2.62153 10.309 1.57292 9.26042C0.524305 8.21181 0 6.93056 0 5.41667C0 3.90278 0.524305 2.62153 1.57292 1.57292C2.62153 0.524305 3.90278 0 5.41667 0C6.93056 0 8.21181 0.524305 9.26042 1.57292C10.309 2.62153 10.8333 3.90278 10.8333 5.41667C10.8333 6.02778 10.7361 6.60417 10.5417 7.14583C10.3472 7.6875 10.0833 8.16667 9.75 8.58333L15 13.8333L13.8333 15V15M5.41667 9.16667C6.45833 9.16667 7.34375 8.80208 8.07292 8.07292C8.80208 7.34375 9.16667 6.45833 9.16667 5.41667C9.16667 4.375 8.80208 3.48958 8.07292 2.76042C7.34375 2.03125 6.45833 1.66667 5.41667 1.66667C4.375 1.66667 3.48958 2.03125 2.76042 2.76042C2.03125 3.48958 1.66667 4.375 1.66667 5.41667C1.66667 6.45833 2.03125 7.34375 2.76042 8.07292C3.48958 8.80208 4.375 9.16667 5.41667 9.16667V9.16667',
  },
  warningTriangle: WARNING_TRIANGLE,
  barChart: {
    viewBox: '0 0 13.3333 13.3333',
    path: 'M10 13.3333V7.5H13.3333V13.3333H10V13.3333M5 13.3333V0H8.33333V13.3333H5V13.3333M0 13.3333V4.16667H3.33333V13.3333H0V13.3333',
  },
  settingsGrid: {
    viewBox: '0 0 15 15',
    path: 'M6.66667 15V10H8.33333V11.6667H15V13.3333H8.33333V15H6.66667V15M0 13.3333V11.6667H5V13.3333H0V13.3333M3.33333 10V8.33333H0V6.66667H3.33333V5H5V10H3.33333V10M6.66667 8.33333V6.66667H15V8.33333H6.66667V8.33333M10 5V0H11.6667V1.66667H15V3.33333H11.6667V5H10V5M0 3.33333V1.66667H8.33333V3.33333H0V3.33333',
  },
  infoCircle: {
    viewBox: '0 0 11.6667 11.6667',
    path: 'M5.25 8.75H6.41667V5.25H5.25V8.75V8.75M5.83333 4.08333C5.99861 4.08333 6.13715 4.02743 6.24896 3.91563C6.36076 3.80382 6.41667 3.66528 6.41667 3.5C6.41667 3.33472 6.36076 3.19618 6.24896 3.08437C6.13715 2.97257 5.99861 2.91667 5.83333 2.91667C5.66806 2.91667 5.52951 2.97257 5.41771 3.08437C5.3059 3.19618 5.25 3.33472 5.25 3.5C5.25 3.66528 5.3059 3.80382 5.41771 3.91563C5.52951 4.02743 5.66806 4.08333 5.83333 4.08333V4.08333M5.83333 11.6667C5.02639 11.6667 4.26806 11.5135 3.55833 11.2073C2.84861 10.901 2.23125 10.4854 1.70625 9.96042C1.18125 9.43542 0.765625 8.81806 0.459375 8.10833C0.153125 7.39861 0 6.64028 0 5.83333C0 5.02639 0.153125 4.26806 0.459375 3.55833C0.765625 2.84861 1.18125 2.23125 1.70625 1.70625C2.23125 1.18125 2.84861 0.765625 3.55833 0.459375C4.26806 0.153125 5.02639 0 5.83333 0C6.64028 0 7.39861 0.153125 8.10833 0.459375C8.81806 0.765625 9.43542 1.18125 9.96042 1.70625C10.4854 2.23125 10.901 2.84861 11.2073 3.55833C11.5135 4.26806 11.6667 5.02639 11.6667 5.83333C11.6667 6.64028 11.5135 7.39861 11.2073 8.10833C10.901 8.81806 10.4854 9.43542 9.96042 9.96042C9.43542 10.4854 8.81806 10.901 8.10833 11.2073C7.39861 11.5135 6.64028 11.6667 5.83333 11.6667V11.6667M5.83333 10.5C7.13611 10.5 8.23958 10.0479 9.14375 9.14375C10.0479 8.23958 10.5 7.13611 10.5 5.83333C10.5 4.53056 10.0479 3.42708 9.14375 2.52292C8.23958 1.61875 7.13611 1.16667 5.83333 1.16667C4.53056 1.16667 3.42708 1.61875 2.52292 2.52292C1.61875 3.42708 1.16667 4.53056 1.16667 5.83333C1.16667 7.13611 1.61875 8.23958 2.52292 9.14375C3.42708 10.0479 4.53056 10.5 5.83333 10.5V10.5M5.83333 5.83333V5.83333V5.83333V5.83333V5.83333V5.83333V5.83333V5.83333V5.83333V5.83333',
  },
  document: {
    viewBox: '0 0 12 15',
    path: 'M3 12H9V10.5H3V12V12M3 9H9V7.5H3V9V9M1.5 15C1.0875 15 0.734375 14.8531 0.440625 14.5594C0.146875 14.2656 0 13.9125 0 13.5V1.5C0 1.0875 0.146875 0.734375 0.440625 0.440625C0.734375 0.146875 1.0875 0 1.5 0H7.5L12 4.5V13.5C12 13.9125 11.8531 14.2656 11.5594 14.5594C11.2656 14.8531 10.9125 15 10.5 15H1.5V15M6.75 5.25V1.5H1.5V1.5V1.5V13.5V13.5V13.5H10.5V13.5V13.5V5.25H6.75V5.25M1.5 1.5V1.5V5.25V5.25V1.5V5.25V5.25V13.5V13.5V13.5V13.5V13.5V13.5V1.5V1.5V1.5V1.5',
  },
  chevronDown: {
    viewBox: '0 0 9 5.55',
    path: 'M4.5 5.55L0 1.05L1.05 0L4.5 3.45L7.95 0L9 1.05L4.5 5.55V5.55',
  },
  globe: {
    viewBox: '0 0 15 15',
    path: 'M7.50938 15C6.47813 15 5.50625 14.8031 4.59375 14.4094C3.68125 14.0156 2.88437 13.4781 2.20312 12.7969C1.52188 12.1156 0.984375 11.3188 0.590625 10.4062C0.196875 9.49375 0 8.52187 0 7.49062C0 6.45937 0.196875 5.49062 0.590625 4.58437C0.984375 3.67812 1.52188 2.88437 2.20312 2.20312C2.88437 1.52188 3.68125 0.984375 4.59375 0.590625C5.50625 0.196875 6.47813 0 7.50938 0C8.54063 0 9.50937 0.196875 10.4156 0.590625C11.3219 0.984375 12.1156 1.52188 12.7969 2.20312C13.4781 2.88437 14.0156 3.67812 14.4094 4.58437C14.8031 5.49062 15 6.45937 15 7.49062C15 8.52187 14.8031 9.49375 14.4094 10.4062C14.0156 11.3188 13.4781 12.1156 12.7969 12.7969C12.1156 13.4781 11.3219 14.0156 10.4156 14.4094C9.50937 14.8031 8.54063 15 7.50938 15V15M7.5 13.4625C7.825 13.0125 8.10625 12.5437 8.34375 12.0562C8.58125 11.5687 8.775 11.05 8.925 10.5H6.075C6.225 11.05 6.41875 11.5687 6.65625 12.0562C6.89375 12.5437 7.175 13.0125 7.5 13.4625V13.4625M5.55 13.1625C5.325 12.75 5.12812 12.3219 4.95937 11.8781C4.79062 11.4344 4.65 10.975 4.5375 10.5H2.325C2.6875 11.125 3.14063 11.6688 3.68438 12.1313C4.22813 12.5938 4.85 12.9375 5.55 13.1625V13.1625M9.45 13.1625C10.15 12.9375 10.7719 12.5938 11.3156 12.1313C11.8594 11.6688 12.3125 11.125 12.675 10.5H10.4625C10.35 10.975 10.2094 11.4344 10.0406 11.8781C9.87187 12.3219 9.675 12.75 9.45 13.1625V13.1625M1.6875 9H4.2375C4.2 8.75 4.17187 8.50312 4.15312 8.25937C4.13437 8.01562 4.125 7.7625 4.125 7.5C4.125 7.2375 4.13437 6.98437 4.15312 6.74062C4.17187 6.49687 4.2 6.25 4.2375 6H1.6875C1.625 6.25 1.57812 6.49687 1.54688 6.74062C1.51562 6.98437 1.5 7.2375 1.5 7.5C1.5 7.7625 1.51562 8.01562 1.54688 8.25937C1.57812 8.50312 1.625 8.75 1.6875 9V9M5.7375 9H9.2625C9.3 8.75 9.32813 8.50312 9.34688 8.25937C9.36563 8.01562 9.375 7.7625 9.375 7.5C9.375 7.2375 9.36563 6.98437 9.34688 6.74062C9.32813 6.49687 9.3 6.25 9.2625 6H5.7375C5.7 6.25 5.67187 6.49687 5.65312 6.74062C5.63437 6.98437 5.625 7.2375 5.625 7.5C5.625 7.7625 5.63437 8.01562 5.65312 8.25937C5.67187 8.50312 5.7 8.75 5.7375 9V9M10.7625 9H13.3125C13.375 8.75 13.4219 8.50312 13.4531 8.25937C13.4844 8.01562 13.5 7.7625 13.5 7.5C13.5 7.2375 13.4844 6.98437 13.4531 6.74062C13.4219 6.49687 13.375 6.25 13.3125 6H10.7625C10.8 6.25 10.8281 6.49687 10.8469 6.74062C10.8656 6.98437 10.875 7.2375 10.875 7.5C10.875 7.7625 10.8656 8.01562 10.8469 8.25937C10.8281 8.50312 10.8 8.75 10.7625 9V9M10.4625 4.5H12.675C12.3125 3.875 11.8594 3.33125 11.3156 2.86875C10.7719 2.40625 10.15 2.0625 9.45 1.8375C9.675 2.25 9.87187 2.67813 10.0406 3.12188C10.2094 3.56563 10.35 4.025 10.4625 4.5V4.5M6.075 4.5H8.925C8.775 3.95 8.58125 3.43125 8.34375 2.94375C8.10625 2.45625 7.825 1.9875 7.5 1.5375C7.175 1.9875 6.89375 2.45625 6.65625 2.94375C6.41875 3.43125 6.225 3.95 6.075 4.5V4.5M2.325 4.5H4.5375C4.65 4.025 4.79062 3.56563 4.95937 3.12188C5.12812 2.67813 5.325 2.25 5.55 1.8375C4.85 2.0625 4.22813 2.40625 3.68438 2.86875C3.14063 3.33125 2.6875 3.875 2.325 4.5V4.5',
  },
  check: {
    viewBox: '0 0 10.8667 8.01667',
    path: 'M3.8 8.01667L0 4.21667L0.95 3.26667L3.8 6.11667L9.91667 0L10.8667 0.95L3.8 8.01667V8.01667',
  },
  camera: {
    viewBox: '0 0 18.3333 16.6667',
    path: 'M8.33333 10V10V10V10V10V10V10V10V10V10V10V10V10V10V10V10V10V10M1.66667 16.6667C1.20833 16.6667 0.815972 16.5035 0.489583 16.1771C0.163194 15.8507 0 15.4583 0 15V5C0 4.54167 0.163194 4.14931 0.489583 3.82292C0.815972 3.49653 1.20833 3.33333 1.66667 3.33333H4.29167L5.83333 1.66667H10.8333V3.33333H6.5625L5.04167 5H1.66667V5V5V15V15V15H15V15V15V7.5H16.6667V15C16.6667 15.4583 16.5035 15.8507 16.1771 16.1771C15.8507 16.5035 15.4583 16.6667 15 16.6667H1.66667V16.6667M15 5V3.33333H13.3333V1.66667H15V0H16.6667V1.66667H18.3333V3.33333H16.6667V5H15V5M8.33333 13.75C9.375 13.75 10.2604 13.3854 10.9896 12.6562C11.7187 11.9271 12.0833 11.0417 12.0833 10C12.0833 8.95833 11.7187 8.07292 10.9896 7.34375C10.2604 6.61458 9.375 6.25 8.33333 6.25C7.29167 6.25 6.40625 6.61458 5.67708 7.34375C4.94792 8.07292 4.58333 8.95833 4.58333 10C4.58333 11.0417 4.94792 11.9271 5.67708 12.6562C6.40625 13.3854 7.29167 13.75 8.33333 13.75V13.75M8.33333 12.0833C7.75 12.0833 7.25694 11.8819 6.85417 11.4792C6.45139 11.0764 6.25 10.5833 6.25 10C6.25 9.41667 6.45139 8.92361 6.85417 8.52083C7.25694 8.11806 7.75 7.91667 8.33333 7.91667C8.91667 7.91667 9.40972 8.11806 9.8125 8.52083C10.2153 8.92361 10.4167 9.41667 10.4167 10C10.4167 10.5833 10.2153 11.0764 9.8125 11.4792C9.40972 11.8819 8.91667 12.0833 8.33333 12.0833V12.0833',
  },
  history: {
    viewBox: '0 0 13.5 13.5',
    path: 'M6.75 13.5C5.025 13.5 3.52187 12.9281 2.24062 11.7844C0.959375 10.6406 0.225 9.2125 0.0375 7.5H1.575C1.75 8.8 2.32813 9.875 3.30938 10.725C4.29063 11.575 5.4375 12 6.75 12C8.2125 12 9.45313 11.4906 10.4719 10.4719C11.4906 9.45313 12 8.2125 12 6.75C12 5.2875 11.4906 4.04688 10.4719 3.02813C9.45313 2.00938 8.2125 1.5 6.75 1.5C5.8875 1.5 5.08125 1.7 4.33125 2.1C3.58125 2.5 2.95 3.05 2.4375 3.75H4.5V5.25H0V0.75H1.5V2.5125C2.1375 1.7125 2.91562 1.09375 3.83437 0.65625C4.75312 0.21875 5.725 0 6.75 0C7.6875 0 8.56562 0.178125 9.38437 0.534375C10.2031 0.890625 10.9156 1.37187 11.5219 1.97812C12.1281 2.58437 12.6094 3.29687 12.9656 4.11562C13.3219 4.93437 13.5 5.8125 13.5 6.75C13.5 7.6875 13.3219 8.56562 12.9656 9.38437C12.6094 10.2031 12.1281 10.9156 11.5219 11.5219C10.9156 12.1281 10.2031 12.6094 9.38437 12.9656C8.56562 13.3219 7.6875 13.5 6.75 13.5V13.5M8.85 9.9L6 7.05V3H7.5V6.45L9.9 8.85L8.85 9.9V9.9',
  },
  clock: {
    viewBox: '0 0 16 16',
    path: 'M8 0C12.4183 0 16 3.58172 16 8C16 12.4183 12.4183 16 8 16C3.58172 16 0 12.4183 0 8C0 3.58172 3.58172 0 8 0ZM8 2.5C4.96243 2.5 2.5 4.96243 2.5 8C2.5 11.0376 4.96243 13.5 8 13.5C11.0376 13.5 13.5 11.0376 13.5 8C13.5 4.96243 11.0376 2.5 8 2.5ZM7.25 4H8.75V8.5L11.5 10.5L10.5 12L7.25 9.5V4Z',
  },
  documentAccent: {
    // 최근 작업 내역 항목 아이콘 — 문서 아이콘, 연빨강 원형 배경 위에 표시(#925)
    viewBox: '0 0 16.6667 16.6667',
    path: 'M5.83333 8.75H6.66667V7.08333H7.5C7.73611 7.08333 7.93403 7.00347 8.09375 6.84375C8.25347 6.68403 8.33333 6.48611 8.33333 6.25V5.41667C8.33333 5.18056 8.25347 4.98264 8.09375 4.82292C7.93403 4.66319 7.73611 4.58333 7.5 4.58333H5.83333V8.75V8.75M6.66667 6.25V5.41667H7.5V6.25H6.66667V6.25M9.16667 8.75H10.8333C11.0694 8.75 11.2674 8.67014 11.4271 8.51042C11.5868 8.35069 11.6667 8.15278 11.6667 7.91667V5.41667C11.6667 5.18056 11.5868 4.98264 11.4271 4.82292C11.2674 4.66319 11.0694 4.58333 10.8333 4.58333H9.16667V8.75V8.75M10 7.91667V5.41667H10.8333V7.91667H10V7.91667M12.5 8.75H13.3333V7.08333H14.1667V6.25H13.3333V5.41667H14.1667V4.58333H12.5V8.75V8.75M5 13.3333C4.54167 13.3333 4.14931 13.1701 3.82292 12.8438C3.49653 12.5174 3.33333 12.125 3.33333 11.6667V1.66667C3.33333 1.20833 3.49653 0.815972 3.82292 0.489583C4.14931 0.163194 4.54167 0 5 0H15C15.4583 0 15.8507 0.163194 16.1771 0.489583C16.5035 0.815972 16.6667 1.20833 16.6667 1.66667V11.6667C16.6667 12.125 16.5035 12.5174 16.1771 12.8438C15.8507 13.1701 15.4583 13.3333 15 13.3333H5V13.3333M5 11.6667H15V11.6667V11.6667V1.66667V1.66667V1.66667H5V1.66667V1.66667V11.6667V11.6667V11.6667V11.6667M1.66667 16.6667C1.20833 16.6667 0.815972 16.5035 0.489583 16.1771C0.163194 15.8507 0 15.4583 0 15V3.33333H1.66667V15V15V15H13.3333V16.6667H1.66667V16.6667M5 1.66667V1.66667V1.66667V1.66667V11.6667V11.6667V11.6667V11.6667V11.6667V11.6667V1.66667V1.66667V1.66667V1.66667',
  },
} satisfies Record<string, IconSpec>;

function Icon({ spec, fill, className }: { spec: IconSpec; fill: string; className?: string }) {
  return (
    <svg className={className} viewBox={spec.viewBox} fill="none" xmlns="http://www.w3.org/2000/svg" aria-hidden="true">
      <path d={spec.path} fill={fill} />
    </svg>
  );
}

// 등급 배지(옅은 배경 알약) — 최고등급(요약 스트립)·유형별 카드 5개가 공유(#925).
function GradeBadge({ grade, size = 'md' }: { grade: string; size?: 'sm' | 'md' }) {
  const style = GRADE_BADGE_STYLE[grade];
  if (!style) return null;
  return (
    <div
      className={`inline-flex items-center justify-center rounded-2xl font-bold ${
        size === 'sm' ? 'px-1.5 py-0.5 text-[10px]' : 'px-2 py-0.5 text-xs'
      }`}
      style={{ backgroundColor: style.bg, color: style.text }}
      title={`${grade}등급 · ${GRADE_LABELS[grade]}`}
    >
      {grade}
    </div>
  );
}

export function ReportEntryPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const inspectionId = Number(id);

  const setActiveReportId = useInspectionStore((state) => state.setActiveReportId);

  // 데이터 조회
  const { data, isLoading, isError, refetch } = useInspectionResultReal(inspectionId);

  const [sections, setSections] = useState({
    overview: true,
    summary: true,
    details: true,
    recommendation: true,
    opinion: false,
  });
  const [includePhoto, setIncludePhoto] = useState(true);

  // UI 상태
  const [isGenerating, setIsGenerating] = useState(false);
  // 다른 보고서 화면(ReportGeneratePage)과 동일한 패턴 — 네이티브 alert() 대신 AlertModal로 통일.
  const [alertModal, setAlertModal] = useState<{ open: boolean; title?: string; message: string }>({
    open: false,
    message: '',
  });
  const closeAlertModal = useCallback(() => setAlertModal((prev) => ({ ...prev, open: false })), []);
  const [reports, setReports] = useState<ReportSummaryResponse[]>([]);
  const [reportsLoading, setReportsLoading] = useState(false);

  // 최근 작업 내역 조회 — cleanup에서 직전 요청을 취소한다(#886 P2).
  // ref에 마지막 컨트롤러만 담고 언마운트에서 한 번 abort하는 방식은, 같은 컴포넌트 인스턴스가
  // 유지된 채 :id만 바뀌는 경우(A 요청 in-flight 중 B로 전환) A를 취소하지 못한다.
  // 그러면 늦게 도착한 A의 응답이 B 화면의 목록을 덮어쓴다(경쟁 조건).
  useEffect(() => {
    if (!Number.isInteger(inspectionId) || inspectionId <= 0) return;

    const controller = new AbortController();

    setReportsLoading(true);
    reportApi
      .listReports(inspectionId, controller.signal)
      .then((response) => {
        if (!controller.signal.aborted) {
          setReports(response.data);
        }
      })
      .catch(() => {
        if (!controller.signal.aborted) {
          setReports([]);
        }
      })
      .finally(() => {
        if (!controller.signal.aborted) {
          setReportsLoading(false);
        }
      });

    return () => controller.abort();
  }, [inspectionId]);

  // 등급별 분포·유형별 카드는 "확정 하자"(검수 완료, reviewedCount의 대상)만 집계한다(#886 P3).
  // 상단 스탯의 '확정 하자' 수치와 기준을 맞추기 위함 — 미검수(오탐 가능) 하자를 섞으면
  // 두 블록의 건수 기준이 서로 달라 보인다.
  // Defect엔 isReviewed가 없지만 status로 동일하게 판별 가능하다 — 백엔드 ReportService의
  // CONFIRMED_DEFECT_STATUSES(=DETECTED 제외 전부)와 정확히 같은 기준.
  const confirmedDefects = data?.defects.filter((d) => d.status !== 'DETECTED') ?? [];

  const gradeDistribution = confirmedDefects.reduce(
    (acc, d) => {
      acc[d.grade] = (acc[d.grade] || 0) + 1;
      return acc;
    },
    {} as Record<string, number>,
  );

  // 유형별 최고 등급 계산 (심각도 순: E > D > C > B > A)
  const defectTypeStats = DEFECT_TYPES.map((typeInfo) => {
    const typeDefects = confirmedDefects.filter((d) => d.type === typeInfo.type);
    const count = typeDefects.length;
    // 심각도가 높은 순서 (E가 가장 높음)
    const severityOrder = { E: 5, D: 4, C: 3, B: 2, A: 1 };
    const maxGrade =
      typeDefects.length > 0
        ? typeDefects.reduce((max, d) => {
            const maxSev = severityOrder[max as keyof typeof severityOrder] || 0;
            const curSev = severityOrder[d.grade as keyof typeof severityOrder] || 0;
            return curSev > maxSev ? d.grade : max;
          }, typeDefects[0].grade)
        : null;
    return { ...typeInfo, count, maxGrade };
  });

  // 전체 최고 등급 (가장 심각한 등급)
  const maxGrade = data
    ? ['E', 'D', 'C', 'B', 'A'].find((grade) => gradeDistribution[grade] && gradeDistribution[grade] > 0) ||
      null
    : null;

  const selectedSectionKeys = useMemo(
    () => Object.entries(sections)
      .filter(([, v]) => v)
      .map(([k]) => k),
    [sections],
  );
  const hasSelectedSections = selectedSectionKeys.length > 0;

  const handleGenerateReport = useCallback(async () => {
    if (!data || data.reviewedCount !== data.totalCount || isGenerating || !hasSelectedSections) return;

    setIsGenerating(true);
    try {
      const response = await reportApi.generateReportDraft(inspectionId, {
        sections: selectedSectionKeys,
        includePhoto,
      });
      setActiveReportId(response.data.id);
      navigate(`/reports/${response.data.id}`);
    } catch (error) {
      setAlertModal({
        open: true,
        title: '보고서 생성 실패',
        message: extractErrorMessage(error, '보고서 생성에 실패했습니다.'),
      });
      setIsGenerating(false);
    }
  }, [inspectionId, data, isGenerating, hasSelectedSections, selectedSectionKeys, includePhoto, navigate]);

  const handleEditReport = useCallback(
    (reportId: number) => {
      setActiveReportId(reportId);
      navigate(`/reports/${reportId}`);
    },
    [inspectionId, navigate, setActiveReportId],
  );

  if (!Number.isInteger(inspectionId) || inspectionId <= 0) {
    return <div className="p-5 text-red-600">잘못된 접근입니다. 유효한 검사 ID를 확인하세요.</div>;
  }

  if (isLoading) return <AILoadingIndicator message="점검 결과를 분석 중입니다..." />;
  if (isError) return <AIErrorFallback onRetry={() => void refetch()} />;
  if (!data) return <div className="p-5 text-red-600">데이터를 불러올 수 없습니다.</div>;

  // 완료율 배지·"보고서 생성 시작" 버튼(247/614행)이 같은 기준을 쓰도록 통일 — totalCount=0(하자
  // 0건)이면 검수할 게 없으니 완료로 취급한다(#945).
  const isComplete = data.reviewedCount === data.totalCount;

  return (
    <div className="pb-6">
      {/* Figma node 180:5040 "Background+Border+Shadow" — 섹션 1~5(제목~최근작업내역)를
          감싸는 흰색 카드. 이전 구현에 이 바깥 카드 자체가 통째로 빠져 있었다(#927).
          6.하단 액션바는 Figma에서도 이 카드의 형제 요소로 별도 카드다(고정 오버레이 아님, #961). */}
      <div className="mx-auto flex max-w-[1024px] flex-col gap-8 rounded-[20px] border border-border bg-white p-8 shadow-[0px_8px_12px_rgba(0,0,0,0.08)]">
      {/* 1. Title Section — "회차 상세"/"보고서 생성" 버튼 제거(#933 후속, 사용자 확정):
          이 페이지 자체가 이미 회차 상세 컨텍스트이고, "보고서 생성"은 하단 액션바의
          "보고서 생성 시작" 버튼과 중복이라 상단 버튼 쌍은 불필요했다. */}
      <div>
        <h1 className="text-3xl font-medium tracking-tight text-black">
          점검 회차 요약 — {data.roundNo}회차
        </h1>
        <p className="mt-2 text-base font-medium text-text-muted">
          검수가 완료된 하자를 바탕으로 점검 보고서 초안을 생성합니다.
        </p>
      </div>

      {/* 2. Round Summary Strip */}
      <div className="flex items-center justify-between gap-6 rounded-full border border-border bg-surface-muted px-6 py-2">
        <div className="flex gap-6">
          {/* 이미지 개수 */}
          <div className="flex gap-3 border-r border-border pr-6">
            <Icon spec={ICONS.image} fill="#77767B" className="h-[17px] w-[17px] shrink-0 self-center" />
            <div>
              <div className="text-xs font-medium tracking-wide text-text-muted">이미지</div>
              <div className="mt-1 flex min-h-7 items-center text-xl font-bold text-black">
                {data.media?.length || 0}장
              </div>
            </div>
          </div>

          {/* 확정 하자 */}
          <div className="flex gap-3 border-r border-border pr-6">
            <Icon spec={ICONS.magnifier} fill="#77767B" className="h-[15px] w-[15px] shrink-0 self-center" />
            <div>
              <div className="text-xs font-medium tracking-wide text-text-muted">확정 하자</div>
              <div className="mt-1 flex min-h-7 items-center text-xl font-bold text-black">{data.reviewedCount}</div>
            </div>
          </div>

          {/* 최고 등급 */}
          <div className="flex gap-3 pr-6">
            <Icon spec={ICONS.warningTriangle} fill="currentColor" className="h-4 w-4 shrink-0 self-center text-text-muted" />
            <div>
              <div className="text-xs font-medium tracking-wide text-text-muted">최고 등급</div>
              <div className="mt-1 flex min-h-7 items-center gap-2">
                {maxGrade ? <GradeBadge grade={maxGrade} /> : <span className="text-text-muted">없음</span>}
              </div>
            </div>
          </div>
        </div>

        {/* 검수 완료율 — 실제 reviewedCount/totalCount 계산(이전엔 "100%" 리터럴 하드코딩, #939) */}
        <div className="flex items-center gap-3">
          <div className="text-right">
            <div className="text-xs font-medium tracking-wide text-text-muted">검수 완료율</div>
            <div className="mt-1 flex min-h-7 items-center text-xl font-bold text-black">
              {data.totalCount > 0 ? Math.round((data.reviewedCount / data.totalCount) * 100) : 0}%
            </div>
          </div>
          {isComplete ? (
            <div className="inline-flex items-center gap-1 rounded-full bg-success-soft-bg px-3 py-1">
              <div className="h-1.5 w-1.5 rounded-full bg-success" />
              <span className="text-xs font-medium text-success">완료</span>
            </div>
          ) : (
            <div className="inline-flex items-center gap-1 rounded-full bg-surface-muted px-3 py-1">
              <div className="h-1.5 w-1.5 rounded-full bg-text-muted" />
              <span className="text-xs font-medium text-text-muted">진행 중</span>
            </div>
          )}
        </div>
      </div>

      {/* 3. Defect Summary Block */}
      <div className="flex flex-col gap-6">
        <h2 className="flex items-center gap-2 text-xl font-medium text-black">
          <Icon spec={ICONS.barChart} fill="currentColor" className="h-[13px] w-[13px]" />
          <span>하자 현황</span>
        </h2>

        {/* 등급별 분포 카드 */}
        <div className="rounded-[20px] border border-border bg-white p-6">
          <div className="mb-3 text-xs font-medium tracking-wide text-text-muted">등급별 분포</div>

          {/* 분포 바 — 공용 DistributionBar 재사용. 다만 이 컴포넌트의 기본 범례는 "라벨 (퍼센트%)"라
              Figma의 "A (13)"(건수) 표기와 달라서, 범례만 끄고 아래에서 직접 렌더한다. */}
          <div className="mb-4">
            <DistributionBar
              ariaLabel="하자 등급별 분포"
              height={16}
              showLegend={false}
              segments={GRADE_ORDER.map((grade) => ({
                key: grade,
                label: grade,
                // 분자(gradeDistribution)가 확정 하자 기준이므로 분모도 맞춘다 —
                // totalCount로 나누면 미검수분만큼 막대가 100%를 못 채운다.
                percent: ((gradeDistribution[grade] || 0) / (confirmedDefects.length || 1)) * 100,
                color: CHART_GRADE_COLORS[grade],
              }))}
            />
          </div>

          {/* 범례 */}
          <div className="flex flex-wrap gap-2">
            {GRADE_ORDER.map((grade) => {
              const count = gradeDistribution[grade] || 0;
              return (
                <div
                  key={grade}
                  className="flex items-center gap-2 rounded-full border border-border bg-surface-muted px-3 py-1.5"
                >
                  <div
                    className="h-2 w-2 rounded-full"
                    style={{ backgroundColor: CHART_GRADE_COLORS[grade] }}
                  />
                  <span className="text-xs font-medium text-black">
                    {grade} ({count})
                  </span>
                </div>
              );
            })}
          </div>
        </div>

        {/* 유형별 카드 — 5칸 고정 균등폭 그리드(Figma 180:5127, #925) */}
        <div className="grid grid-cols-5 gap-4">
          {defectTypeStats.map((stat) => (
            <div
              key={stat.type}
              data-testid={`defect-type-card-${stat.type}`}
              className="flex h-[112px] flex-col justify-between rounded-[20px] border border-border bg-white p-[17px]"
            >
              <div className="flex items-start justify-between gap-2">
                <div className="text-sm font-medium text-text-default">{stat.label}</div>
                {stat.maxGrade && (
                  <div className="flex shrink-0 items-center gap-1">
                    <span className="text-[10px] font-medium whitespace-nowrap text-text-muted">최고 등급</span>
                    <GradeBadge grade={stat.maxGrade} size="sm" />
                  </div>
                )}
              </div>
              <div className="text-3xl font-semibold text-black">{stat.count}</div>
            </div>
          ))}
        </div>
      </div>

      {/* 4. Report Options */}
      <div className="flex flex-col gap-6">
        <div className="flex items-end justify-between">
          <h2 className="flex items-center gap-2 text-xl font-medium text-black">
            <Icon spec={ICONS.settingsGrid} fill="currentColor" className="h-[15px] w-[15px]" />
            보고서 설정
          </h2>
          <div className="inline-flex items-center gap-1 rounded-full bg-surface-muted px-3 py-1">
            <Icon spec={ICONS.infoCircle} fill="#77767B" className="h-3 w-3" />
            <span className="text-xs text-text-muted">
              AI가 수치를 자동 대조합니다
            </span>
          </div>
        </div>

        <div className="rounded-[20px] border border-border bg-surface p-6">
          {/* 섹션 선택 */}
          <div className="mb-6">
            <div className="mb-3 text-xs font-medium tracking-wide text-text-muted">
              포함할 섹션 (자동 초안)
            </div>
            <div className="flex flex-wrap gap-2">
              {[
                // 라벨은 실제 생성된 보고서 편집기의 섹션명과 동일하게 맞춘다(sectionOrder.ts의
                // FIXED_SECTION_LABELS, SummarySection.tsx의 "종합 의견" 필드 라벨) — key 값은
                // 백엔드 계약(GroundingReportContentSerializer.ALL_SECTIONS)과 그대로 맞춰야 하므로
                // 변경하지 않는다.
                { key: 'overview', label: '기본현황' },
                { key: 'summary', label: '결과 요약' },
                { key: 'details', label: '진단 외관조사결과 기본사항' },
                { key: 'recommendation', label: '보수ㆍ보강(안)' },
                { key: 'opinion', label: '종합 의견' },
              ].map((sec) => {
                const sectionKey = sec.key as keyof typeof sections;
                const isSelected = sections[sectionKey];
                return (
                  <button
                    key={sec.key}
                    type="button"
                    aria-pressed={isSelected}
                    onClick={() => setSections((prev) => ({ ...prev, [sectionKey]: !prev[sectionKey] }))}
                    className={`flex items-center gap-2 rounded-full border px-4 py-2 text-sm font-medium transition-all ${
                      isSelected
                        ? 'border-black bg-black text-white'
                        : 'border-border bg-white text-black'
                    }`}
                  >
                    {isSelected && <Icon spec={ICONS.check} fill="currentColor" className="h-2 w-[11px]" />}
                    {sec.label}
                  </button>
                );
              })}
            </div>
          </div>

          {/* 대표 사진 토글 */}
          <div className="flex items-center justify-between rounded-full border border-border bg-white p-4">
            <div className="flex items-center gap-3">
              <Icon spec={ICONS.camera} fill="currentColor" className="h-[15px] w-4 shrink-0 text-black" />
              <div className="flex flex-col gap-1">
                <div className="text-sm font-medium text-black">대표 사진 자동 삽입</div>
                <div className="text-xs text-text-muted">
                  각 유형별 최고 등급 하자의 원본/AI마커 이미지를 첨부합니다.
                </div>
              </div>
            </div>
            <button
              type="button"
              aria-label="대표 사진 자동 삽입"
              onClick={() => setIncludePhoto((prev) => !prev)}
              className={`relative inline-flex h-6 w-11 items-center rounded-full transition-colors ${
                includePhoto ? 'bg-black' : 'bg-border'
              }`}
            >
              <span
                className={`inline-block h-4 w-4 transform rounded-full bg-white transition-transform ${
                  includePhoto ? 'translate-x-6' : 'translate-x-1'
                }`}
              />
            </button>
          </div>
        </div>
      </div>

      {/* 5. Previous Reports */}
      {!reportsLoading && reports.length > 0 && (
        <div className="rounded-2xl border border-border bg-surface-soft p-5">
          <h3 className="mb-3 flex items-center gap-2 text-sm font-medium text-black">
            <Icon spec={ICONS.history} fill="#77767B" className="h-[13.5px] w-[13.5px]" />
            <span>최근 작업 내역</span>
          </h3>

          <div className="space-y-2">
            {reports.map((report) => (
              <div
                key={report.id}
                className="flex items-center justify-between rounded-full border border-border bg-white p-3"
              >
                <div className="flex items-center gap-3">
                  {/* ponytail: bg-danger-soft-bg는 tokens.css의 --color-danger-soft-bg(#fef2f2)와 동일값 — 하드코딩 대신 기존 토큰 재사용(#928) */}
                  <div className="flex h-10 w-10 items-center justify-center rounded-2xl bg-danger-soft-bg">
                    <Icon spec={ICONS.documentAccent} fill="#EF4444" className="h-[17px] w-[17px]" />
                  </div>
                  <div>
                    <div className="text-sm font-medium text-black">{data.roundNo}회차 보고서 (초안)</div>
                    <div className="text-xs text-text-muted">
                      {new Date(report.createdAt).toLocaleDateString('ko-KR')}
                    </div>
                  </div>
                  <div className="rounded-full bg-surface-muted px-2 py-1">
                    <span className="text-xs font-medium text-text-default">
                      {report.status === 'DRAFT' ? '편집 중' : '확정됨'}
                    </span>
                  </div>
                </div>

                <Button
                  size="sm"
                  variant="secondary"
                  onClick={() => handleEditReport(report.id)}
                >
                  이어서 편집
                </Button>
              </div>
            ))}
          </div>
        </div>
      )}
      </div>

      {/* 6. Action Bar — 별도 카드 박스 (fixed 오버레이 아님, #961) */}
      <div className="mx-auto mt-6 w-full max-w-[1024px] rounded-2xl border border-border bg-white px-6 py-4 shadow-[0px_8px_12px_rgba(0,0,0,0.08)]">
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-2 text-sm text-text-muted">
            <Icon spec={ICONS.clock} fill="#77767B" className="h-4 w-4" />
            <span>
              확정 하자 <span className="font-semibold text-black">{data.reviewedCount}건</span> 기준
            </span>
          </div>
          <div className="flex gap-3">
            <Button
              variant="secondary"
              size="md"
              onClick={() =>
                reports.length > 0
                  ? navigate(`/reports/${reports[0].id}?mode=export`)
                  : setAlertModal({
                      open: true,
                      title: '아직 생성된 보고서가 없습니다',
                      message: "이 점검에 대해 생성된 보고서가 없습니다. 먼저 '보고서 생성 시작'으로 보고서를 만들어주세요.",
                    })
              }
            >
              최근 보고서 보기
            </Button>
            <Button
              variant="primary"
              size="md"
              disabled={!isComplete || isGenerating || !hasSelectedSections}
              onClick={handleGenerateReport}
            >
              <span className="inline-flex items-center gap-2">
                {isGenerating ? '생성 중...' : '보고서 생성 시작'}
                <Icon spec={ICONS.sparkle} fill="currentColor" className="h-3 w-3.5" />
              </span>
            </Button>
          </div>
        </div>
      </div>

      <AlertModal
        open={alertModal.open}
        title={alertModal.title}
        message={alertModal.message}
        onClose={closeAlertModal}
      />
    </div>
  );
}
