// RAG 문서 업로드 multipart 요청 변환 로직 단위 테스트 — 실제 HTTP 라운드트립(파일 파트 포함)은
// msw+jsdom+undici 조합의 환경 한계로 이 리포의 테스트 러너에서 안정적으로 재현되지 않아
// (authApi.buildCompanySignupFormData.test.ts와 동일 이유) 변환 로직만 별도로 검증한다.
import { describe, expect, it } from 'vitest';
import type { RagDocumentUploadPayload } from '../ragDocument.types';
import { toRagDocumentUploadFormData } from './ragDocumentApi';

function makePayload(overrides: Partial<RagDocumentUploadPayload> = {}): RagDocumentUploadPayload {
  return {
    file: new File(['%PDF-1.4'], 'law.pdf', { type: 'application/pdf' }),
    title: '시설물의 안전관리에 관한 특별법',
    sourceType: 'LAW',
    targetCollection: 'REGULATIONS',
    ...overrides,
  };
}

describe('toRagDocumentUploadFormData', () => {
  it('필수 필드 + 파일을 계약 필드명과 1:1로 FormData에 담는다', () => {
    const formData = toRagDocumentUploadFormData(makePayload());

    const file = formData.get('file') as File;
    expect(file.name).toBe('law.pdf');
    expect(file.type).toBe('application/pdf');
    expect(formData.get('title')).toBe('시설물의 안전관리에 관한 특별법');
    expect(formData.get('sourceType')).toBe('LAW');
    expect(formData.get('targetCollection')).toBe('REGULATIONS');
  });

  it('선택 필드(publisher/effectiveDate/authoredAt)를 넘기면 포함한다', () => {
    const formData = toRagDocumentUploadFormData(
      makePayload({ publisher: '국토교통부', effectiveDate: '2026-01-01' }),
    );

    expect(formData.get('publisher')).toBe('국토교통부');
    expect(formData.get('effectiveDate')).toBe('2026-01-01');
    expect(formData.get('authoredAt')).toBeNull();
  });

  it('선택 필드를 넘기지 않으면 FormData에 키 자체가 없다', () => {
    const formData = toRagDocumentUploadFormData(makePayload());

    expect(formData.get('publisher')).toBeNull();
    expect(formData.get('effectiveDate')).toBeNull();
    expect(formData.get('authoredAt')).toBeNull();
  });

  it('defect_kb 대상 문서는 authoredAt을 포함할 수 있다', () => {
    const formData = toRagDocumentUploadFormData(
      makePayload({
        title: '하자 유형별 보수 지침',
        sourceType: 'GUIDELINE',
        targetCollection: 'DEFECT_KB',
        authoredAt: '2026-06-01',
      }),
    );

    expect(formData.get('sourceType')).toBe('GUIDELINE');
    expect(formData.get('targetCollection')).toBe('DEFECT_KB');
    expect(formData.get('authoredAt')).toBe('2026-06-01');
  });
});
