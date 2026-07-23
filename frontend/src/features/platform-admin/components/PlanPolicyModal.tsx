import type { ReactNode } from 'react';
import { useEffect, useState } from 'react';
import { Button } from '../../../shared/components/Button';
import { Modal } from '../../../shared/components/Modal';
import { PLAN_LABEL } from '../constants';
import {
  PLAN_POLICY_COLUMN_ORDER,
  PLAN_POLICY_COLUMN_SUBLABEL,
  PLAN_POLICY_TEXT_ROWS,
  PLAN_POLICY_TOGGLE_ROWS,
} from '../planPolicy.constants';
import type { PlanPolicyForm, PlanPolicyValues } from '../planPolicy.types';
import type { AdminUserPlan } from '../types';

interface PlanPolicyModalProps {
  open: boolean;
  onClose: () => void;
  initialValues: PlanPolicyForm;
  onSave: (values: PlanPolicyForm) => void;
}

const GRID_TEMPLATE = 'grid grid-cols-[minmax(0,220px)_repeat(3,minmax(0,1fr))] items-center gap-4';

// 입력칸은 3개 플랜 컬럼 전부 동일하게 흰 배경·편집 가능 스타일을 쓴다(시안의 FREE 컬럼만 회색으로
// 보이던 것은 실수 재현 대상이 아니라 통일 지시 대상 — 사용자 확정 사항).
const TEXT_INPUT_CLASS =
  'w-full rounded-lg border border-border bg-surface px-3 py-2.5 text-center text-sm text-text-default placeholder:text-text-muted focus:outline-none focus-visible:ring-1 focus-visible:ring-primary';

function RowIconBadge({ children }: { children: ReactNode }) {
  return (
    <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-lg bg-surface-muted text-text-muted">
      {children}
    </span>
  );
}

// ROW 01~06 장식 아이콘 — 이 모달 전용이라 별도 파일로 빼지 않고 지역 컴포넌트로 둔다.
function PriceIcon() {
  return (
    <svg width="16" height="16" viewBox="0 0 16 16" fill="none" aria-hidden>
      <rect x="1.5" y="3.5" width="13" height="9" rx="1.5" stroke="currentColor" strokeWidth="1.3" />
      <circle cx="8" cy="8" r="2" stroke="currentColor" strokeWidth="1.3" />
    </svg>
  );
}
function FacilityIcon() {
  return (
    <svg width="16" height="16" viewBox="0 0 16 16" fill="none" aria-hidden>
      <path d="M2 14V4l6-2 6 2v10" stroke="currentColor" strokeWidth="1.3" strokeLinejoin="round" />
      <path d="M2 14h12M6 14v-3h4v3" stroke="currentColor" strokeWidth="1.3" strokeLinejoin="round" />
    </svg>
  );
}
function AnalysisIcon() {
  return (
    <svg width="16" height="16" viewBox="0 0 16 16" fill="none" aria-hidden>
      <path
        d="M8 1.5a6.5 6.5 0 1 1-4.6 1.9"
        stroke="currentColor"
        strokeWidth="1.3"
        strokeLinecap="round"
      />
      <path d="M8 1.5v6l4 2" stroke="currentColor" strokeWidth="1.3" strokeLinecap="round" />
    </svg>
  );
}
function SeatIcon() {
  return (
    <svg width="16" height="16" viewBox="0 0 16 16" fill="none" aria-hidden>
      <circle cx="6.2" cy="5.5" r="2.2" stroke="currentColor" strokeWidth="1.3" />
      <path d="M1.5 14c0-2.5 2-4 4.7-4s4.7 1.5 4.7 4" stroke="currentColor" strokeWidth="1.3" />
      <path d="M11 2.3c1.2.4 2 1.5 2 2.9s-.8 2.5-2 2.9M13.5 14c0-2.1-1.4-3.5-3.3-3.9" stroke="currentColor" strokeWidth="1.3" strokeLinecap="round" />
    </svg>
  );
}
function WatermarkIcon() {
  return (
    <svg width="16" height="16" viewBox="0 0 16 16" fill="none" aria-hidden>
      <rect x="2.5" y="1.5" width="11" height="13" rx="1.3" stroke="currentColor" strokeWidth="1.3" />
      <path d="M5 5h6M5 8h6M5 11h3.5" stroke="currentColor" strokeWidth="1.3" strokeLinecap="round" />
    </svg>
  );
}
function CounselorIcon() {
  return (
    <svg width="16" height="16" viewBox="0 0 16 16" fill="none" aria-hidden>
      <path
        d="M2 4.5A2.5 2.5 0 0 1 4.5 2h7A2.5 2.5 0 0 1 14 4.5V9a2.5 2.5 0 0 1-2.5 2.5H7l-3 2.5v-2.5H4.5A2.5 2.5 0 0 1 2 9V4.5Z"
        stroke="currentColor"
        strokeWidth="1.3"
        strokeLinejoin="round"
      />
    </svg>
  );
}

