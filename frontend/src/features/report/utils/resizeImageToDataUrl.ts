// 위치도ㆍ전경 사진ㆍ종ㆍ평면도ㆍ현황도 섹션(#1409)이 이미지를 content_json에 base64로 직접
// 저장하므로(새 업로드 엔드포인트 없음), 원본 스캔본·사진을 그대로 넣으면 PATCH content 요청
// payload가 수 MB로 불어난다. 업로드 시점에 긴 변을 MAX_DIMENSION으로 줄여 완화한다.
const MAX_DIMENSION = 1600;
const JPEG_QUALITY = 0.82;

/** 이미지 파일을 긴 변 기준 MAX_DIMENSION 이하로 축소한 JPEG data URL로 변환한다. */
export function resizeImageToDataUrl(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const objectUrl = URL.createObjectURL(file);
    const image = new Image();
    image.onload = () => {
      URL.revokeObjectURL(objectUrl);
      const scale = Math.min(1, MAX_DIMENSION / Math.max(image.width, image.height));
      const width = Math.max(1, Math.round(image.width * scale));
      const height = Math.max(1, Math.round(image.height * scale));

      const canvas = document.createElement('canvas');
      canvas.width = width;
      canvas.height = height;
      const ctx = canvas.getContext('2d');
      if (!ctx) {
        reject(new Error('캔버스 컨텍스트를 생성하지 못했습니다.'));
        return;
      }
      ctx.drawImage(image, 0, 0, width, height);
      resolve(canvas.toDataURL('image/jpeg', JPEG_QUALITY));
    };
    image.onerror = () => {
      URL.revokeObjectURL(objectUrl);
      reject(new Error('이미지를 불러오지 못했습니다.'));
    };
    image.src = objectUrl;
  });
}
