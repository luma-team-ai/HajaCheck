import notoBoldUrl from '../../assets/fonts/NotoSansKR-Bold.subset.ttf?url';
import notoRegularUrl from '../../assets/fonts/NotoSansKR-Regular.subset.ttf?url';

// PDF에 한글(Noto Sans KR) 폰트를 임베딩하는 공용 유틸.
//
// features/report/utils/exportReportToPdf.ts(관공서 정밀안전진단 표준서식 PDF, 현재 prod 실제
// 제출 산출물)가 원래 이 로직(fetch → Base64 → addFileToVFS/addFont)을 파일 안에 직접 갖고
// 있었다. 통계 리포트(#1692)도 같은 폰트 임베딩이 필요해졌지만, report 파일은 회귀 위험 때문에
// 건드리지 않고(현재 prod 산출물) 이 공용 유틸을 새로 빼서 통계 쪽만 쓰게 했다 — 그래서 지금은
// report와 이 유틸 두 곳에 같은 로직이 중복돼 있다. report 쪽을 이 유틸로 갈아끼우는 공용화
// 통합은 회귀 없이 별도로 검증해야 하므로 후속 이슈로 남긴다.
export const PDF_FONT_NAME = 'NotoSansKR';
const REGULAR_FONT_FILE = 'NotoSansKR-Regular.ttf';
const BOLD_FONT_FILE = 'NotoSansKR-Bold.ttf';

async function toBase64(blob: Blob): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onloadend = () => {
      const result = reader.result as string;
      resolve(result.slice(result.indexOf(',') + 1));
    };
    reader.onerror = () => reject(reader.error);
    reader.readAsDataURL(blob);
  });
}

/** addFileToVFS/addFont만 있으면 되는 최소 타입 — jsPDF를 정적으로 의존하지 않는다(동적 import 유지). */
export interface PdfFontTarget {
  addFileToVFS(filename: string, data: string): unknown;
  addFont(postScriptName: string, id: string, fontStyle: string): unknown;
}

/**
 * Regular/Bold 두 글꼴을 fetch해 doc(jsPDF 인스턴스)에 등록한다.
 * 등록 후 `doc.setFont(PDF_FONT_NAME, 'normal')`로 실제 적용하는 것은 호출자 책임이다
 * (문서마다 기본 굵기가 다를 수 있어 이 유틸이 강제하지 않는다).
 */
export async function registerNotoSansKrFont(doc: PdfFontTarget): Promise<void> {
  const [regularResponse, boldResponse] = await Promise.all([fetch(notoRegularUrl), fetch(notoBoldUrl)]);
  const [regularBase64, boldBase64] = await Promise.all([
    toBase64(await regularResponse.blob()),
    toBase64(await boldResponse.blob()),
  ]);
  doc.addFileToVFS(REGULAR_FONT_FILE, regularBase64);
  doc.addFont(REGULAR_FONT_FILE, PDF_FONT_NAME, 'normal');
  doc.addFileToVFS(BOLD_FONT_FILE, boldBase64);
  doc.addFont(BOLD_FONT_FILE, PDF_FONT_NAME, 'bold');
}
