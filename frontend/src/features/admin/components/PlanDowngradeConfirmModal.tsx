import { useEffect, useMemo, useState } from 'react';
import { Button } from '../../../shared/components/Button';
import { Modal } from '../../../shared/components/Modal';
import { getApiErrorMessage } from '../../../shared/api/types';
import { useChangePlan } from '../hooks/useChangePlan';
import { usePlanChangePreview } from '../hooks/usePlanChangePreview';
import { usePlanQuotaUsers } from '../hooks/usePlanQuotaUsers';
import { PLAN_LABEL } from '../planQuota.constants';
import type { AdminUserPlan } from '../types';

// 유지 대상 선택 UI에서 한 번에 보여줄 회사 멤버 수 상한 — 서버 @Max(100)과 맞춘다. 회사 규모가
// 100명을 넘으면 이 모달에서 전원을 선택지로 보여주지 못한다(이 요금제 정책상 좌석 한도가 최대
// 수십 단위라 실용적으로는 충분하다고 판단 — 넘는 경우는 후속 이슈로 남길 수 있음).
const MEMBER_ROSTER_PAGE_SIZE = 100;

interface PlanDowngradeConfirmModalProps {
  open: boolean;
  /** 확인 대상 요금제 — open=false 이거나 아직 미선택이면 null. */
  planName: AdminUserPlan | null;
  onClose: () => void;
  /** 변경이 성공적으로 반영된 뒤 호출 — 부모가 선택 상태를 리셋한다. */
  onChanged: () => void;
}

