import { useEffect, useMemo, useState } from 'react';
import { useAuthStore } from '../../auth/store/authStore';
import { Button } from '../../../shared/components/Button';
import { Modal } from '../../../shared/components/Modal';
import { getApiErrorMessage } from '../../../shared/api/types';
import { useChangePlan } from '../hooks/useChangePlan';
import { usePlanChangePreview } from '../hooks/usePlanChangePreview';
import { usePlanQuotaUsers } from '../hooks/usePlanQuotaUsers';
import { useScheduleDowngrade } from '../hooks/useScheduleDowngrade';
import { formatScheduledDate, PLAN_LABEL } from '../planQuota.constants';
import type { AdminUserPlan } from '../types';

/** '즉시' = 기존 PATCH 경로. '예약' = 다음 결제일 적용(#1105 / HAJA-526, #1191, FREE 대상 전용). */
export type PlanChangeTiming = 'IMMEDIATE' | 'SCHEDULED';

// 유지 대상 선택 UI에서 한 번에 보여줄 회사 멤버 수 상한 — 서버 @Max(100)과 맞춘다. 회사 규모가
// 100명을 넘으면 이 모달에서 전원을 선택지로 보여주지 못한다(이 요금제 정책상 좌석 한도가 최대
// 수십 단위라 실용적으로는 충분하다고 판단 — 넘는 경우는 후속 이슈로 남길 수 있음, F-15 계열 후속).
const MEMBER_ROSTER_PAGE_SIZE = 100;

interface PlanDowngradeConfirmModalProps {
  open: boolean;
  /** 확인 대상 요금제 — open=false 이거나 아직 미선택이면 null. */
  planName: AdminUserPlan | null;
  /** 대상 요금제의 좌석 한도(catalog 기준) — null=무제한. 좌석 카운터·체크박스 disabled 판정에 쓴다. */
  maxSeats: number | null;
  /** '즉시'(기존 PATCH) | '예약'(다음 결제일, FREE 전용) — PlanChangeControl이 고른 값을 그대로 받는다. */
  timing: PlanChangeTiming;
  /** 예약 적용 예정일 표시용(= 신청 시점 currentPeriodEnd). timing='IMMEDIATE'면 쓰이지 않는다. */
  currentPeriodEnd: string | null;
  onClose: () => void;
  /** 변경(또는 예약)이 성공적으로 반영된 뒤 호출 — 부모가 선택 상태를 리셋한다. */
  onChanged: () => void;
}

