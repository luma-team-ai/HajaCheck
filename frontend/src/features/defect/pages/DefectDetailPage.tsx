import { useParams } from 'react-router-dom';
import '../../../shared/styles/layout.css';
import { DefectExplainPanel } from '../components/DefectExplainPanel';

// 최소 셸 — AI 설명 패널만 구현(FR-08-04 범위에 한정)
// 앱 셸(SideNavBar+Header)은 라우터 레벨 AppShellRoute가 담당(HAJA-186, #217 후속).
// 콘텐츠 패널은 Dashboard/MyPlan과 동일한 .dashboard-content(플로팅 화이트 패널, r=20px)를
// 재사용해 같은 셸 아래 페이지 간 시각적 일관성을 맞춘다(#227 리뷰 P2) — 자체 <main>은 두지
// 않는다(AppLayout이 이미 <main>으로 감싸므로 중첩 <main> 랜드마크가 되는 걸 방지).
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
    <div className="dashboard-content">
      <div className="dashboard-page-header">
        <h1 className="dashboard-page-title">하자 상세</h1>
        <p className="defect-id">ID: {defect.id}</p>
      </div>

      <DefectExplainPanel
        defect_type={defect.defect_type}
        severity_grade={defect.severity_grade}
        location={defect.location}
        facility_type={defect.facility_type}
      />
    </div>
  );
}
