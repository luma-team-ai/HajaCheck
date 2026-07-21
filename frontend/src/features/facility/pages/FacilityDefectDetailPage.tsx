import { useNavigate, useParams } from 'react-router-dom';
import '../../../shared/styles/layout.css';
import { FacilityDefectActivityTimeline } from '../components/FacilityDefectActivityTimeline';
import { FacilityDefectAiExplainPanel } from '../components/FacilityDefectAiExplainPanel';
import { FacilityDefectImagePanel } from '../components/FacilityDefectImagePanel';
import { FacilityDefectInfoPanel } from '../components/FacilityDefectInfoPanel';
import { useFacilityDefectDetail } from '../hooks/useFacilityDefectDetail';

const DEFAULT_ID = 'detail';

// 시설물 상세 하위 드릴다운 — 하자 정보 패널(dev-04-02, #489). /facilities/:id(시설물 개요,
// dev-05-02·#504)에서 특정 하자를 드릴다운하는 화면이라 :id(시설물)와 :defectId(하자)를 함께 받는다.
export function FacilityDefectDetailPage() {
  const { id: facilityId = DEFAULT_ID, defectId = DEFAULT_ID } = useParams<{
    id: string;
    defectId: string;
  }>();
  const navigate = useNavigate();
  const { data: defect, isLoading, isError, refetch } = useFacilityDefectDetail(defectId);

  const handleCompareClick = () => {
    navigate(`/facilities/${facilityId}/defects/${defectId}/compare`);
  };

  // "다음 단계로 전이" — 상태 mutation이 아니라 하자 관리 도메인(/defects/:id, DefectDetailPage)으로
  // 이동하는 단순 navigation이다(#489 확정). 별도 상태 전이 mutation/API는 두지 않는다.
  const handleTransitionClick = () => {
    navigate(`/defects/${defectId}`);
  };

  if (isLoading) {
    return <div className="dashboard-content text-sm text-text-muted">불러오는 중...</div>;
  }

  if (isError || !defect) {
    return (
      <div className="dashboard-content">
        <p className="m-0 text-sm text-text-muted">하자 정보를 불러오지 못했습니다.</p>
        <button
          type="button"
          onClick={() => refetch()}
          className="self-start text-sm font-semibold text-accent"
        >
          다시 시도
        </button>
      </div>
    );
  }

  return (
    <div className="dashboard-content">
      <div className="dashboard-page-header">
        <h1 className="dashboard-page-title">하자 상세</h1>
      </div>

      <div className="grid gap-8 lg:grid-cols-[1.4fr_1fr]">
        <FacilityDefectImagePanel defect={defect} onCompareClick={handleCompareClick} />
        <FacilityDefectInfoPanel defect={defect} onTransitionClick={handleTransitionClick} />
      </div>

      <FacilityDefectAiExplainPanel
        defectId={defect.id}
        defectType={defect.defectType}
        grade={defect.grade}
        location={defect.location}
      />

      <FacilityDefectActivityTimeline facilityId={defectId} />
    </div>
  );
}