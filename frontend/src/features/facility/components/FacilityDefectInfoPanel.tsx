import { Button } from '../../../shared/components/Button';
import { DefectStatusStepper } from './DefectStatusStepper';
import { FacilityGradeBadge } from './FacilityGradeBadge';
import type { FacilityDefectDetail } from '../types';

type Props = {
  defect: FacilityDefectDetail;
  onTransitionClick: () => void;
};

const PLACEHOLDER = '—';

// 우측 하자 정보 패널 — 유형/등급/크기/발견/담당 + 조치 상태 스테퍼(dev-04-02, #489).
// "다음 단계로 전이" 버튼은 상태 mutation이 아니라 점검 단위 하자 목록으로 이동하는
// 단순 navigation이라(#489 확정) 상태에 따른 비활성화·진행중 표시는 두지 않는다.
export function FacilityDefectInfoPanel({ defect, onTransitionClick }: Props) {
  // widthMm/lengthM은 균열이 아닌 유형이면 backend가 null로 내려준다(DefectResponse.crackWidthMm/
  // crackLengthMm) — 실 연동 전환(#970 갭3)으로 nullable이 되어 placeholder 처리가 필요해졌다.
  // ai-server는 현재 폭(width)만 계산하고 길이(length)는 계산하지 않아(#1487/#1547) lengthM은
  // 항상 null이다 — 둘 다 있어야만 보여주는 AND 조건이면 폭이 있어도 통째로 숨겨진다(#1588 후속).
  // 각각 독립적으로 null-safe 처리해 있는 값만 보여준다.
  // areaMm2(#1658/#1669)는 박리박락·철근노출 전용 실측 면적 — widthMm/lengthM(균열 전용)과
  // 상호 배타적이지만 같은 "크기" 항목에 병기한다. 마찬가지로 단독 null 체크.
  const sizeLabel =
    [
      defect.widthMm != null ? `폭 ${defect.widthMm}mm` : null,
      defect.lengthM != null ? `길이 ${defect.lengthM}m` : null,
      defect.areaMm2 != null ? `면적 ${defect.areaMm2}mm²` : null,
    ]
      .filter((part): part is string => part != null)
      .join(' · ') || PLACEHOLDER;

  return (
    <section className="flex flex-col gap-5">
      <h2 className="m-0 text-base font-bold text-heading">하자 정보</h2>
      <dl className="m-0 flex flex-col gap-3 text-sm">
        <div className="flex items-center justify-between">
          <dt className="text-text-muted">유형</dt>
          <dd className="m-0 font-semibold text-heading">{defect.defectType}</dd>
        </div>
        <div className="flex items-center justify-between">
          <dt className="text-text-muted">등급</dt>
          <dd className="m-0">
            <FacilityGradeBadge grade={defect.grade} withChevron />
          </dd>
        </div>
        <div className="flex items-center justify-between">
          <dt className="text-text-muted">크기</dt>
          <dd className="m-0 font-semibold text-heading">{sizeLabel}</dd>
        </div>
        <div className="flex items-center justify-between">
          <dt className="text-text-muted">발견</dt>
          <dd className="m-0 font-semibold text-heading">
            {defect.foundCycle}회차 ({defect.foundAt})
          </dd>
        </div>
        <div className="flex items-center justify-between">
          <dt className="text-text-muted">담당</dt>
          <dd className="m-0 flex items-center gap-1.5 font-semibold text-heading">
            <span
              aria-hidden="true"
              className="inline-flex h-5 w-5 items-center justify-center rounded-full bg-surface-muted text-xs"
            >
              👤
            </span>
            {defect.assigneeName ?? PLACEHOLDER}
          </dd>
        </div>
      </dl>

      <div className="flex flex-col gap-3 border-t border-border pt-4">
        <span className="text-sm font-bold text-heading">상태</span>
        <DefectStatusStepper status={defect.status} />
        <Button variant="primary" onClick={onTransitionClick}>
          다음 단계로 전이
        </Button>
      </div>
    </section>
  );
}