const TEXT_ROW_ICON: Record<string, () => ReactNode> = {
  priceMonthly: PriceIcon,
  maxFacilities: FacilityIcon,
  maxMonthlyAnalyses: AnalysisIcon,
  maxSeats: SeatIcon,
};

const TOGGLE_ROW_ICON: Record<string, () => ReactNode> = {
  hasPdfWatermark: WatermarkIcon,
  hasCounselorAccess: CounselorIcon,
};

function PolicyToggle({
  checked,
  label,
  onChange,
}: {
  checked: boolean;
  label: string;
  onChange: (checked: boolean) => void;
}) {
  return (
    <div className="flex justify-center">
      <button
        type="button"
        role="switch"
        aria-checked={checked}
        aria-label={label}
        onClick={() => onChange(!checked)}
        className={`relative inline-flex h-6 w-11 items-center rounded-full transition-colors ${
          checked ? 'bg-heading' : 'bg-[#b8b6bd]'
        }`}
      >
        <span
          className={`inline-block h-4 w-4 transform rounded-full bg-surface transition-transform ${
            checked ? 'translate-x-6' : 'translate-x-1'
          }`}
        />
      </button>
    </div>
  );
}

// "플랜 정책 설정" 모달 — 첨부 시안(2026-07-23) 그대로. FREE/STANDARD/ENTERPRISE 3개 플랜의 가격·한도·
// 기능 제공 여부를 한 표에서 편집한다. 저장 API가 아직 없어(#625 시점 보류 사항) 열려 있는 동안만
// 값을 들고 있다가 "설정 저장" 클릭 시 상위(onSave)로 커밋하고, "취소"·오버레이 클릭·ESC는 드래프트를
// 버리고 initialValues로 되돌린다.
export function PlanPolicyModal({ open, onClose, initialValues, onSave }: PlanPolicyModalProps) {
  const [draft, setDraft] = useState<PlanPolicyForm>(initialValues);

  useEffect(() => {
    if (open) {
      setDraft(initialValues);
    }
  }, [open, initialValues]);

  function updateField<K extends keyof PlanPolicyValues>(plan: AdminUserPlan, key: K, value: PlanPolicyValues[K]) {
    setDraft((prev) => ({
      ...prev,
      [plan]: { ...prev[plan], [key]: value } as PlanPolicyValues,
    }));
  }

  function handleSave() {
    onSave(draft);
    onClose();
  }

  return (
    <Modal
      open={open}
      onClose={onClose}
      closeOnOverlayClick={false}
      title={
        <>
          {/* Modal이 title을 <h2>로 감싸므로 여기서는 block 요소(p) 대신 span만 쓴다(HTML 콘텐츠 모델상
              heading은 phrasing content만 허용) */}
          <span className="block text-xs font-semibold tracking-wide text-text-muted uppercase">
            System configuration
          </span>
          <span className="mt-1 block text-xl font-bold text-heading">
            플랜 정책 설정 (Plan Policy Settings)
          </span>
        </>
      }
    >
      <div className="flex w-full max-w-4xl flex-col gap-6">
        {/* 컬럼 헤더 — FREE/STANDARD/ENTERPRISE */}
        <div className={GRID_TEMPLATE}>
          <div />
          {PLAN_POLICY_COLUMN_ORDER.map((plan) => (
            <div key={plan} className="text-center">
              <p className="m-0 text-xs font-bold tracking-wide text-heading uppercase">
                {PLAN_LABEL[plan]}
              </p>
              <p className="m-0 text-xs text-text-muted">{PLAN_POLICY_COLUMN_SUBLABEL[plan]}</p>
            </div>
          ))}
        </div>

        <div className="flex flex-col gap-5">
          {PLAN_POLICY_TEXT_ROWS.map((row) => {
            const Icon = TEXT_ROW_ICON[row.key];
            return (
              <div key={row.key} className={GRID_TEMPLATE}>
                <div className="flex items-center gap-3">
                  <RowIconBadge>
                    <Icon />
                  </RowIconBadge>
                  <div>
                    <p className="m-0 text-[11px] font-semibold tracking-wide text-text-muted uppercase">
                      Row {row.no}
                    </p>
                    <p className="m-0 text-sm font-semibold text-heading">{row.label}</p>
                  </div>
                </div>
                {PLAN_POLICY_COLUMN_ORDER.map((plan) => (
                  <div key={plan} className="relative">
                    <input
                      type="text"
                      inputMode="numeric"
                      className={row.unit ? `${TEXT_INPUT_CLASS} pr-10` : TEXT_INPUT_CLASS}
                      value={draft[plan][row.key]}
                      placeholder={row.emptyHint}
                      onChange={(event) => updateField(plan, row.key, event.target.value)}
                      aria-label={`${PLAN_LABEL[plan]} ${row.label}`}
                    />
                    {row.unit && (
                      <span className="pointer-events-none absolute top-1/2 right-3 -translate-y-1/2 text-xs text-text-muted">
                        {row.unit}
                      </span>
                    )}
                  </div>
                ))}
              </div>
            );
          })}

          {PLAN_POLICY_TOGGLE_ROWS.map((row) => {
            const Icon = TOGGLE_ROW_ICON[row.key];
            return (
              <div key={row.key} className={GRID_TEMPLATE}>
                <div className="flex items-center gap-3">
                  <RowIconBadge>
                    <Icon />
                  </RowIconBadge>
                  <div>
                    <p className="m-0 text-[11px] font-semibold tracking-wide text-text-muted uppercase">
                      Row {row.no}
                    </p>
                    <p className="m-0 text-sm font-semibold text-heading">{row.label}</p>
                  </div>
                </div>
                {PLAN_POLICY_COLUMN_ORDER.map((plan) => (
                  <PolicyToggle
                    key={plan}
                    checked={draft[plan][row.key]}
                    label={`${PLAN_LABEL[plan]} ${row.label}`}
                    onChange={(checked) => updateField(plan, row.key, checked)}
                  />
                ))}
              </div>
            );
          })}
        </div>

        <div className="-mx-6 -mb-6 flex flex-wrap items-center justify-between gap-4 border-t border-border bg-surface-muted px-6 py-5">
          <p className="m-0 max-w-md text-xs text-text-muted">
            변경 사항은 실시간으로 사용자 플랜에 반영됩니다.
            <br />
            Enterprise 플랜의 경우 개별 계약 사항과 충돌할 수 있으니 주의하십시오.
          </p>
          <div className="flex gap-3">
            <Button type="button" variant="secondary" onClick={onClose}>
              취소
            </Button>
            <Button type="button" variant="primary" onClick={handleSave}>
              설정 저장
            </Button>
          </div>
        </div>
      </div>
    </Modal>
  );
}
