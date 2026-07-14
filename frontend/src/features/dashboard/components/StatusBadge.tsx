import type { InspectionStatus } from '../types';
import { getInspectionStatusVariant } from '../utils/statusBadge';

type Props = {
  status: InspectionStatus;
};

export function StatusBadge({ status }: Props) {
  const variant = getInspectionStatusVariant(status);
  return <span className={`status-badge status-badge--${variant}`}>{status}</span>;
}
