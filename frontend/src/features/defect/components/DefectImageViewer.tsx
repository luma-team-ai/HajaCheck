type Props = {
  imageUrl: string | null;
  typeLabel: string;
  bboxX: number | null;
  bboxY: number | null;
  bboxW: number | null;
  bboxH: number | null;
};

// 하자 실사진 표시(HAJA-314) — bbox 좌표는 0~1 비율이라 %로 변환해 오버레이 위치를 잡는다.
// 백엔드가 media_id 없는(아직 이미지가 연결되지 않은) 하자를 그대로 반환할 수 있어(imageUrl=null),
// 그 경우 빈 상태를 명시적으로 보여준다 — 깨진 이미지 아이콘을 노출하지 않는다.
export function DefectImageViewer({ imageUrl, typeLabel, bboxX, bboxY, bboxW, bboxH }: Props) {
  const hasBbox = bboxX != null && bboxY != null && bboxW != null && bboxH != null;

  return (
    <section className="defect-card defect-viewer" aria-label="하자 이미지">
      <div className="defect-image-stage">
        {imageUrl ? (
          <>
            <img src={imageUrl} alt={`${typeLabel} 촬영 이미지`} />
            {hasBbox && (
              <div
                className="defect-detection-box"
                aria-label="AI 감지 영역"
                style={{
                  top: `${bboxY * 100}%`,
                  left: `${bboxX * 100}%`,
                  width: `${bboxW * 100}%`,
                  height: `${bboxH * 100}%`,
                }}
              />
            )}
          </>
        ) : (
          <div className="defect-image-empty" role="status">
            촬영 이미지가 없습니다
          </div>
        )}
      </div>
    </section>
  );
}
