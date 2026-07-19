import { useParams } from 'react-router-dom';
import '../../../shared/styles/layout.css';
import { ErrorFallback } from '../../../shared/components/ErrorFallback';
import { DefectExplainPanel } from '../components/DefectExplainPanel';
import { DefectStatusStepper } from '../components/DefectStatusStepper';
import { useDefect } from '../hooks/useDefect';
import { useUpdateDefectStatus } from '../hooks/useUpdateDefectStatus';
import { DEFECT_GRADE_LABEL, DEFECT_STATUS_LABEL } from '../types';
import type { DefectStatus } from '../types';

// 앱 셸(SideNavBar+Header)은 라우터 레벨 AppShellRoute가 담당(HAJA-186, #217 후속).
// 콘텐츠 패널은 Dashboard/MyPlan과 동일한 .dashboard-content(플로팅 화이트 패널, r=20px)를
// 재사용해 같은 셸 아래 페이지 간 시각적 일관성을 맞춘다(#227 리뷰 P2) — 자체 <main>은 두지
// 않는다(AppLayout이 이미 <main>으로 감싸므로 중첩 <main> 랜드마크가 되는 걸 방지).
// HAJA-30: 하드코딩 목데이터를 useDefect(id) 실 데이터 조회로 교체. AI 설명 패널은 그대로 유지.
// HAJA-30 2단계: 상태 전이 스텝퍼 추가(FR-4). 신규→검수확정→조치대기→조치중→조치완료.
// TODO: 등급 수동조정, 이미지 탭 전환, 활동이력 (FR-08-04/06/08) — 별도 이슈
export function DefectDetailPage() {
  const { id } = useParams<{ id: string }>();
  const defectId = id != null ? Number(id) : undefined;
  const { data: defect, isLoading, isError, refetch } = useDefect(defectId);
  const { updateStatus, isPending: isStatusUpdating, error: statusError } =
    useUpdateDefectStatus(defectId);

  const handleAdvance = (next: DefectStatus) => {
    // 에러는 useUpdateDefectStatus의 mutation.error 상태로 스텝퍼에 표시하므로 여기서는 흡수만 한다
    // (mutateAsync가 던지는 reject를 잡지 않으면 unhandled rejection 경고가 발생한다).
    updateStatus(next).catch(() => {});
  };

  return (
    <div className="dashboard-content">
      <div className="dashboard-page-header">
        <h1 className="dashboard-page-title">하자 상세</h1>
        <p className="defect-id">ID: {id}</p>
      </div>

      {isLoading && (
        <div className="flex items-center justify-center px-4 py-12 text-sm text-text-muted" role="status">
          불러오는 중...
        </div>
      )}

      {isError && <ErrorFallback message="하자 정보를 불러오지 못했습니다." onRetry={refetch} />}

      {!isLoading && !isError && defect && (
        <>
          <dl className="mb-6 grid grid-cols-2 gap-x-6 gap-y-3 rounded-2xl border border-border bg-surface p-6 text-sm sm:grid-cols-4">
            <div>
              <dt className="text-text-muted">유형</dt>
              <dd className="m-0 font-medium text-text-default">{defect.typeLabel}</dd>
            </div>
            <div>
              <dt className="text-text-muted">등급</dt>
              <dd className="m-0 font-medium text-text-default">
                {defect.grade ? `${defect.grade} · ${DEFECT_GRADE_LABEL[defect.grade]}` : '미분류'}
              </dd>
            </div>
            <div>
              <dt className="text-text-muted">위치</dt>
              <dd className="m-0 font-medium text-text-default">{defect.facilityName}</dd>
            </div>
            <div>
              <dt className="text-text-muted">상태</dt>
              <dd className="m-0 font-medium text-text-default">{DEFECT_STATUS_LABEL[defect.status]}</dd>
            </div>
          </dl>

          <DefectStatusStepper
            status={defect.status}
            onAdvance={handleAdvance}
            isPending={isStatusUpdating}
            errorMessage={statusError?.message}
          />

          <DefectExplainPanel
            defect_type={defect.typeLabel}
            severity_grade={defect.grade ?? '미분류'}
            location={defect.facilityName}
            facility_type={defect.facilityType}
          />
        </>
      )}
    </div>
  );
}