// 플랜 하향 확인 모달(#890 Phase 1 확인 UX + Phase 2 keepUserIds) — /admin/plans-quota 플랜 변경 흐름.
// change-preview가 requiresConfirmation=true를 돌려줄 때만 연다. 정지될 구성원 이름·이메일과
// 읽기전용 시설물 "총량"을 보여주고, 관리자가 유지할 구성원을 직접 선택할 수 있게 한다 — 기본값은
// 서버가 계산한 id 오름차순 자동 선정이며, 체크박스를 건드리기 전까지는 그 기본값을 그대로 보여준다.
export function PlanDowngradeConfirmModal({
  open,
  planName,
  maxSeats,
  timing,
  currentPeriodEnd,
  onClose,
  onChanged,
}: PlanDowngradeConfirmModalProps) {
  // changePlan은 회사 owner 전용 API라(AdminPlanService#requireCompanyOwner), 이 모달을 열 수 있는
  // 로그인 사용자 = 항상 owner다 — 별도 조회 없이 auth store의 로그인 사용자 id를 그대로 owner로
  // 쓴다(#930 재검토 F-2).
  const ownerUserId = useAuthStore((state) => state.user?.id) ?? null;

  // null = 관리자가 아직 유지 대상을 직접 고르지 않음(서버 기본값 사용). 커스터마이즈하면 구체적인
  // id 배열이 된다. owner는 항상 이 배열에 포함된다(toggleKeep이 강제) — 빈 배열은 나오지 않는다.
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
    isFetching: previewIsFetching,
    isError: previewIsError,
  } = usePlanChangePreview(planName, previewKeepUserIds, open);

  // 유지 대상 선택 UI용 회사 멤버 전체 목록 — plan-quota 목록과 같은 데이터 소스를 재사용한다.
  // 모달이 닫혀 있으면 조회하지 않는다(#930 재검토 F-11).
  const { data: rosterData } = usePlanQuotaUsers({ page: 1, size: MEMBER_ROSTER_PAGE_SIZE }, open);
  const roster = rosterData?.content ?? [];

  const suspendIds = useMemo(
    () => new Set((preview?.seatsToSuspend ?? []).map((target) => target.userId)),
    [preview],
  );

  const { changePlan, isPending: isChangePending, error: changeError, resetError: resetChangeError } =
    useChangePlan();
  const {
    scheduleDowngrade,
    isPending: isSchedulePending,
    error: scheduleError,
    resetError: resetScheduleError,
  } = useScheduleDowngrade();

  const isScheduled = timing === 'SCHEDULED';
  const isPending = isScheduled ? isSchedulePending : isChangePending;
  const error = isScheduled ? scheduleError : changeError;
  const resetError = isScheduled ? resetScheduleError : resetChangeError;
  // SCHEDULED인데 currentPeriodEnd가 없으면(정상 경로에서는 PlanChangeControl이 '예약' 선택지 자체를
  // 막아 도달하지 않는다) 확정을 막는다 — 백엔드도 동일 상황을 PLAN_SCHEDULE_PERIOD_END_MISSING(409)
  // 으로 거절하므로, 방어적으로 요청 자체를 보내지 않는다.
  const periodEndMissingForSchedule = isScheduled && !currentPeriodEnd;
  const effectiveDateLabel = isScheduled ? formatScheduledDate(currentPeriodEnd) : null;

  if (!open || !planName) {
    return null;
  }

  // 체크박스 표시 상태 — owner는 항상 유지(체크 해제 불가). 아직 커스터마이즈하지 않았으면 서버가
  // 계산한 기본 정지 대상을 그대로 반영한다.
  function isKeeping(userId: number): boolean {
    if (ownerUserId !== null && userId === ownerUserId) {
      return true;
    }
    if (customKeepUserIds !== null) {
      return customKeepUserIds.includes(userId);
    }
    return !suspendIds.has(userId);
  }

  function toggleKeep(userId: number) {
    if (ownerUserId !== null && userId === ownerUserId) {
      // owner 행은 애초에 disabled 처리되지만, 방어적으로 한 번 더 막는다.
      return;
    }
    const base = customKeepUserIds ?? roster.filter((member) => isKeeping(member.id)).map((member) => member.id);
    // owner id를 base에 항상 강제 포함한다 — 이게 F-1("정확히 N명 골랐는데 초과") 의 근본 원인이었다:
    // toggleKeep이 현재 선택에 owner를 안 넣은 채로 시작하면, applyOverflow는 owner를 추가로 끼워
    // 넣어 항상 +1이 되어 좌석 한도를 넘겨버린다.
    const baseWithOwner =
      ownerUserId !== null && !base.includes(ownerUserId) ? [...base, ownerUserId] : base;
    const next = baseWithOwner.includes(userId)
      ? baseWithOwner.filter((id) => id !== userId)
      : [...baseWithOwner, userId];
    resetError();
    // []([]) 의미를 백엔드에 맞춰 통일한다(#930 재검토 F-2 보안 P2): adminPlanApi.ts는 keepUserIds가
    // 비어 있으면 아예 전송하지 않고, 백엔드도 빈 배열을 "미지정"으로 취급한다 — 즉 이 코드베이스
    // 어디에도 "빈 배열 = 전원 정지 선택"은 구현돼 있지 않다. next가 비면(이론상 owner조차 없는
    // 극단적 상황 방어용 — owner가 항상 base에 강제 포함되므로 실질적으로는 발생하지 않는다) 서버
    // 기본값(null)으로 되돌린다.
    setCustomKeepUserIds(next.length === 0 ? null : next);
  }

  // 좌석 카운터·한도 판정 — 오늘 이 화면의 주 사용 경로(자동 선정이 이미 maxSeats를 꽉 채운 상태에서
  // 관리자가 한 명을 더 체크하는 경우)가 항상 403 데드엔드로 끝나던 문제(#930 재검토 F-1)를, 한도
  // 도달 시 미체크 체크박스 자체를 disabled로 막아 원천 차단한다.
  // roster(plan-quota 목록)에는 아직 활성화되지 않은 초대 대기 멤버(plan=null, 좌석을 점유하지
  // 않음)도 함께 내려온다 — 좌석 계산은 백엔드와 동일하게 활성(plan!==null) 멤버만 대상으로 해야
  // 카운터·한도가 실제 좌석 수와 어긋나지 않는다.
  const activeRoster = roster.filter((member) => member.plan !== null);
  const keepCount = activeRoster.filter((member) => isKeeping(member.id)).length;
  const atSeatLimit = maxSeats !== null && keepCount >= maxSeats;
  // preview가 아직 한 번도 로드되지 않은 최초 로딩 구간에서는 suspendIds를 신뢰할 수 없어(전원
  // isKeeping=true로 오표시) 체크박스를 잠가 그 창에 발생하는 잘못된 toggle을 막는다(F-3).
  const rosterInteractionDisabled = preview === undefined;

  async function handleConfirm() {
    if (!planName || periodEndMissingForSchedule) {
      return;
    }
    try {
      const payload = {
        planName,
        confirmOverflow: true,
        keepUserIds: customKeepUserIds ?? undefined,
      };
      if (isScheduled) {
        await scheduleDowngrade(payload);
      } else {
        await changePlan(payload);
      }
      onChanged();
    } catch {
      // 에러는 아래 error(mutation.error)로 표시한다 — 에러 코드 기준으로 안내만 바꾸고(메시지
      // 문자열 매칭 금지), 콘솔에 unhandled rejection이 찍히지 않도록 여기서 흡수한다.
    }
  }

  const isStaleConfirmation = error?.code === 'PLAN_DOWNGRADE_CONFIRMATION_REQUIRED';
  const isSeatQuotaExceeded = error?.code === 'PLAN_SEAT_QUOTA_EXCEEDED';
  const isProtectedAdmin = error?.code === 'ADMIN_PROTECTED_ACCOUNT';
  const isKeepUserInvalid = error?.code === 'PLAN_KEEP_USER_INVALID';
  // 예약(POST /admin/plan/scheduled-change) 전용 에러 코드(#1105 / HAJA-526, #1191).
  const isScheduledChangeExists = error?.code === 'PLAN_SCHEDULED_CHANGE_EXISTS';
  const isPeriodEndMissing = error?.code === 'PLAN_SCHEDULE_PERIOD_END_MISSING';
  const isPaidTargetUnsupported = error?.code === 'PLAN_SCHEDULE_PAID_TARGET_UNSUPPORTED';
  const isNotDowngrade = error?.code === 'PLAN_SCHEDULE_NOT_DOWNGRADE';

  function errorMessage(): string {
    if (isStaleConfirmation) {
      return '확인하는 사이 구성원이 늘어 다시 확인이 필요합니다. 위 내용을 다시 확인한 뒤 변경을 눌러주세요.';
    }
    if (isSeatQuotaExceeded) {
      // 이 문맥(하향 시 유지 인원 선택)에서 "요금제를 업그레이드해 주세요"는 오안내다 — 관리자가
      // 이미 하향을 시도하는 중이라 업그레이드를 안내하면 목적과 반대되는 요구가 된다(F-1).
      return '선택한 유지 인원이 좌석 한도를 초과합니다. 유지할 구성원 수를 줄여주세요.';
    }
    if (isProtectedAdmin) {
      return '선택한 구성원만 유지하면 활성 관리자가 한 명도 남지 않습니다. 관리자 권한을 가진 구성원을 한 명 이상 유지 대상에 포함해 주세요.';
    }
    if (isKeepUserInvalid) {
      return '유지 대상 선택에 문제가 있습니다. 목록을 다시 확인한 뒤 선택해 주세요.';
    }
    if (isScheduledChangeExists) {
      return '이미 예약된 변경이 있습니다.';
    }
    if (isPeriodEndMissing) {
      return '현재 요금제는 결제 주기가 없어 예약할 수 없습니다.';
    }
    if (isPaidTargetUnsupported) {
      return '유료 요금제 간 예약 변경은 현재 지원하지 않습니다.';
    }
    if (isNotDowngrade) {
      return '상향은 예약할 수 없습니다.';
    }
    return getApiErrorMessage(error, isScheduled ? '플랜 예약에 실패했습니다.' : '플랜 변경에 실패했습니다.');
  }

  return (
    <Modal
      open={open}
      onClose={onClose}
      title={
        isScheduled ? `${PLAN_LABEL[planName]} 플랜으로 변경 예약` : `${PLAN_LABEL[planName]} 플랜으로 변경`
      }
      closeOnOverlayClick={!isPending}
    >
      <div className="flex w-[440px] max-w-full flex-col gap-5">
        {/* 예약(SCHEDULED) 전용 고지 — 적용 예정일 + FREE 1석 blast radius(#1191). 이 확인 없이 예약이
            진행되면 사람이 잘리는 시점이 신청 몇 주 뒤로 미뤄져 안내가 특히 중요하다(핸드오프 §3). */}
        {isScheduled && (
          <div className="flex flex-col gap-1.5 rounded-2xl border border-[#f97316]/40 bg-[#fff7ed] p-3 text-xs text-[#9a3412]">
            <p className="m-0 font-semibold">
              {effectiveDateLabel ? `${effectiveDateLabel}부터 적용됩니다.` : '다음 결제일부터 적용됩니다.'}{' '}
              그때까지 현재 요금제를 그대로 사용합니다.
            </p>
            <p className="m-0">
              ⚠️ FREE 요금제는 좌석이 1개입니다. 적용 시점에 회사 소유자를 제외한 전원이 정지되고
              즉시 로그아웃됩니다.
            </p>
            {periodEndMissingForSchedule && (
              <p className="m-0 font-semibold text-danger">
                현재 요금제는 결제 주기가 없어 예약할 수 없습니다.
              </p>
            )}
          </div>
        )}

        {/* 유지 대상 체크박스는 preview 로딩 상태와 무관하게 항상 마운트해 둔다 — {preview && …}로
            통째로 감싸면 선택을 바꿀 때마다(재조회 도중) 목록이 통째로 사라졌다 다시 나타나
            깜빡이고, 이미 커스터마이즈한 체크 상태(customKeepUserIds)를 다루는 DOM 참조도 매번
            새로 마운트돼 뒤이은 상호작용(연속 토글)이 불안정해진다(리뷰에서 재현된 버그). */}
        <div className="flex flex-col gap-1">
          <p className="m-0 text-sm font-semibold text-heading">
            {isScheduled ? '적용 시점에 정지될 구성원 ' : '정지될 구성원 '}
            {preview ? `${preview.seatsToSuspend.length}명` : '계산 중...'}
          </p>
          {maxSeats !== null && (
            <p className="m-0 text-xs text-text-muted">
              현재 유지 선택 {keepCount} / 최대 {maxSeats}명 (회사 소유자 1석 포함)
            </p>
          )}
          <p className="m-0 text-xs text-text-muted">
            {isScheduled ? (
              <>
                아래에서 유지할 구성원을 직접 선택할 수 있습니다. 선택하지 않은 구성원은 적용
                시점에 로그인할 수 없게 정지됩니다(예약을 취소하면 아무 일도 일어나지 않습니다).
              </>
            ) : (
              <>
                아래에서 유지할 구성원을 직접 선택할 수 있습니다. 선택하지 않은 구성원은 로그인할 수
                없게 정지됩니다(계정은 삭제되지 않으며, 좌석 여유가 생기면 다시 활성화할 수 있습니다).
              </>
            )}
          </p>
        </div>

        {previewIsError && (
          <p role="alert" className="m-0 text-sm text-danger">
            변경 영향을 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.
          </p>
        )}

        {/* F-13: 시설물만 초과(정지 대상 0명)면 유지 대상 선택 UI 자체가 무의미하다 — 감추고 시설물
            안내만 보여준다. preview가 아직 없는 최초 로딩 구간에는(어느 쪽인지 모르므로) 우선
            보여준다 — 이후 데이터가 도착하면 필요 시 한 번만 감춰진다(토글마다 깜빡이지 않음, 위
            placeholderData:keepPreviousData 덕에 preview는 재조회 중에도 이전 값을 유지한다). */}
        {(preview === undefined || preview.seatsToSuspend.length > 0) && (
          <div className="flex max-h-60 flex-col overflow-y-auto rounded-xl border border-border">
            {roster.length === 0 && (
              <p className="m-0 p-3 text-sm text-text-muted">표시할 구성원이 없습니다.</p>
            )}
            {roster.map((member) => {
              const isOwner = ownerUserId !== null && member.id === ownerUserId;
              const checked = isKeeping(member.id);
              const disabled =
                isOwner || rosterInteractionDisabled || (atSeatLimit && !checked);
              return (
                <label
                  key={member.id}
                  className="flex items-center gap-2.5 border-b border-border px-3 py-2 last:border-b-0"
                >
                  <input
                    type="checkbox"
                    className="h-4 w-4 accent-heading"
                    checked={checked}
                    disabled={disabled}
                    onChange={() => toggleKeep(member.id)}
                  />
                  <span className="flex flex-col text-sm">
                    <span className="text-text-default">{member.name}</span>
                    {isOwner ? (
                      <span className="text-xs text-text-muted">회사 소유자는 항상 유지됩니다</span>
                    ) : (
                      <span className="text-xs text-text-muted">{member.email}</span>
                    )}
                  </span>
                </label>
              );
            })}
          </div>
        )}

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
            {errorMessage()}
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
            disabled={isPending || previewIsFetching || previewIsError || periodEndMissingForSchedule}
          >
            {isPending ? (isScheduled ? '예약 중...' : '변경 중...') : isScheduled ? '예약 확정' : '변경 확정'}
          </Button>
        </div>
      </div>
    </Modal>
  );
}
