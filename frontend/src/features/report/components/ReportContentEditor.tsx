import { Button } from '../../../shared/components/Button';
import type {
  DefectDetailItem,
  RecommendationItem,
  ReportContent,
} from '../types';

interface ReportContentEditorProps {
  content: ReportContent;
  onChange: (next: ReportContent) => void;
  readOnly: boolean;
}

const FIELD_CLASSES =
  'w-full rounded-lg border border-border bg-surface p-2 text-sm text-text-default disabled:cursor-not-allowed disabled:bg-surface-muted disabled:text-text-muted';

function Field({
  label,
  value,
  onChange,
  readOnly,
  multiline = false,
}: {
  label: string;
  value: string;
  onChange: (v: string) => void;
  readOnly: boolean;
  multiline?: boolean;
}) {
  return (
    <label className="flex flex-col gap-1">
      <span className="text-xs font-medium text-text-muted">{label}</span>
      {multiline ? (
        <textarea
          className={FIELD_CLASSES}
          rows={3}
          value={value}
          disabled={readOnly}
          onChange={(e) => onChange(e.target.value)}
        />
      ) : (
        <input
          className={FIELD_CLASSES}
          value={value}
          disabled={readOnly}
          onChange={(e) => onChange(e.target.value)}
        />
      )}
    </label>
  );
}

function StringListEditor({
  label,
  items,
  onChange,
  readOnly,
}: {
  label: string;
  items: string[];
  onChange: (next: string[]) => void;
  readOnly: boolean;
}) {
  return (
    <div className="flex flex-col gap-2">
      <span className="text-xs font-medium text-text-muted">{label}</span>
      {items.map((item, i) => (
        <div key={i} className="flex items-center gap-2">
          <input
            className={FIELD_CLASSES}
            value={item}
            disabled={readOnly}
            onChange={(e) => {
              const next = [...items];
              next[i] = e.target.value;
              onChange(next);
            }}
          />
          {!readOnly && (
            <Button
              variant="secondary"
              size="sm"
              onClick={() => onChange(items.filter((_, idx) => idx !== i))}
            >
              삭제
            </Button>
          )}
        </div>
      ))}
      {!readOnly && (
        <Button variant="secondary" size="sm" onClick={() => onChange([...items, ''])}>
          + 항목 추가
        </Button>
      )}
    </div>
  );
}

