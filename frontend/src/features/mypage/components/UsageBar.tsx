import { formatLimit, isUsageWarning, usagePercent } from '../utils/planFormat';

type Props = {
  label: string;
  used: number;
  limit: number | null;
  unit?: string;
};

export function UsageBar({ label, used, limit, unit = '' }: Props) {
  const percent = usagePercent(used, limit);
  const warning = isUsageWarning(percent);
  const limitText = limit === null ? formatLimit(limit) : `${formatLimit(limit)}${unit}`;

  return (
    <div className="mypage-usage-row">
      <div className="mypage-usage-row-header">
        <span className="mypage-usage-label">{label}</span>
        <span className="mypage-usage-value">
          {used.toLocaleString()}
          {unit} / {limitText}
        </span>
      </div>
      <div
        className="mypage-usage-bar-track"
        role="img"
        aria-label={`${label} 사용량 ${percent ?? 0}%`}
      >
        <div
          className={`mypage-usage-bar-fill${warning ? ' mypage-usage-bar-fill--warning' : ''}`}
          style={{ width: `${percent ?? 0}%` }}
        />
      </div>
    </div>
  );
}
