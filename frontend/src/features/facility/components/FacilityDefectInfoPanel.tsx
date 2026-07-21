import { Button } from '../../../shared/components/Button';
import { DefectStatusStepper } from './DefectStatusStepper';
import { FacilityGradeBadge } from './FacilityGradeBadge';
import type { FacilityDefectDetail } from '../types';

type Props = {
  defect: FacilityDefectDetail;
  onTransitionClick: () => void;
};

// 우측 하자 정보 패널 — 유형/등급/크기/발견/담당 + 조치 상태 스테퍼(dev-04-02, #489).
// "다음 단계로 전이" 버튼은 상태 mutation이 아니라 /defects/:id(하자 관리 도메인)로 이동하는
// 단순 navigation이라(#489 확정) 상태에 따른 비활성화·진행중 표시는 두지 않는다.
export function FacilityDefectInfoPanel({ defect, onTransitionClick }: Props) {
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
          <dd className="m-0 font-semibold text-heading">
            폭 {defect.widthMm}mm · 길이 {defect.lengthM}m
          </dd>
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
            {defect.assigneeName}
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