// 플랜 하향 확인 모달(#890 Phase 1 확인 UX + Phase 2 keepUserIds) — /admin/plans-quota 플랜 변경 흐름.
// change-preview가 requiresConfirmation=true를 돌려줄 때만 연다. 정지될 구성원 이름·이메일과
// 읽기전용 시설물 "총량"을 보여주고, 관리자가 유지할 구성원을 직접 선택할 수 있게 한다 — 기본값은
// 서버가 계산한 id 오름차순 자동 선정이며, 체크박스를 건드리기 전까지는 그 기본값을 그대로 보여준다.
export function PlanDowngradeConfirmModal({
  open,
  planName,
  onClose,
  onChanged,
}: PlanDowngradeConfirmModalProps) {
  // null = 관리자가 아직 유지 대상을 직접 고르지 않음(서버 기본값 사용). 커스터마이즈하면 구체적인
  // id 배열이 된다 — 빈 배열([])도 "직접 전원 정지 선택"이라는 유효한 커스터마이즈 상태다.
  const [customKeepUserIds, setCustomKeepUserIds] = useState<number[] | null>(null);

  // 모달이 닫힐 때 커스터마이즈 상태를 리셋 — 다음에 다른 플랜으로 다시 열어도 이전 선택이 새지 않게.
  useEffect(() => {
    if (!open) {
      setCustomKeepUserIds(null);
    }
  }, [open]);

  const previewKeepUserIds = useMemo(() => customKeepUserIds ?? [], [customKeepUserIds]);
  const {
    data: preview,
    isLoading: previewLoading,
    isError: previewIsError,
  } = usePlanChangePreview(planName, previewKeepUserIds, open);

  // 유지 대상 선택 UI용 회사 멤버 전체 목록 — plan-quota 목록과 같은 데이터 소스를 재사용한다.
  const { data: rosterData } = usePlanQuotaUsers({ page: 1, size: MEMBER_ROSTER_PAGE_SIZE });
  const roster = rosterData?.content ?? [];

  const suspendIds = useMemo(
    () => new Set((preview?.seatsToSuspend ?? []).map((target) => target.userId)),
    [preview],
  );

  const { changePlan, isPending, error, resetError } = useChangePlan();

  if (!open || !planName) {
    return null;
  }

  // 체크박스 표시 상태 — 아직 커스터마이즈하지 않았으면 서버가 계산한 기본 정지 대상을 그대로 반영한다.
  function isKeeping(userId: number): boolean {
    if (customKeepUserIds !== null) {
      return customKeepUserIds.includes(userId);
    }
    return !suspendIds.has(userId);
  }

  function toggleKeep(userId: number) {
    const base = customKeepUserIds ?? roster.filter((member) => !suspendIds.has(member.id)).map((member) => member.id);
    const next = base.includes(userId)
      ? base.filter((id) => id !== userId)
      : [...base, userId];
    resetError();
    setCustomKeepUserIds(next);
  }

  async function handleConfirm() {
    if (!planName) {
      return;
    }
    try {
      await changePlan({
        planName,
        confirmOverflow: true,
        keepUserIds: customKeepUserIds ?? undefined,
      });
      onChanged();
    } catch {
      // 에러는 아래 error(mutation.error)로 표시한다 — 409(PLAN_DOWNGRADE_CONFIRMATION_REQUIRED)를
      // 포함해 에러 코드 기준으로 안내만 바꾸고(메시지 문자열 매칭 금지), 콘솔에 unhandled rejection이
      // 찍히지 않도록 여기서 흡수한다.
    }
  }

  const isStaleConfirmation = error?.code === 'PLAN_DOWNGRADE_CONFIRMATION_REQUIRED';

  return (
    <Modal
      open={open}
      onClose={onClose}
      title={`${PLAN_LABEL[planName]} 플랜으로 변경`}
      closeOnOverlayClick={!isPending}
    >
      <div className="flex w-[440px] max-w-full flex-col gap-5">
        {/* 유지 대상 체크박스는 preview 로딩 상태와 무관하게 항상 마운트해 둔다 — {preview && …}로
            통째로 감싸면 선택을 바꿀 때마다(재조회 도중) 목록이 통째로 사라졌다 다시 나타나
            깜빡이고, 이미 커스터마이즈한 체크 상태(customKeepUserIds)를 다루는 DOM 참조도 매번
            새로 마운트돼 뒤이은 상호작용(연속 토글)이 불안정해진다(리뷰에서 재현된 버그). */}
        <div className="flex flex-col gap-1">
          <p className="m-0 text-sm font-semibold text-heading">
            정지될 구성원 {preview ? `${preview.seatsToSuspend.length}명` : '계산 중...'}
          </p>
          <p className="m-0 text-xs text-text-muted">
            아래에서 유지할 구성원을 직접 선택할 수 있습니다. 선택하지 않은 구성원은 로그인할 수
            없게 정지됩니다(계정은 삭제되지 않으며, 좌석 여유가 생기면 다시 활성화할 수 있습니다).
          </p>
        </div>

        {previewIsError && (
          <p role="alert" className="m-0 text-sm text-danger">
            변경 영향을 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.
          </p>
        )}

        <div className="flex max-h-60 flex-col overflow-y-auto rounded-xl border border-border">
          {roster.length === 0 && (
            <p className="m-0 p-3 text-sm text-text-muted">표시할 구성원이 없습니다.</p>
          )}
          {roster.map((member) => (
            <label
              key={member.id}
              className="flex items-center gap-2.5 border-b border-border px-3 py-2 last:border-b-0"
            >
              <input
                type="checkbox"
                className="h-4 w-4 accent-heading"
                checked={isKeeping(member.id)}
                onChange={() => toggleKeep(member.id)}
              />
              <span className="flex flex-col text-sm">
                <span className="text-text-default">{member.name}</span>
                <span className="text-xs text-text-muted">{member.email}</span>
              </span>
            </label>
          ))}
        </div>

        {/* facilityOverflowCount는 "대상 요금제 기준 총량"이지 증분이 아니다(AdminPlanChangePreviewResponse
            javadoc) — "새로 N개가 읽기전용이 됩니다"로 쓰면 오인을 준다. */}
        {preview && (
          <p className="m-0 text-sm text-text-muted">
            대상 요금제에서 읽기 전용이 되는 시설물: {preview.facilityOverflowCount}개
            <br />
            (조회와 기존 점검 이력은 그대로 유지되고, 신규 점검 생성만 제한됩니다.)
          </p>
        )}

        {error && (
          <p role="alert" className="m-0 text-sm text-danger">
            {isStaleConfirmation
              ? '확인하는 사이 구성원이 늘어 다시 확인이 필요합니다. 위 내용을 다시 확인한 뒤 변경을 눌러주세요.'
              : getApiErrorMessage(error, '플랜 변경에 실패했습니다.')}
          </p>
        )}

        <div className="flex justify-end gap-3">
          <Button type="button" variant="secondary" onClick={onClose} disabled={isPending}>
            취소
          </Button>
          <Button
            type="button"
            variant="primary"
            onClick={handleConfirm}
            disabled={isPending || previewLoading || previewIsError}
          >
            {isPending ? '변경 중...' : '변경 확정'}
          </Button>
        </div>
      </div>
    </Modal>
  );
}
