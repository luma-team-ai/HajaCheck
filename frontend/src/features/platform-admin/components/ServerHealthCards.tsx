import { SERVER_HEALTH_DOT_CLASS, SERVER_HEALTH_STATUS_LABEL } from '../monitoring.constants';
import type { ServerHealthItem } from '../monitoring.types';

export const SERVER_HEALTH_TEST_ID = 'server-health-cards';

interface ServerHealthCardsProps {
  items: ServerHealthItem[];
  isLoading: boolean;
  isError: boolean;
}

// 상단 인프라 상태 카드 3종(API 서버/AI 분석 서버/DB) — Figma node-id 1-404.
// 로딩·에러 시에도 카드 자리는 유지하고 상태 점만 회색으로 낮춘다(자리 이동 방지).
export function ServerHealthCards({ items, isLoading, isError }: ServerHealthCardsProps) {
  const showEmpty = isLoading || isError;

  return (
    <div
      className="grid grid-cols-1 gap-4 sm:grid-cols-3"
      data-testid={SERVER_HEALTH_TEST_ID}
    >
      {items.map((item) => (
        <section
          key={item.id}
          className="flex items-center justify-between rounded-2xl border border-border bg-surface px-5 py-4"
        >
          <div className="flex items-center gap-2.5">
            <span
              className={`inline-block h-2.5 w-2.5 rounded-full ${showEmpty ? 'bg-text-muted' : SERVER_HEALTH_DOT_CLASS[item.status]}`}
            />
            <span className="text-sm font-semibold text-heading">{item.name}</span>
          </div>
          <div className="flex items-center gap-2">
            <span className="text-[13px] font-medium text-text-muted">
              {showEmpty ? '-' : SERVER_HEALTH_STATUS_LABEL[item.status]}
            </span>
            {!showEmpty && item.metric && (
              <span className="text-[13px] font-semibold text-text-default">{item.metric}</span>
            )}
          </div>
        </section>
      ))}
    </div>
  );
}
