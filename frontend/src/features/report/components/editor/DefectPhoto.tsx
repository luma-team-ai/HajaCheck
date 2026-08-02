import { useEffect, useState } from 'react';
import type { Defect } from '../../../inspection/types';
import { isDrawableBbox } from '../../../inspection/utils/isDrawableBbox';

/** 사진 1장 + 그 위에 얹을 하자 목록. `mediaId`가 null인 하자(수동 추가 등)는 사진별로 묶이지 않는다. */
export interface DefectPhotoGroup {
  /** 그룹 키 — 같은 사진의 하자를 한 장에 모으는 기준. null이면 하자 단건 그룹. */
  mediaId: number | null;
  imageUrl?: string | null;
  /** 이 사진에서 탐지된 하자. `bbox`가 없는 항목은 박스 없이 사진만 보여준다. */
  defects: Defect[];
  /** 지정 시 그 id의 박스만 강조하고 나머지는 흐리게 — 하자 상세 항목처럼 1건이 주인공일 때 쓴다. */
  highlightDefectId?: number;
}

interface DefectPhotoProps {
  group: DefectPhotoGroup;
  /** 이미지에 적용할 클래스 — 그리드 카드/상세 패널이 각자 크기를 정한다. */
  imageClassName?: string;
  alt: string;
  /** 이미지가 없거나 로드에 실패했을 때 자리 표시자 */
  fallback: React.ReactNode;
}

/**
 * 리포트에서 하자 박스를 얹은 사진을 그린다(#1333).
 *
 * <p>⚠️ 이미지에 `object-cover`를 쓰면 안 된다 — 크롭이 일어나 정규화 좌표(0~1)와 표시 영역이
 * 어긋나 박스가 엉뚱한 곳에 그려진다. 컨테이너를 이미지 크기에 맞추고(`w-fit`) 박스를 %로 얹어,
 * 이미지가 어떤 비율이든 좌표가 그대로 대응하게 한다(`DefectOverlay`와 같은 방식).
 */
export function DefectPhoto({ group, imageClassName = '', alt, fallback }: DefectPhotoProps) {
  const [failed, setFailed] = useState(false);

  // 그룹이 바뀌면(페이지 이동·필터 변경) 이전 실패 상태를 물고 있지 않도록 재동기화한다.
  useEffect(() => {
    setFailed(false);
  }, [group.imageUrl]);

  if (!group.imageUrl || failed) {
    return <>{fallback}</>;
  }

  const drawable = group.defects.filter(isDrawableBbox);

  return (
    <div className="relative w-fit max-w-full">
      <img
        src={group.imageUrl}
        alt={alt}
        onError={() => setFailed(true)}
        className={`block ${imageClassName}`}
      />
      {drawable.map((defect) => {
        // highlight가 지정된 경우에만 주/조연을 나눈다 — 지정이 없으면 전부 동일 강조.
        const isMuted =
          group.highlightDefectId !== undefined && group.highlightDefectId !== defect.id;
        return (
          <span
            key={defect.id}
            aria-hidden="true"
            className={`absolute box-border rounded-sm border-2 ${
              isMuted ? 'border-selection/40' : 'border-selection'
            }`}
            style={{
              left: `${defect.bbox.x * 100}%`,
              top: `${defect.bbox.y * 100}%`,
              width: `${defect.bbox.width * 100}%`,
              height: `${defect.bbox.height * 100}%`,
              backgroundColor: isMuted ? 'transparent' : 'var(--color-selection-soft-bg)',
            }}
          />
        );
      })}
    </div>
  );
}

/**
 * 하자 목록을 사진 단위로 묶는다 — 같은 사진에서 하자가 여러 건 나오면 사진이 그 수만큼
 * 중복되던 문제(#1333)를 여기서 해소한다. 원래 순서를 보존해 사진이 처음 등장한 자리에 놓는다.
 * `mediaId`가 없는 하자는 묶을 기준이 없으므로 각자 독립 그룹이 된다.
 */
export function groupDefectsByMedia(defects: Defect[]): DefectPhotoGroup[] {
  const groups: DefectPhotoGroup[] = [];
  const indexByMediaId = new Map<number, number>();

  defects.forEach((defect) => {
    const mediaId = defect.mediaId ?? null;
    if (mediaId === null) {
      groups.push({ mediaId: null, imageUrl: defect.imageUrl, defects: [defect] });
      return;
    }
    const existing = indexByMediaId.get(mediaId);
    if (existing === undefined) {
      indexByMediaId.set(mediaId, groups.length);
      groups.push({ mediaId, imageUrl: defect.imageUrl, defects: [defect] });
      return;
    }
    groups[existing].defects.push(defect);
    // 앞선 하자의 imageUrl이 비어 있으면 뒤 하자 것으로 메운다(같은 사진이므로 동등하다).
    groups[existing].imageUrl ??= defect.imageUrl;
  });

  return groups;
}
