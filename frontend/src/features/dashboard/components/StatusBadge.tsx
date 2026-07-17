import type { InspectionStatus } from '../types';
import { getInspectionStatusClass } from '../utils/statusBadge';

type Props = {
  status: InspectionStatus;
};

export function StatusBadge({ status }: Props) {
  return (
    <span
      className={`inline-block py-1 px-2.5 rounded-full text-xs font-bold ${getInspectionStatusClass(status)}`}
    >
      {status}
    </span>
  );
}
