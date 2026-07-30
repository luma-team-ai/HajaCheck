import type { ParticipantEntry, ParticipantsSectionData } from '../../types';

interface ParticipantsSectionFormProps {
  data: ParticipantsSectionData;
  onChange: (next: ParticipantsSectionData) => void;
  readOnly: boolean;
}

const EMPTY_ENTRY: ParticipantEntry = { role: '', name: '', qualification: '', period: '' };

const INPUT_CLASS =
  'w-full rounded-lg border border-border bg-surface px-3 py-2 text-sm leading-6 text-text-default outline-none transition placeholder:text-text-muted focus:border-primary focus:ring-2 focus:ring-primary/10 read-only:cursor-default read-only:bg-surface-muted read-only:text-text-muted';

// 표준서식의 "참여 기술진 명단" — 사람 이름·자격은 도메인 밖 값이라 백엔드가 생산할 수 없다.
// 행 추가/삭제가 가능한 표 형태로 편집기에 둔다(RecommendationSection의 카드-리스트 패턴과 동일한
// "add/remove by index" 갱신 방식).
export function ParticipantsSectionForm({ data, onChange, readOnly }: ParticipantsSectionFormProps) {
  const entries = data.entries;

  const updateEntry = (index: number, patch: Partial<ParticipantEntry>) => {
    const next = entries.map((entry, entryIndex) => (entryIndex === index ? { ...entry, ...patch } : entry));
    onChange({ entries: next });
  };

  const addEntry = () => onChange({ entries: [...entries, { ...EMPTY_ENTRY }] });
  const removeEntry = (index: number) => onChange({ entries: entries.filter((_, entryIndex) => entryIndex !== index) });

  return (
    <section className="flex flex-col gap-4">
      <div className="flex justify-end">
        {!readOnly && (
          <button
            type="button"
            onClick={addEntry}
            className="rounded-full border border-border px-3 py-1.5 text-xs font-medium text-text-default transition hover:bg-surface-muted"
          >
            + 인원 추가
          </button>
        )}
      </div>

      {entries.length === 0 ? (
        <div className="rounded-lg border border-border bg-surface-muted p-8 text-center text-sm text-text-muted">
          참여기술진 정보가 없습니다.
        </div>
      ) : (
        <div className="flex flex-col gap-3">
          {entries.map((entry, index) => (
            <div
              key={index}
              className="grid grid-cols-1 gap-3 rounded-lg border border-border p-4 sm:grid-cols-[repeat(4,1fr)_auto] sm:items-start"
            >
              <label className="flex flex-col gap-1.5">
                <span className="text-xs font-medium tracking-wide text-text-muted">구분</span>
                <input
                  className={INPUT_CLASS}
                  value={entry.role}
                  readOnly={readOnly}
                  placeholder="예: 사업책임기술인"
                  onChange={(event) => updateEntry(index, { role: event.target.value })}
                />
              </label>
              <label className="flex flex-col gap-1.5">
                <span className="text-xs font-medium tracking-wide text-text-muted">성명</span>
                <input
                  className={INPUT_CLASS}
                  value={entry.name}
                  readOnly={readOnly}
                  onChange={(event) => updateEntry(index, { name: event.target.value })}
                />
              </label>
              <label className="flex flex-col gap-1.5">
                <span className="text-xs font-medium tracking-wide text-text-muted">자격 및 주요경력</span>
                <input
                  className={INPUT_CLASS}
                  value={entry.qualification}
                  readOnly={readOnly}
                  onChange={(event) => updateEntry(index, { qualification: event.target.value })}
                />
              </label>
              <label className="flex flex-col gap-1.5">
                <span className="text-xs font-medium tracking-wide text-text-muted">과업 참여기간</span>
                <input
                  className={INPUT_CLASS}
                  value={entry.period}
                  readOnly={readOnly}
                  placeholder="예: 2025.04.22.~2026.05.13."
                  onChange={(event) => updateEntry(index, { period: event.target.value })}
                />
              </label>
              {!readOnly && (
                <button
                  type="button"
                  aria-label={`참여기술진 ${index + 1}번 삭제`}
                  onClick={() => removeEntry(index)}
                  className="self-center rounded-full border border-border px-3 py-1.5 text-xs text-text-muted transition hover:border-red-200 hover:text-red-600 sm:mt-5"
                >
                  삭제
                </button>
              )}
            </div>
          ))}
        </div>
      )}
    </section>
  );
}
