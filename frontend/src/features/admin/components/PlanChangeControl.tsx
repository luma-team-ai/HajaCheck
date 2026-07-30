import { useState } from 'react';
import { Button } from '../../../shared/components/Button';
import { getApiErrorMessage, type ApiError } from '../../../shared/api/types';
import { adminPlanApi } from '../api/adminPlanApi';
import { useChangePlan } from '../hooks/useChangePlan';
import { formatScheduledDate, PLAN_LABEL } from '../planQuota.constants';
import type { AdminPlanCatalogItem } from '../planQuota.types';
import type { AdminUserPlan } from '../types';
import { PlanDowngradeConfirmModal, type PlanChangeTiming } from './PlanDowngradeConfirmModal';

interface PlanChangeControlProps {
  /** 로그인한 관리자 소속 회사의 현재 플랜(#508과 동일 소스) — 로딩 전 undefined, 활성 구독 없으면 null. */
  currentPlan: AdminUserPlan | null | undefined;
  catalog?: AdminPlanCatalogItem[];
  /** 현재 결제 주기 종료 시각(GET /api/admin/plan.currentPeriodEnd). null=무기한 — 예약 가능 여부·
   * 적용 예정일 표시에 쓴다(#1105 / HAJA-526, #1191). */
  currentPeriodEnd?: string | null;
  /** 이미 대기 중인 예약이 있으면 true — '예약' 선택지를 미리 막아 백엔드의 409
   * PLAN_SCHEDULED_CHANGE_EXISTS 왕복을 줄인다. */
  hasPendingSchedule?: boolean;
}

