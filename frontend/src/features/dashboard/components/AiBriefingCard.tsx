import { AIErrorFallback } from '../../../shared/components/AIErrorFallback';
import { AILoadingIndicator } from '../../../shared/components/AILoadingIndicator';
import { DASHBOARD_COLOR_CLASS } from '../colors';
import { useAiBriefing } from '../hooks/useAiBriefing';

// shared/styles/layout.css의 .dashboard-card 배경/보더를 이 카드만 덮어써야 해서(그라데이션+보더 색)
// Tailwind !important 접두 사용 — 원본 CSS도 동일 목적으로 소스 순서 기반 override였음.
const AI_BRIEFING_CARD_CLASS =
  'bg-[linear-gradient(135deg,#f4f6ff_0%,#fff_70%)]! border-[#e2e6ff]!';

// AI 실패가 대시보드의 비-AI 위젯을 막지 않아야 함(React_코드_컨벤션.md §6) — 이 카드 내부에서만
// 로딩/에러를 처리하고 다른 위젯과 완전히 독립적으로 렌더링된다.
export function AiBriefingCard() {
  const { data, isLoading, isError, refetch } = useAiBriefing();

  const handleRetry = () => {
    refetch();
  };

  return (
    <section className={`dashboard-card ${AI_BRIEFING_CARD_CLASS}`}>
      <div className="flex items-center gap-2 mb-3">
        <span className="text-base" aria-hidden="true">
          ✨
        </span>
        <span
          className={`${DASHBOARD_COLOR_CLASS.accentBg} text-white text-[11px] font-bold py-0.5 px-2 rounded-full`}
        >
          AI
        </span>
        <h3 className="dashboard-card-title m-0!">이번 주 브리핑</h3>
      </div>

      {isLoading && <AILoadingIndicator message="AI가 이번 주 현황을 분석하고 있습니다..." />}
      {isError && <AIErrorFallback onRetry={handleRetry} />}
      {!isLoading && !isError && !data && (
        <p className="dashboard-card-status">아직 생성된 브리핑이 없습니다.</p>
      )}
      {!isLoading && !isError && data && (
        <>
          <p className={`text-sm leading-[1.6] ${DASHBOARD_COLOR_CLASS.bodyText} mt-0 mb-2 mx-0`}>
            {data.briefing}
          </p>
          <p className={`text-[13px] font-semibold ${DASHBOARD_COLOR_CLASS.accentText} m-0`}>
            {data.recommendation}
          </p>
        </>
      )}
    </section>
  );
}
