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

// 활동 기록 타임라인(defect/ActivityHistoryPanel)이 같은 defect_revisions 시각을 이 형식으로
// 보여준다 — 같은 종류의 데이터가 화면마다 다르게 보이지 않도록 관례를 그대로 따른다(#1401).
// ⚠️ deletedAt은 백엔드 LocalDateTime이라 오프셋 없이 내려오고 new Date()가 브라우저 로컬로
// 해석한다. 서버 컨테이너 TZ=Asia/Seoul + JPA auditing이 JVM 기본 타임존을 쓰므로 KST 벽시계로
// 저장되며, 국내 사용(브라우저도 KST)에서는 일치한다. 해외 타임존 브라우저에서는 어긋나지만
// 활동 기록 타임라인도 동일한 패턴이라 이 화면만 고치면 오히려 갈린다 — 앱 전반 사안으로 분리.
function formatDeletedAt(value: string | null): string {
  if (!value) return '일시 미상';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '일시 미상';
  return date.toLocaleString('ko-KR');
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
