import { useAiBriefing } from '../hooks/useAiBriefing';

// AI 실패가 대시보드의 비-AI 위젯을 막지 않아야 함(React_코드_컨벤션.md §6) — 이 카드 내부에서만
// 로딩/에러를 처리하고 다른 위젯과 완전히 독립적으로 렌더링된다.
export function AiBriefingCard() {
  const { data, isLoading, isError } = useAiBriefing();

  return (
    <section className="dashboard-card ai-briefing-card">
      <div className="ai-briefing-header">
        <span className="ai-briefing-icon" aria-hidden="true">
          ✨
        </span>
        <span className="ai-briefing-badge">AI</span>
        <h3 className="dashboard-card-title">이번 주 브리핑</h3>
      </div>

      {isLoading && <p className="dashboard-card-status">AI가 이번 주 현황을 분석하고 있습니다...</p>}
      {isError && (
        <p className="dashboard-card-status">AI 분석을 불러올 수 없습니다. 잠시 후 다시 시도해 주세요.</p>
      )}
      {!isLoading && !isError && !data && (
        <p className="dashboard-card-status">아직 생성된 브리핑이 없습니다.</p>
      )}
      {!isLoading && !isError && data && (
        <>
          <p className="ai-briefing-text">{data.briefing}</p>
          <p className="ai-briefing-recommendation">{data.recommendation}</p>
        </>
      )}
    </section>
  );
}
