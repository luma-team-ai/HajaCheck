/**
 * Recharts 공용 색상/스타일 팔레트 — GitHub #373 / Jira HAJA-249.
 *
 * recharts는 fill/stroke/contentStyle 등 SVG·인라인 스타일 prop으로 색상을 받기 때문에
 * Tailwind 클래스가 아니라 raw hex 문자열 상수로 정의한다.
 *
 * - 기본 색상은 styles/tokens.css(@theme) 토큰과 동일한 hex 값을 그대로 사용한다(신규 색상 정의 금지).
 * - 등급 색상은 features/dashboard/colors.ts의 GRADE_BG_CLASS(A~E)와 동일한 hex 값을 사용한다.
 *   GRADE_BG_CLASS 자체는 Tailwind 화살괄호 클래스 문자열(`bg-[#16a34a]`)이라 recharts SVG prop에
 *   직접 쓸 수 없어, 같은 값을 이 파일에 순수 상수로 다시 정의했다 — GRADE_BG_CLASS가 바뀌면 이 값도
 *   같이 갱신할 것.
 */

export const CHART_COLORS = {
  primary: '#18181b',
  accent: '#4a4aff',
  danger: '#d92d20',
  success: '#16a34a',
  warning: '#f97316',
  surface: '#fff',
  surfaceMuted: '#fafafa',
  border: '#e4e4e7',
  textDefault: '#494551',
  textMuted: '#7a7582',
} as const;

/** 하자 등급별 색상 — features/dashboard/colors.ts GRADE_BG_CLASS와 동일 값(A=양호 ~ E=중대). */
export const CHART_GRADE_COLORS = {
  A: '#16a34a',
  B: '#65a30d',
  C: '#eab308',
  D: '#f97316',
  E: '#dc2626',
} as const;

/** 시리즈별 color를 지정하지 않았을 때 순서대로 순환 배정되는 기본 팔레트. */
export const CHART_SERIES_COLORS: readonly string[] = [
  CHART_COLORS.primary,
  CHART_COLORS.accent,
  CHART_COLORS.success,
  CHART_COLORS.warning,
  CHART_COLORS.danger,
];

/** 전역 body 폰트(styles/global.css)와 동일 — recharts는 인라인 스타일이라 상속에 기대지 않고 명시한다. */
export const CHART_FONT_FAMILY = "'Pretendard', -apple-system, sans-serif";

/** 공통 축(tick) 텍스트 스타일 — <XAxis tick>/<YAxis tick>에 그대로 전달. */
export const CHART_AXIS_TICK_STYLE = {
  fontFamily: CHART_FONT_FAMILY,
  fontSize: 12,
  fill: CHART_COLORS.textMuted,
} as const;

/** 공통 툴팁 스타일 — <Tooltip {...CHART_TOOLTIP_STYLE} />로 그대로 전달. */
export const CHART_TOOLTIP_STYLE = {
  contentStyle: {
    fontFamily: CHART_FONT_FAMILY,
    fontSize: 13,
    backgroundColor: CHART_COLORS.surface,
    border: `1px solid ${CHART_COLORS.border}`,
    borderRadius: 8,
    padding: '8px 12px',
    color: CHART_COLORS.textDefault,
  },
  labelStyle: {
    color: CHART_COLORS.textMuted,
    fontWeight: 600,
    marginBottom: 4,
  },
  itemStyle: {
    color: CHART_COLORS.textDefault,
  },
} as const;
