import { formatChangeRate } from '../utils/formatChangeRate';

type Props = {
  label: string;
  value: string;
  changeRate: number;
  hasAlertDot?: boolean;
};

export function KpiCard({ label, value, changeRate, hasAlertDot = false }: Props) {
  return (
    <div className="kpi-col">
      <div className="kpi-card-header">
        {hasAlertDot && <span className="kpi-card-dot" aria-hidden="true" />}
        <span className="kpi-card-label">{label}</span>
      </div>
      <p className="kpi-value-row">
        <span className="kpi-card-value">{value}</span>
        <span className={`kpi-card-change${changeRate < 0 ? ' kpi-card-change--down' : ''}`}>
          {formatChangeRate(changeRate)}
        </span>
      </p>
    </div>
  );
}