// 플랜 변경 컨트롤(#890 Phase 1/2, /admin/plans-quota 플랜 변경 흐름) — 대상 요금제를 고르면 먼저
// change-preview로 영향을 확인하고, 넘치는 자원이 없으면(requiresConfirmation=false) 바로 반영,
// 있으면(true) PlanDowngradeConfirmModal을 연다. 확인 없이 보낸 뒤에도 서버가 뒤늦게
// 409(PLAN_DOWNGRADE_CONFIRMATION_REQUIRED)를 돌려주면(미리보기 이후 인원이 늘어난 경합) 같은
// 모달로 넘겨 방어적으로 처리한다 — 메시지 문자열이 아니라 에러 코드로 분기한다.
export function PlanChangeControl({
  currentPlan,
  catalog,
  currentPeriodEnd = null,
  hasPendingSchedule = false,
}: PlanChangeControlProps) {
  const [selected, setSelected] = useState<AdminUserPlan | ''>('');
  // FREE 하향일 때만 의미가 있다(showTimingChoice=false면 항상 IMMEDIATE로 취급) — 유료 대상은
  // 정기결제 미지원으로 백엔드가 예약 자체를 403(PLAN_SCHEDULE_PAID_TARGET_UNSUPPORTED)으로 막으므로
  // UI에서도 선택지를 주지 않는다(#1177 분리, 핸드오프 §1).
  const [timing, setTiming] = useState<PlanChangeTiming>('IMMEDIATE');
  const [confirmPlanName, setConfirmPlanName] = useState<AdminUserPlan | null>(null);
  const [confirmTiming, setConfirmTiming] = useState<PlanChangeTiming>('IMMEDIATE');
  const [isChecking, setIsChecking] = useState(false);
  const [directErrorMessage, setDirectErrorMessage] = useState<string | null>(null);
  const { changePlan } = useChangePlan();

  if (currentPlan === undefined || !catalog) {
    return null;
  }

  const options = catalog.filter((item) => item.name !== currentPlan);
  // 확인 모달의 좌석 카운터·체크박스 disabled 판정(#930 재검토 F-1)에 쓸 대상 요금제의 좌석 한도.
  const confirmTargetMaxSeats = catalog.find((item) => item.name === confirmPlanName)?.maxSeats ?? null;

  const showTimingChoice = selected === 'FREE';
  // current_period_end가 없으면(무기한 구독) 백엔드가 PLAN_SCHEDULE_PERIOD_END_MISSING(409)으로
  // 거절한다 — '예약' 라디오 자체를 disabled해 헛된 요청 왕복을 막는다.
  const schedulingBlockedReason = !currentPeriodEnd
    ? '현재 요금제는 결제 주기가 없어 예약할 수 없습니다.'
    : hasPendingSchedule
      ? '이미 예약된 변경이 있습니다.'
      : null;
  const effectiveDateLabel = formatScheduledDate(currentPeriodEnd);

  function handlePlanSelect(value: AdminUserPlan | '') {
    setSelected(value);
    setDirectErrorMessage(null);
    if (value !== 'FREE' || schedulingBlockedReason) {
      setTiming('IMMEDIATE');
    }
  }

  async function handleChangeClick() {
    if (!selected) {
      return;
    }
    setDirectErrorMessage(null);

    if (selected === 'FREE' && timing === 'SCHEDULED') {
      // 예약은 항상 확인 모달을 거친다 — 신청 시점엔 아무것도 바뀌지 않는 대신, 적용 예정일과
      // FREE 1석 blast radius(owner 외 전원 정지+즉시 로그아웃)를 반드시 먼저 보여준다(핸드오프 §3).
      setConfirmTiming('SCHEDULED');
      setConfirmPlanName(selected);
      return;
    }

    setIsChecking(true);
    try {
      const preview = await adminPlanApi.previewChange(selected).then((res) => res.data);
      if (preview.requiresConfirmation) {
        setConfirmTiming('IMMEDIATE');
        setConfirmPlanName(selected);
        return;
      }
      await changePlan({ planName: selected });
      setSelected('');
    } catch (err) {
      const apiError = err as ApiError;
      if (apiError?.code === 'PLAN_DOWNGRADE_CONFIRMATION_REQUIRED') {
        // 미리보기(false)와 실제 변경 사이에 인원이 늘어 뒤늦게 확인이 필요해진 경우 — 확인 모달로 넘긴다.
        setConfirmTiming('IMMEDIATE');
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
        onChange={(event) => handlePlanSelect(event.target.value as AdminUserPlan | '')}
        className="rounded-full border border-border bg-surface px-4 py-2.5 text-sm text-text-default"
      >
        <option value="">요금제 선택</option>
        {options.map((item) => (
          <option key={item.name} value={item.name}>
            {PLAN_LABEL[item.name]}
          </option>
        ))}
      </select>

      {/* FREE 하향일 때만 노출(#1105 / HAJA-526, #1191) — 유료 대상(ENTERPRISE→STANDARD 등)은
          정기결제(빌링키) 미지원으로 백엔드가 예약 자체를 막으므로(#1177) 선택지를 주지 않는다. */}
      {showTimingChoice && (
        <div role="radiogroup" aria-label="적용 시점" className="flex flex-col gap-2">
          <label
            className={`flex cursor-pointer items-center gap-2 rounded-full border px-4 py-2 text-xs ${
              timing === 'IMMEDIATE' ? 'border-heading text-heading' : 'border-border text-text-muted'
            }`}
          >
            <input
              type="radio"
              name="plan-change-timing"
              className="h-4 w-4 accent-heading"
              checked={timing === 'IMMEDIATE'}
              onChange={() => setTiming('IMMEDIATE')}
            />
            즉시 적용
          </label>
          <label
            className={`flex cursor-pointer flex-col gap-0.5 rounded-2xl border px-4 py-2 text-xs ${
              schedulingBlockedReason
                ? 'cursor-not-allowed border-border text-text-muted opacity-60'
                : timing === 'SCHEDULED'
                  ? 'border-heading text-heading'
                  : 'border-border text-text-muted'
            }`}
          >
            <span className="flex items-center gap-2">
              <input
                type="radio"
                name="plan-change-timing"
                className="h-4 w-4 accent-heading"
                checked={timing === 'SCHEDULED'}
                disabled={Boolean(schedulingBlockedReason)}
                onChange={() => setTiming('SCHEDULED')}
              />
              다음 결제일 적용
            </span>
            <span className="pl-6">
              {schedulingBlockedReason ??
                (effectiveDateLabel
                  ? `${effectiveDateLabel}부터 적용됩니다. 그때까지 현재 요금제를 그대로 사용합니다.`
                  : '적용 예정일을 확인하는 중...')}
            </span>
          </label>
        </div>
      )}

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
        maxSeats={confirmTargetMaxSeats}
        timing={confirmTiming}
        currentPeriodEnd={currentPeriodEnd}
        onClose={() => setConfirmPlanName(null)}
        onChanged={() => {
          setConfirmPlanName(null);
          setSelected('');
          setTiming('IMMEDIATE');
        }}
      />
    </div>
  );
}
