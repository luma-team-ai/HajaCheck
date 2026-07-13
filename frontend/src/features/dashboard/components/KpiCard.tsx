import { formatChangeRate } from '../utils/formatChangeRate';

type Props = {
  label: string;
  value: string;
  changeRate: number;
  hasAlertDot?: boolean;
};

export function KpiCard({ label, value, changeRate, hasAlertDot = false }: Props) {
  return (
    <div className="kpi-card">
      <div className="kpi-card-header">
        <span className="kpi-card-label">{label}</span>
        {hasAlertDot && <span className="kpi-card-dot" aria-hidden="true" />}
      </div>
      <p className="kpi-card-value">{value}</p>
      <p className={`kpi-card-change${changeRate < 0 ? ' kpi-card-change--down' : ''}`}>
        {formatChangeRate(changeRate)}
      </p>
    </div>
  );
}
