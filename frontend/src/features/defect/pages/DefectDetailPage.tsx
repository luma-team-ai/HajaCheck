import { useParams } from 'react-router-dom';
import { DefectExplainPanel } from '../components/DefectExplainPanel';

// 최소 셸 — AI 설명 패널만 구현(FR-08-04 범위에 한정)
// TODO: 등급 수동조정, 상태 전이 스텝퍼, 이미지 탭 전환, 활동이력 (FR-08-04/05/06/08) — 별도 이슈
export function DefectDetailPage() {
  const { id } = useParams<{ id: string }>();

  // 임시 — 실제 하자 정보는 백엔드에서 조회 필요
  const defect = {
    id,
    defect_type: '철근 노출',
    severity_grade: 'D',
    location: '교각 하부',
    facility_type: '교량',
  };

  return (
    <div className="defect-detail-page">
      <header className="defect-detail-header">
        <h1>하자 상세</h1>
        <p className="defect-id">ID: {defect.id}</p>
      </header>

      <main className="defect-detail-main">
        <DefectExplainPanel
          defect_type={defect.defect_type}
          severity_grade={defect.severity_grade}
          location={defect.location}
          facility_type={defect.facility_type}
        />
      </main>
    </div>
  );
}
