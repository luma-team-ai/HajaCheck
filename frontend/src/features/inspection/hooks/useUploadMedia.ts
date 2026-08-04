import { useMutation } from '@tanstack/react-query';
import { useRef, useState } from 'react';
import type { ApiError } from '../../../shared/api/types';
import { mediaApi } from '../api/mediaApi';
import type { Media } from '../types';

interface UploadMediaInput {
  inspectionId: number;
  files: File[];
}

// inspectionId를 훅 생성 시점이 아니라 호출 시점 인자로 받는다 — 새 점검 생성 화면처럼
// 점검 회차가 폼 제출로 막 만들어져 그 응답에서만 id를 아는 경우도 같은 훅으로 처리하기 위함.
export function useUploadMedia() {
  const [progress, setProgress] = useState(0);
  // 대용량 다건 업로드(수십 장) 중 브라우저가 onUploadProgress를 초당 수십 번 쏘는데, 같은
  // 퍼센트로 매번 setProgress를 부르면 매번 리렌더가 발생해(진행률을 보여주는 파일 목록이
  // 파일 수만큼 다시 그려짐) 그 시간 동안 버튼 클릭 반응이 느려진다 — 값이 실제로 바뀔 때만 갱신.
  const lastPercentRef = useRef(0);

  const mutation = useMutation<Media[], ApiError, UploadMediaInput>({
    mutationFn: ({ inspectionId, files }) => {
      lastPercentRef.current = 0;
      setProgress(0);
      return mediaApi
        .upload(inspectionId, files, (percent) => {
          if (percent === lastPercentRef.current) return;
          lastPercentRef.current = percent;
          setProgress(percent);
        })
        .then((res) => res.data);
    },
  });

  return {
    uploadMedia: mutation.mutateAsync,
    isPending: mutation.isPending,
    error: mutation.error,
    resetError: mutation.reset,
    progress,
  };
}
