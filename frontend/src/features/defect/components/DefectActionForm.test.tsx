// @vitest-environment jsdom
// 조치 결과 등록 폼 — 업로드 드롭존 미리보기(#969) 단위 테스트. QueryClientProvider + MSW로
// useDefectAssignableUsers/useUploadDefectActionPhoto/useSubmitDefectAction의 실제 요청 경로를
// DefectActionBoard.test.tsx와 동일하게 재사용한다(defectApi.handlers.ts 단일 소스).
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { setupServer } from 'msw/node';
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { defectHandlers } from '../api/defectApi.handlers';
import { DefectActionForm } from './DefectActionForm';

const server = setupServer(...defectHandlers);

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => {
  server.resetHandlers();
  cleanup();
});
afterAll(() => server.close());

// jsdom은 URL.createObjectURL/revokeObjectURL을 구현하지 않으므로 직접 스텁한다
// (FacilityPhotoUploadField.test.tsx:6-18과 동일 패턴).
let createObjectURLMock: ReturnType<typeof vi.fn>;
let revokeObjectURLMock: ReturnType<typeof vi.fn>;

beforeEach(() => {
  let counter = 0;
  createObjectURLMock = vi.fn(() => `blob:mock-${counter++}`);
  revokeObjectURLMock = vi.fn();
  URL.createObjectURL = createObjectURLMock as unknown as typeof URL.createObjectURL;
  URL.revokeObjectURL = revokeObjectURLMock as unknown as typeof URL.revokeObjectURL;
});

function makeImageFile(name: string): File {
  return new File(['fake-image-bytes'], name, { type: 'image/png' });
}

function renderForm() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={queryClient}>
      <DefectActionForm defectId={1} inspectionId={101} actionResult={null} />
    </QueryClientProvider>,
  );
}

describe('DefectActionForm — 업로드 드롭존 미리보기', () => {
  it('파일을 선택하면 미리보기 이미지와 파일명 칩이 렌더된다', () => {
    renderForm();

    const input = screen.getByLabelText('조치 후 사진 업로드 *');
    fireEvent.change(input, { target: { files: [makeImageFile('after.png')] } });

    const preview = screen.getByAltText('조치 후 사진 미리보기') as HTMLImageElement;
    expect(preview).not.toBeNull();
    expect(preview.src).toContain('blob:mock-0');
    expect(screen.getByText('after.png')).not.toBeNull();
    expect(createObjectURLMock).toHaveBeenCalledTimes(1);
    // 안내 아이콘/문구는 미리보기 상태에서 사라져야 한다.
    expect(screen.queryByText('파일을 드래그하거나 클릭하여 업로드')).toBeNull();
  });

  it('제거(X) 버튼 클릭 시 미리보기가 사라지고 원래 안내 문구로 돌아오며, 드롭존의 파일선택창 재오픈은 발생하지 않는다', () => {
    renderForm();

    const input = screen.getByLabelText('조치 후 사진 업로드 *') as HTMLInputElement;
    fireEvent.change(input, { target: { files: [makeImageFile('after.png')] } });
    expect(screen.getByAltText('조치 후 사진 미리보기')).not.toBeNull();

    const clickSpy = vi.spyOn(HTMLInputElement.prototype, 'click');

    fireEvent.click(screen.getByRole('button', { name: '선택한 사진 제거' }));

    expect(screen.queryByAltText('조치 후 사진 미리보기')).toBeNull();
    expect(screen.getByText('파일을 드래그하거나 클릭하여 업로드')).not.toBeNull();
    // 드롭존 div의 onClick(fileInputRef.current?.click())이 재발화하면 안 된다(stopPropagation 검증).
    expect(clickSpy).not.toHaveBeenCalled();
    // 같은 파일을 다시 선택해도 onChange가 재발화하도록 input value가 비워져야 한다.
    expect(input.value).toBe('');

    clickSpy.mockRestore();
  });

  it('언마운트 시 생성된 objectURL을 revoke한다', () => {
    const { unmount } = render(
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <DefectActionForm defectId={1} inspectionId={101} actionResult={null} />
      </QueryClientProvider>,
    );

    const input = screen.getByLabelText('조치 후 사진 업로드 *');
    fireEvent.change(input, { target: { files: [makeImageFile('after.png')] } });
    expect(revokeObjectURLMock).not.toHaveBeenCalled();

    unmount();

    expect(revokeObjectURLMock).toHaveBeenCalledWith('blob:mock-0');
  });
});

describe('DefectActionForm — actionResult 등록 완료 상태(회귀 확인)', () => {
  it('actionResult가 있으면 읽기 전용 요약을 렌더링하고 드롭존은 렌더링하지 않는다', () => {
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <QueryClientProvider client={queryClient}>
        <DefectActionForm
          defectId={1}
          inspectionId={101}
          actionResult={{
            actionContent: '에폭시 주입 처리',
            actionDate: '2026-07-20',
            assigneeId: 1,
            assigneeName: '홍길동',
            afterPhotoUrl: '/api/media/999/thumbnail',
          }}
        />
      </QueryClientProvider>,
    );

    expect(screen.getByText('에폭시 주입 처리')).not.toBeNull();
    expect(screen.queryByLabelText('조치 후 사진 업로드 *')).toBeNull();
  });
});
