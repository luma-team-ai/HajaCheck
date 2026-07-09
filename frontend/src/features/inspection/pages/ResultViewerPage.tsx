import type { ChangeEvent } from 'react';
import { useState } from 'react';
import { useParams } from 'react-router-dom';
import { DefectOverlay } from '../components/DefectOverlay';
import { useInspectionResult } from '../hooks/useInspectionResult';
import type { DefectGrade } from '../types';
import { filterDefects } from '../utils/filterDefects';

const ALL_GRADES: DefectGrade[] = ['A', 'B', 'C', 'D', 'E'];

export function ResultViewerPage() {
  const { id } = useParams<{ id: string }>();
  const inspectionId = Number(id);
  const [confidenceThreshold, setConfidenceThreshold] = useState(0.5);
  const [gradeFilter, setGradeFilter] = useState<DefectGrade[]>(ALL_GRADES);

  // URL 파라미터 검증
  if (!Number.isInteger(inspectionId) || inspectionId <= 0) {
    return <div style={{ padding: '20px', color: 'red' }}>잘못된 접근입니다. 유효한 검사 ID를 확인하세요.</div>;
  }

  const { data, isLoading, isError } = useInspectionResult(inspectionId);

  const handleThresholdChange = (event: ChangeEvent<HTMLInputElement>) => {
    setConfidenceThreshold(Number(event.target.value));
  };

  const handleGradeToggle = (grade: DefectGrade) => {
    setGradeFilter((prev) =>
      prev.includes(grade) ? prev.filter((g) => g !== grade) : [...prev, grade],
    );
  };

  if (isLoading) return <div>불러오는 중...</div>;
  if (isError) return <div>결과를 불러오지 못했습니다.</div>;
  if (!data || data.defects.length === 0) return <div>탐지된 하자가 없습니다.</div>;

  const visibleDefects = filterDefects(data.defects, confidenceThreshold, gradeFilter);

  return (
    <div>
      <h2>분석 결과 뷰어 (프로토타입)</h2>

      <label>
        confidence 임계값: {confidenceThreshold.toFixed(2)}
        <input
          type="range"
          min={0}
          max={1}
          step={0.05}
          value={confidenceThreshold}
          onChange={handleThresholdChange}
        />
      </label>

      <div>
        {ALL_GRADES.map((grade) => (
          <label key={grade}>
            <input
              type="checkbox"
              checked={gradeFilter.includes(grade)}
              onChange={() => handleGradeToggle(grade)}
            />
            {grade}등급
          </label>
        ))}
      </div>

      <DefectOverlay media={data.media} defects={visibleDefects} />
    </div>
  );
}
