import { useState, type ChangeEvent } from 'react';
import { REASON_REQUIRED_TARGETS } from '../constants/defectStatusWorkflow';
import { useChangeDefectStatus } from '../hooks/useChangeDefectStatus';
import { DEFECT_STATUS_LABEL } from '../types';
import type { Defect, DefectStatus } from '../types';
import { DefectStatusReasonModal } from './DefectStatusReasonModal';

type Props = {
  defect: Defect;
};

// 하자 상세 모달 "다른 상태로 변경"(HAJA-349/#630) — 역행/건너뛰기 사유 UI(DefectStatusReasonModal)가
// "보드 보기" 탭 롤백(#726) 이후 어떤 화면에도 연결되지 않은 채 dead code로 남아 있었다. 백엔드
// PATCH /api/defects/{id}/status는 이미 사유 기반 역행/건너뛰기를 지원하므로(Defect#changeStatus)
// 프론트만 재통합한다. REASON_REQUIRED_TARGETS는 정방향 1단계(DefectActionForm이 이미 처리)를
// 제외한 항목만 담고 있어, 이 컨트롤에서 고르는 대상은 항상 사유가 필요하다 — kind 판별 없이 선택
// 즉시 모달을 연다(useDefectActionBoard.resolveDropKind와 달리 여기선 이미 필터링돼 불필요).
export function DefectStatusChangeControl({ defect }: Props) {
  const targets = REASON_REQUIRED_TARGETS[defect.status];
  const [pendingTarget, setPendingTarget] = useState<DefectStatus | null>(null);
  const { changeStatus, isPending, error, resetError } = useChangeDefectStatus(defect.id, defect.inspectionId);

  if (!targets || targets.length === 0) {
    return null;
  }

  function handleSelect(event: ChangeEvent<HTMLSelectElement>) {
    const value = event.target.value;
    if (!value) return;
    resetError();
    setPendingTarget(value as DefectStatus);
  }

  async function handleSubmitReason(reason: string) {
    if (!pendingTarget) return;
    try {
      await changeStatus({ status: pendingTarget, reason });
      setPendingTarget(null);
    } catch {
      // 실패 메시지는 아래 인라인 alert로 노출 — 모달은 열어 둔 채 재시도할 수 있게 한다.
    }
  }

  return (
    <div className="defect-action-form__field">
      <label htmlFor="defect-status-change">다른 상태로 변경</label>
      <select id="defect-status-change" value="" disabled={isPending} onChange={handleSelect}>
        <option value="">상태를 선택하세요</option>
        {targets.map((target) => (
          <option key={target} value={target}>
            {DEFECT_STATUS_LABEL[target]}(으)로 변경
          </option>
        ))}
      </select>

      {pendingTarget && (
        <DefectStatusReasonModal
          defect={defect}
          targetStatus={pendingTarget}
          onCancel={() => {
            resetError();
            setPendingTarget(null);
          }}
          onSubmit={handleSubmitReason}
          isSubmitting={isPending}
          submitError={error ? '상태 변경에 실패했습니다. 잠시 후 다시 시도해 주세요.' : null}
        />
      )}
    </div>
  );
}
