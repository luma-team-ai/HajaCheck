import { useState } from 'react';
import { Button } from '../../../shared/components/Button';
import { getApiErrorMessage, type ApiError } from '../../../shared/api/types';
import { adminPlanApi } from '../api/adminPlanApi';
import { useChangePlan } from '../hooks/useChangePlan';
import { PLAN_LABEL } from '../planQuota.constants';
import type { AdminPlanCatalogItem } from '../planQuota.types';
import type { AdminUserPlan } from '../types';
import { PlanDowngradeConfirmModal } from './PlanDowngradeConfirmModal';

interface PlanChangeControlProps {
  /** 로그인한 관리자 소속 회사의 현재 플랜(#508과 동일 소스) — 로딩 전 undefined, 활성 구독 없으면 null. */
  currentPlan: AdminUserPlan | null | undefined;
  catalog?: AdminPlanCatalogItem[];
}

// 플랜 변경 컨트롤(#890 Phase 1/2, /admin/plans-quota 플랜 변경 흐름) — 대상 요금제를 고르면 먼저
// change-preview로 영향을 확인하고, 넘치는 자원이 없으면(requiresConfirmation=false) 바로 반영,
// 있으면(true) PlanDowngradeConfirmModal을 연다. 확인 없이 보낸 뒤에도 서버가 뒤늦게
// 409(PLAN_DOWNGRADE_CONFIRMATION_REQUIRED)를 돌려주면(미리보기 이후 인원이 늘어난 경합) 같은
// 모달로 넘겨 방어적으로 처리한다 — 메시지 문자열이 아니라 에러 코드로 분기한다.
export function PlanChangeControl({ currentPlan, catalog }: PlanChangeControlProps) {
  const [selected, setSelected] = useState<AdminUserPlan | ''>('');
  const [confirmPlanName, setConfirmPlanName] = useState<AdminUserPlan | null>(null);
  const [isChecking, setIsChecking] = useState(false);
  const [directErrorMessage, setDirectErrorMessage] = useState<string | null>(null);
  const { changePlan } = useChangePlan();

  if (currentPlan === undefined || !catalog) {
    return null;
  }

  const options = catalog.filter((item) => item.name !== currentPlan);

  async function handleChangeClick() {
    if (!selected) {
      return;
    }
    setDirectErrorMessage(null);
    setIsChecking(true);
    try {
      const preview = await adminPlanApi.previewChange(selected).then((res) => res.data);
      if (preview.requiresConfirmation) {
        setConfirmPlanName(selected);
        return;
      }
      await changePlan({ planName: selected });
      setSelected('');
    } catch (err) {
      const apiError = err as ApiError;
      if (apiError?.code === 'PLAN_DOWNGRADE_CONFIRMATION_REQUIRED') {
        // 미리보기(false)와 실제 변경 사이에 인원이 늘어 뒤늦게 확인이 필요해진 경우 — 확인 모달로 넘긴다.
        setConfirmPlanName(selected);
        return;
      }
      setDirectErrorMessage(getApiErrorMessage(err, '플랜 변경 확인에 실패했습니다.'));
    } finally {
      setIsChecking(false);
    }
  }

  return (
    <div className="flex flex-col gap-3 rounded-[20px] border border-border bg-surface p-5">
      <p className="m-0 text-sm font-semibold text-heading">플랜 변경</p>
      <p className="m-0 text-xs text-text-muted">
        결제 없이 즉시 적용됩니다. 회사 소유자만 변경할 수 있습니다.
      </p>

      <select
        aria-label="변경할 요금제"
        value={selected}
        onChange={(event) => {
          setSelected(event.target.value as AdminUserPlan | '');
          setDirectErrorMessage(null);
        }}
        className="rounded-full border border-border bg-surface px-4 py-2.5 text-sm text-text-default"
      >
        <option value="">요금제 선택</option>
        {options.map((item) => (
          <option key={item.name} value={item.name}>
            {PLAN_LABEL[item.name]}
          </option>
        ))}
      </select>

      {directErrorMessage && (
        <p role="alert" className="m-0 text-xs text-danger">
          {directErrorMessage}
        </p>
      )}

      <Button
        type="button"
        variant="secondary"
        disabled={!selected || isChecking}
        onClick={() => void handleChangeClick()}
      >
        {isChecking ? '확인 중...' : '변경'}
      </Button>

      <PlanDowngradeConfirmModal
        open={confirmPlanName !== null}
        planName={confirmPlanName}
        onClose={() => setConfirmPlanName(null)}
        onChanged={() => {
          setConfirmPlanName(null);
          setSelected('');
        }}
      />
    </div>
  );
}
