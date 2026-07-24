import type { ServerResourceUsage } from '../monitoring.types';

export const SERVER_RESOURCE_TEST_ID = 'server-resource-card';

const DANGER_THRESHOLD_PERCENT = 80;

interface ServerResourceCardProps {
  resourceUsage?: ServerResourceUsage;
  isLoading: boolean;
  isError: boolean;
}

interface ResourceRow {
  key: keyof ServerResourceUsage;
  label: string;
}

const ROWS: ResourceRow[] = [
  { key: 'cpuUsagePercent', label: 'CPU' },
  { key: 'memoryUsagePercent', label: '메모리' },
  { key: 'diskUsagePercent', label: '디스크' },
];

// 서버 자원(CPU/메모리/디스크) 카드 — HF API 사용량 카드를 대체(#728, HF는 사용량 조회 공개 API가 없어
// 별도 과업으로 분리). Actuator 메트릭 기반 현재 시점 값이라 시계열 차트 없이 % 바만 표시한다.
export function ServerResourceCard({ resourceUsage, isLoading, isError }: ServerResourceCardProps) {
  const showEmpty = isLoading || isError;

  return (
    <section
      className="flex flex-col gap-5 rounded-[20px] border border-border bg-surface p-6"
      data-testid={SERVER_RESOURCE_TEST_ID}
    >
      <div className="flex items-center justify-between">
        <h3 className="m-0 text-base font-bold text-heading">서버 자원</h3>
        <span className="text-[13px] font-medium text-text-muted">현재</span>
      </div>

      {isLoading && <p className="text-sm text-text-muted">불러오는 중...</p>}
      {!isLoading && isError && (
        <p className="text-sm text-danger" role="alert">
          서버 자원 사용률을 불러오지 못했습니다.
        </p>
      )}

      {!showEmpty && resourceUsage && (
        <div className="flex flex-col gap-4">
          {ROWS.map((row) => {
            const value = resourceUsage[row.key];
            const isOverThreshold = value >= DANGER_THRESHOLD_PERCENT;
            return (
              <div key={row.key} className="flex flex-col gap-2">
                <div className="flex items-center justify-between text-sm">
                  <span className="font-semibold text-heading">{row.label}</span>
                  <span className={`font-bold ${isOverThreshold ? 'text-danger' : 'text-heading'}`}>
                    {value}%
                  </span>
                </div>
                <div className="h-2 w-full overflow-hidden rounded-full bg-[#d4d4d8]">
                  <div
                    className={`h-full rounded-full ${isOverThreshold ? 'bg-danger' : 'bg-primary'}`}
                    style={{ width: `${Math.min(value, 100)}%` }}
                  />
                </div>
              </div>
            );
          })}
        </div>
      )}
    </section>
  );
}
