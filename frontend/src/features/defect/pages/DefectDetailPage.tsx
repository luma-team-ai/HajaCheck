import { useParams } from 'react-router-dom';
import { useEffect, useState } from 'react';
import '../../../shared/styles/layout.css';
import { DefectExplainPanel } from '../components/DefectExplainPanel';
import { defectApi, DefectDetail } from '../api/defectApi';

// 최소 셸 — AI 설명 패널만 구현(FR-08-04 범위에 한정)
// 앱 셸(SideNavBar+Header)은 라우터 레벨 AppShellRoute가 담당(HAJA-186, #217 후속).
// 콘텐츠 패널은 Dashboard/MyPlan과 동일한 .dashboard-content(플로팅 화이트 패널, r=20px)를
// 재사용해 같은 셸 아래 페이지 간 시각적 일관성을 맞춘다(#227 리뷰 P2) — 자체 <main>은 두지
// 않는다(AppLayout이 이미 <main>으로 감싸므로 중첩 <main> 랜드마크가 되는 걸 방지).
// TODO: 등급 수동조정, 상태 전이 스텝퍼, 이미지 탭 전환, 활동이력 (FR-08-04/05/06/08) — 별도 이슈
export function DefectDetailPage() {
  const { id } = useParams<{ id: string }>();
  const [defect, setDefect] = useState<DefectDetail | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!id) {
      setError('하자 ID가 없습니다.');
      setIsLoading(false);
      return;
    }

    const fetchDefect = async () => {
      try {
        setIsLoading(true);
        const response = await defectApi.get(id);
        setDefect(response.data);
        setError(null);
      } catch (err) {
        setError('하자 정보를 불러올 수 없습니다.');
        setDefect(null);
      } finally {
        setIsLoading(false);
      }
    };

    fetchDefect();
  }, [id]);

  if (isLoading) {
    return (
      <div className="dashboard-content">
        <p>로딩 중...</p>
      </div>
    );
  }

  if (error || !defect) {
    return (
      <div className="dashboard-content">
        <p>{error || '하자 정보를 찾을 수 없습니다.'}</p>
      </div>
    );
  }

  return (
    <div className="dashboard-content">
      <div className="dashboard-page-header">
        <h1 className="dashboard-page-title">하자 상세</h1>
        <p className="defect-id">ID: {defect.id}</p>
      </div>

      <DefectExplainPanel
        defect_type={defect.type}
        severity_grade={defect.grade || '미정'}
        location=""
        facility_type={defect.facilityType}
      />
    </div>
  );
}
