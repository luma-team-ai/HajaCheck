import { useState } from 'react';
import { Button } from '../../../shared/components/Button';
import { DEFECT_TYPE_CODE_LABELS } from '../api/inspectionApi.types';
import type { DeletedDefectItem } from '../api/inspectionApi.types';

interface DeletedDefectsPanelProps {
  /** 이 이미지에서 오탐 삭제된 하자(호출부가 현재 media로 이미 걸러서 넘긴다) */
  items: DeletedDefectItem[];
  onRestore: (defectId: number) => void;
  /** 되살리기 진행 중인 하자 id — 중복 클릭 방지 */
  restoringId?: number;
  disabled?: boolean;
}

function formatDeletedAt(value: string | null): string {
  if (!value) return '일시 미상';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '일시 미상';
  // 로케일 문자열은 환경마다 흔들려 테스트가 불안정해진다 — 고정 포맷으로 직접 만든다.
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

/**
 * 오탐 삭제된 하자 목록 + 되살리기(#1399).
 *
 * <p>삭제 사유는 삭제 시점에 1~500자 필수로 받아 `defect_revisions`에 저장돼 있었지만, 모든 조회가
 * `is_deleted=false` 필터라 **어느 화면에서도 읽을 수 없었다**. PRD FR-4가 요구하는 "감사용
 * defect_revisions 화면 노출"에서 오탐 삭제분만 빠져 있던 것을 메운다.
 *
 * <p>기본은 접힌 상태다 — 검수 흐름의 주가 아니라 "실수했을 때 되돌아오는 자리"라서, 펼치기 전에는
 * 건수만 알리고 화면을 차지하지 않는다.
 */
export function DeletedDefectsPanel({
  items,
  onRestore,
  restoringId,
  disabled = false,
}: DeletedDefectsPanelProps) {
  const [isOpen, setIsOpen] = useState(false);

  if (items.length === 0) return null;

  return (
    <div className="rounded-lg border border-border">
      <button
        type="button"
        onClick={() => setIsOpen((prev) => !prev)}
        aria-expanded={isOpen}
        className="flex w-full items-center justify-between px-4 py-2 text-sm text-text-muted hover:bg-surface-muted"
      >
        <span>🗑 이 이미지에서 삭제된 하자 {items.length}건</span>
        <span aria-hidden="true">{isOpen ? '▲' : '▼'}</span>
      </button>

      {isOpen && (
        <ul className="flex flex-col gap-2 border-t border-border p-3">
          {items.map(({ defect, deletedReason, deletedAt, deletedByName }) => (
            <li
              key={defect.id}
              className="flex items-start justify-between gap-3 rounded-lg bg-surface-muted p-3"
            >
              <div className="min-w-0 text-sm">
                <div className="font-medium text-text-default">
                  {DEFECT_TYPE_CODE_LABELS[defect.type]}{' '}
                  {defect.grade == null ? '등급 미판정' : `${defect.grade}등급`}
                </div>
                {/* 사유는 자유 입력(최대 500자)이라 줄바꿈·긴 문장이 들어온다 — 잘라내지 않고 감싼다. */}
                <div className="mt-1 whitespace-pre-wrap break-words text-text-default">
                  사유: {deletedReason ?? '사유 없음'}
                </div>
                <div className="mt-1 text-xs text-text-muted">
                  {formatDeletedAt(deletedAt)} · {deletedByName ?? '삭제자 미상'}
                </div>
              </div>
              <Button
                type="button"
                variant="secondary"
                size="sm"
                onClick={() => onRestore(defect.id)}
                disabled={disabled || restoringId === defect.id}
              >
                되살리기
              </Button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