export function ReportContentEditor({ content, onChange, readOnly }: ReportContentEditorProps) {
  const updateOverview = (patch: Partial<ReportContent['overview']>) =>
    onChange({ ...content, overview: { ...content.overview, ...patch } });

  const updateSummary = (patch: Partial<ReportContent['summary']>) =>
    onChange({ ...content, summary: { ...content.summary, ...patch } });

  const updateDetailItem = (index: number, patch: Partial<DefectDetailItem>) => {
    const items = content.detail.items.map((item, i) => (i === index ? { ...item, ...patch } : item));
    onChange({ ...content, detail: { items } });
  };

  const removeDetailItem = (index: number) => {
    onChange({ ...content, detail: { items: content.detail.items.filter((_, i) => i !== index) } });
  };

  const addDetailItem = () => {
    const blank: DefectDetailItem = {
      defect_type: '',
      location: '',
      severity_grade: '',
      description: '',
      cause: '',
    };
    onChange({ ...content, detail: { items: [...content.detail.items, blank] } });
  };

  const updateRecommendationItem = (index: number, patch: Partial<RecommendationItem>) => {
    const items = content.recommendation.items.map((item, i) =>
      i === index ? { ...item, ...patch } : item,
    );
    onChange({ ...content, recommendation: { ...content.recommendation, items } });
  };

  const removeRecommendationItem = (index: number) => {
    onChange({
      ...content,
      recommendation: {
        ...content.recommendation,
        items: content.recommendation.items.filter((_, i) => i !== index),
      },
    });
  };

  const addRecommendationItem = () => {
    const blank: RecommendationItem = {
      target: '',
      method: '',
      priority: '',
      legal_basis: '',
      legal_basis_verified: false,
    };
    onChange({
      ...content,
      recommendation: { ...content.recommendation, items: [...content.recommendation.items, blank] },
    });
  };

  return (
    <div className="flex flex-col gap-8">
      {/* 1. 개요 */}
      <section className="flex flex-col gap-3 rounded-3xl border border-border bg-surface p-6">
        <h3 className="text-lg font-semibold text-text-default">개요</h3>
        <Field
          label="점검 목적"
          value={content.overview.purpose}
          readOnly={readOnly}
          onChange={(v) => updateOverview({ purpose: v })}
          multiline
        />
        <Field
          label="시설물 개요"
          value={content.overview.facility_summary}
          readOnly={readOnly}
          onChange={(v) => updateOverview({ facility_summary: v })}
          multiline
        />
        <Field
          label="점검 범위"
          value={content.overview.scope}
          readOnly={readOnly}
          onChange={(v) => updateOverview({ scope: v })}
          multiline
        />
      </section>

      {/* 2. 요약 */}
      <section className="flex flex-col gap-3 rounded-3xl border border-border bg-surface p-6">
        <h3 className="text-lg font-semibold text-text-default">요약</h3>
        <Field
          label="종합 의견"
          value={content.summary.overall_opinion}
          readOnly={readOnly}
          onChange={(v) => updateSummary({ overall_opinion: v })}
          multiline
        />
        {/* total_count/count_by_grade는 확정 하자 집계값(grounding 대조 기준)이라 편집 대상이 아니다 */}
        <div className="text-xs text-text-muted">
          총 하자 수 {content.summary.total_count}건 ·{' '}
          {Object.entries(content.summary.count_by_grade)
            .map(([grade, count]) => `${grade}등급 ${count}건`)
            .join(', ')}
        </div>
        <StringListEditor
          label="주요 발견사항"
          items={content.summary.key_findings}
          readOnly={readOnly}
          onChange={(key_findings) => updateSummary({ key_findings })}
        />
      </section>

      {/* 3. 상세 내역 */}
      <section className="flex flex-col gap-3 rounded-3xl border border-border bg-surface p-6">
        <h3 className="text-lg font-semibold text-text-default">상세 내역</h3>
        {content.detail.items.map((item, i) => (
          <div key={i} className="flex flex-col gap-2 rounded-2xl border border-border bg-surface-muted p-4">
            <div className="grid grid-cols-3 gap-3">
              <Field
                label="하자 유형"
                value={item.defect_type}
                readOnly={readOnly}
                onChange={(v) => updateDetailItem(i, { defect_type: v })}
              />
              <Field
                label="위치"
                value={item.location}
                readOnly={readOnly}
                onChange={(v) => updateDetailItem(i, { location: v })}
              />
              <Field
                label="등급"
                value={item.severity_grade}
                readOnly={readOnly}
                onChange={(v) => updateDetailItem(i, { severity_grade: v })}
              />
            </div>
            <Field
              label="설명"
              value={item.description}
              readOnly={readOnly}
              onChange={(v) => updateDetailItem(i, { description: v })}
              multiline
            />
            <Field
              label="원인"
              value={item.cause}
              readOnly={readOnly}
              onChange={(v) => updateDetailItem(i, { cause: v })}
              multiline
            />
            {!readOnly && (
              <Button variant="secondary" size="sm" onClick={() => removeDetailItem(i)}>
                이 항목 삭제
              </Button>
            )}
          </div>
        ))}
        {!readOnly && (
          <Button variant="secondary" size="sm" onClick={addDetailItem}>
            + 상세 항목 추가
          </Button>
        )}
      </section>

      {/* 4. 조치 권고 */}
      <section className="flex flex-col gap-3 rounded-3xl border border-border bg-surface p-6">
        <h3 className="text-lg font-semibold text-text-default">조치 권고</h3>
        {content.recommendation.items.map((item, i) => (
          <div key={i} className="flex flex-col gap-2 rounded-2xl border border-border bg-surface-muted p-4">
            <div className="grid grid-cols-2 gap-3">
              <Field
                label="대상"
                value={item.target}
                readOnly={readOnly}
                onChange={(v) => updateRecommendationItem(i, { target: v })}
              />
              <Field
                label="우선순위"
                value={item.priority}
                readOnly={readOnly}
                onChange={(v) => updateRecommendationItem(i, { priority: v })}
              />
            </div>
            <Field
              label="방법"
              value={item.method}
              readOnly={readOnly}
              onChange={(v) => updateRecommendationItem(i, { method: v })}
              multiline
            />
            <Field
              label={`법적 근거${item.legal_basis_verified ? ' (검증됨)' : ''}`}
              value={item.legal_basis}
              readOnly={readOnly}
              onChange={(v) => updateRecommendationItem(i, { legal_basis: v })}
              multiline
            />
            {!readOnly && (
              <Button variant="secondary" size="sm" onClick={() => removeRecommendationItem(i)}>
                이 항목 삭제
              </Button>
            )}
          </div>
        ))}
        {!readOnly && (
          <Button variant="secondary" size="sm" onClick={addRecommendationItem}>
            + 권고 항목 추가
          </Button>
        )}
        <StringListEditor
          label="모니터링 포인트"
          items={content.recommendation.monitoring_points}
          readOnly={readOnly}
          onChange={(monitoring_points) =>
            onChange({ ...content, recommendation: { ...content.recommendation, monitoring_points } })
          }
        />
      </section>
    </div>
  );
}
