import { LoadingSpinner } from '../../../shared/components/LoadingSpinner';
import { COUNSEL_TYPE_LABEL } from '../stats.constants';
import type { CounselTypeDistributionItem } from '../stats.types';

interface CounselTypeDistributionChartProps {
  data: CounselTypeDistributionItem[];
  isLoading: boolean;
  isError: boolean;
}

// 상담 유형 분포 — Figma node-id 177-3515. 전체 대비 비율(DistributionBar)이 아니라 유형별
// 절대 건수를 최댓값 기준 상대 막대로 보여준다(플랜 분포와 시각적으로 구분되는 별도 패턴).
export function CounselTypeDistributionChart({ data, isLoading, isError }: CounselTypeDistributionChartProps) {
  if (isLoading) {
    return <LoadingSpinner />;
  }

  if (isError) {
    return (
      <p className="text-sm text-danger" role="alert">
        상담 유형 분포를 불러오지 못했습니다.
      </p>
    );
  }

  if (data.length === 0) {
    return <p className="text-sm text-text-muted">표시할 데이터가 없습니다.</p>;
  }

  const maxCount = Math.max(...data.map((item) => item.count));

  return (
    <ul className="m-0 flex list-none flex-col gap-4 p-0">
      {data.map((item) => (
        <li key={item.type} className="flex items-center gap-4">
          <span className="w-32 shrink-0 text-sm text-text-muted">{COUNSEL_TYPE_LABEL[item.type]}</span>
          <span
            className="h-2 flex-1 overflow-hidden rounded-full bg-surface-muted"
            role="img"
            aria-label={`${COUNSEL_TYPE_LABEL[item.type]} ${item.count}건`}
          >
            <span
              className="block h-full rounded-full bg-[#18181b]"
              style={{ width: `${maxCount === 0 ? 0 : (item.count / maxCount) * 100}%` }}
            />
          </span>
          <span className="w-12 shrink-0 text-right text-sm font-semibold text-heading">{item.count}</span>
        </li>
      ))}
    </ul>
  );
}
