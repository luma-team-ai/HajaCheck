// @vitest-environment jsdom
// #298 — OCR 예상화면(잘못된 랜딩 히어로 이미지) 제거 + 드래그앤드롭 업로드 버튼 회귀 방지.
// 특히 드롭 경로 검증(accept 속성이 드래그앤드롭엔 적용되지 않는 문제)을 고정하는 테스트 포함.
// #748 — 업로드 이미지 썸네일 미리보기·OCR 결과 피드백 테스트 포함.
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { BusinessLicenseUpload } from './BusinessLicenseUpload';

afterEach(cleanup);

// jsdom은 URL.createObjectURL/revokeObjectURL을 구현하지 않으므로 직접 스텁한다
// (FacilityPhotoUploadField.test.tsx와 동일 패턴).
let createObjectURLMock: ReturnType<typeof vi.fn>;
let revokeObjectURLMock: ReturnType<typeof vi.fn>;

beforeEach(() => {
  let counter = 0;
  createObjectURLMock = vi.fn(() => `blob:mock-${counter++}`);
  revokeObjectURLMock = vi.fn();
  URL.createObjectURL = createObjectURLMock as unknown as typeof URL.createObjectURL;
  URL.revokeObjectURL = revokeObjectURLMock as unknown as typeof URL.revokeObjectURL;
});

function getDropzone() {
  // 드롭존 컨테이너 = "파일을 끌어다 놓거나" 문구의 부모 요소
  return screen.getByText('파일을 끌어다 놓거나').parentElement as HTMLElement;
}

describe('BusinessLicenseUpload', () => {
  it('"파일 선택" 버튼 클릭 시 숨겨진 input의 클릭을 트리거한다', () => {
    const onFileSelect = vi.fn();
    render(<BusinessLicenseUpload file={null} onFileSelect={onFileSelect} />);

    const input = screen.getByLabelText('사업자등록증') as HTMLInputElement;
    const clickSpy = vi.spyOn(input, 'click');

    fireEvent.click(screen.getByRole('button', { name: '파일 선택' }));

    expect(clickSpy).toHaveBeenCalledTimes(1);
  });

  it('파일 선택(클릭 경로) 시 파일명·용량 칩을 노출하고 ✕ 클릭 시 제거한다', () => {
    const onFileSelect = vi.fn();
    const { rerender } = render(<BusinessLicenseUpload file={null} onFileSelect={onFileSelect} />);

    const file = new File(['dummy'], 'license.png', { type: 'image/png' });
    fireEvent.change(screen.getByLabelText('사업자등록증'), { target: { files: [file] } });

    expect(onFileSelect).toHaveBeenCalledWith(file);

    rerender(<BusinessLicenseUpload file={file} onFileSelect={onFileSelect} />);

    expect(screen.getByText('license.png')).not.toBeNull();

    fireEvent.click(screen.getByRole('button', { name: '첨부 파일 삭제' }));
    expect(onFileSelect).toHaveBeenCalledWith(null);
  });

  it('드롭으로 허용되지 않는 타입을 투입하면 onFileSelect를 호출하지 않고 에러를 노출한다', () => {
    const onFileSelect = vi.fn();
    render(<BusinessLicenseUpload file={null} onFileSelect={onFileSelect} />);

    const invalidFile = new File(['not a license'], 'malware.exe', { type: 'text/plain' });
    fireEvent.drop(getDropzone(), { dataTransfer: { files: [invalidFile] } });

    expect(onFileSelect).not.toHaveBeenCalled();
    expect(screen.getByText('지원하지 않는 파일 형식입니다. (JPG, PNG, PDF만 가능)')).not.toBeNull();
  });

  it('드롭으로 유효한 PDF를 투입하면 onFileSelect가 호출된다', () => {
    const onFileSelect = vi.fn();
    render(<BusinessLicenseUpload file={null} onFileSelect={onFileSelect} />);

    const validFile = new File(['%PDF-1.4'], 'license.pdf', { type: 'application/pdf' });
    fireEvent.drop(getDropzone(), { dataTransfer: { files: [validFile] } });

    expect(onFileSelect).toHaveBeenCalledWith(validFile);
  });

  it('OCR 예상화면 이미지를 더 이상 렌더링하지 않는다', () => {
    render(<BusinessLicenseUpload file={null} onFileSelect={vi.fn()} />);

    expect(screen.queryByRole('img')).toBeNull();
    expect(screen.queryByText(/자동인식 완료 시 예상 화면/)).toBeNull();
  });

  it('OCR 자동채움 구현(#587, 개업일자 확장 #600) 후 실제 동작에 맞는 안내 문구를 노출한다', () => {
    render(<BusinessLicenseUpload file={null} onFileSelect={vi.fn()} />);

    expect(
      screen.getByText(
        /JPG, PNG 파일은 업로드 시 사업자등록번호·상호명·대표자명·개업일자가 자동으로 채워집니다/,
      ),
    ).not.toBeNull();
    expect(screen.queryByText(/준비 중입니다/)).toBeNull();
  });

  it('isOcrLoading이 true면 자동인식 중 로딩 문구를 노출한다', () => {
    const { rerender } = render(
      <BusinessLicenseUpload file={null} onFileSelect={vi.fn()} isOcrLoading={false} />,
    );
    expect(screen.queryByRole('status')).toBeNull();

    rerender(<BusinessLicenseUpload file={null} onFileSelect={vi.fn()} isOcrLoading={true} />);
    expect(screen.getByRole('status').textContent).toContain('자동인식하는 중입니다');
  });

  // #748 — 업로드 이미지 썸네일 미리보기
  // 장식용 썸네일이라 alt=""를 쓰는데, 이 경우 암묵적 role은 "presentation"이라
  // getByRole('img')로는 찾히지 않는다(a11y 트리에서 제외) — container.querySelector로 확인한다.
  it('이미지 파일을 선택하면 썸네일을 렌더링하고 objectURL을 생성한다', () => {
    const file = new File(['dummy'], 'license.png', { type: 'image/png' });
    const { container } = render(<BusinessLicenseUpload file={file} onFileSelect={vi.fn()} />);

    expect(createObjectURLMock).toHaveBeenCalledWith(file);
    const thumbnail = container.querySelector('img') as HTMLImageElement | null;
    expect(thumbnail).not.toBeNull();
    expect(thumbnail?.src).toContain('blob:mock-0');
  });

  it('PDF 파일은 썸네일 대신 기존 📄 아이콘을 유지한다(objectURL 생성 안 함)', () => {
    const file = new File(['%PDF-1.4'], 'license.pdf', { type: 'application/pdf' });
    const { container } = render(<BusinessLicenseUpload file={file} onFileSelect={vi.fn()} />);

    expect(createObjectURLMock).not.toHaveBeenCalled();
    expect(container.querySelector('img')).toBeNull();
    expect(screen.getByText('license.pdf')).not.toBeNull();
  });

  it('파일을 다른 이미지로 교체하면 이전 objectURL을 revoke하고 새 objectURL을 생성한다', () => {
    const fileA = new File(['a'], 'a.png', { type: 'image/png' });
    const fileB = new File(['b'], 'b.png', { type: 'image/png' });
    const { rerender } = render(<BusinessLicenseUpload file={fileA} onFileSelect={vi.fn()} />);

    expect(createObjectURLMock).toHaveBeenCalledTimes(1);

    rerender(<BusinessLicenseUpload file={fileB} onFileSelect={vi.fn()} />);

    expect(revokeObjectURLMock).toHaveBeenCalledWith('blob:mock-0');
    expect(createObjectURLMock).toHaveBeenCalledTimes(2);
  });

  it('언마운트 시 생성된 objectURL을 revoke한다', () => {
    const file = new File(['dummy'], 'license.png', { type: 'image/png' });
    const { unmount } = render(<BusinessLicenseUpload file={file} onFileSelect={vi.fn()} />);

    unmount();

    expect(revokeObjectURLMock).toHaveBeenCalledWith('blob:mock-0');
  });

  // #748 — OCR 진행 스피너 강조: role=status는 유지하되 회전 스피너(장식용 span)가 함께 있어야 한다.
  it('isOcrLoading이 true면 role=status 로딩 박스 안에 회전 스피너를 함께 렌더링한다', () => {
    render(<BusinessLicenseUpload file={null} onFileSelect={vi.fn()} isOcrLoading={true} />);

    const statusBox = screen.getByRole('status');
    expect(statusBox.querySelector('.animate-spin')).not.toBeNull();
  });

  // #748 — OCR 성공/실패 결과 피드백
  it('ocrFeedback이 success면 채운 필드 수를 포함한 안내 문구를 노출한다', () => {
    render(
      <BusinessLicenseUpload
        file={null}
        onFileSelect={vi.fn()}
        ocrFeedback={{ status: 'success', filledCount: 3 }}
      />,
    );

    expect(screen.getByText('✓ 3개 항목이 자동입력됐어요')).not.toBeNull();
  });

  it('ocrFeedback이 empty면 인식된 정보가 없다는 중립 안내를 노출한다', () => {
    render(
      <BusinessLicenseUpload
        file={null}
        onFileSelect={vi.fn()}
        ocrFeedback={{ status: 'empty', filledCount: 0 }}
      />,
    );

    expect(
      screen.getByText('인식된 정보가 없어요. 아래 항목을 직접 입력해 주세요'),
    ).not.toBeNull();
  });

  it('ocrFeedback이 error면 실패 안내를 노출하고, isOcrLoading이 true인 동안은 감춘다', () => {
    const { rerender } = render(
      <BusinessLicenseUpload
        file={null}
        onFileSelect={vi.fn()}
        isOcrLoading={true}
        ocrFeedback={{ status: 'error', filledCount: 0 }}
      />,
    );
    expect(screen.queryByText(/자동인식에 실패했어요/)).toBeNull();

    rerender(
      <BusinessLicenseUpload
        file={null}
        onFileSelect={vi.fn()}
        isOcrLoading={false}
        ocrFeedback={{ status: 'error', filledCount: 0 }}
      />,
    );
    expect(screen.getByText(/자동인식에 실패했어요/)).not.toBeNull();
  });

  it('ocrFeedback이 없으면 결과 피드백 문구를 노출하지 않는다', () => {
    render(<BusinessLicenseUpload file={null} onFileSelect={vi.fn()} ocrFeedback={null} />);

    expect(screen.queryByText(/자동입력됐어요/)).toBeNull();
    expect(screen.queryByText(/인식된 정보가 없어요/)).toBeNull();
    expect(screen.queryByText(/자동인식에 실패했어요/)).toBeNull();
  });

  // #767 — 썸네일 클릭 확대(라이트박스)
  describe('이미지 썸네일 라이트박스(#767)', () => {
    function renderWithImage() {
      const file = new File(['dummy'], 'license.png', { type: 'image/png' });
      return render(<BusinessLicenseUpload file={file} onFileSelect={vi.fn()} />);
    }

    it('썸네일을 클릭하면 라이트박스가 열리고 원본 이미지를 role=dialog 안에 노출한다', () => {
      renderWithImage();

      expect(screen.queryByRole('dialog')).toBeNull();

      fireEvent.click(screen.getByRole('button', { name: '사업자등록증 이미지 크게 보기' }));

      const dialog = screen.getByRole('dialog', { name: '사업자등록증 이미지 크게 보기' });
      expect(dialog).not.toBeNull();
      const originalImage = screen.getByAltText('사업자등록증 원본 이미지') as HTMLImageElement;
      expect(originalImage.src).toContain('blob:mock-0');
      // 라이트박스는 기존 previewUrl을 재사용할 뿐, 추가 objectURL을 만들지 않는다.
      expect(createObjectURLMock).toHaveBeenCalledTimes(1);
    });

    it('닫기(✕) 버튼 클릭 시 라이트박스가 닫힌다', () => {
      renderWithImage();

      fireEvent.click(screen.getByRole('button', { name: '사업자등록증 이미지 크게 보기' }));
      expect(screen.getByRole('dialog')).not.toBeNull();

      fireEvent.click(screen.getByRole('button', { name: '닫기' }));
      expect(screen.queryByRole('dialog')).toBeNull();
    });

    it('배경(바깥 영역) 클릭 시 라이트박스가 닫힌다', () => {
      renderWithImage();

      fireEvent.click(screen.getByRole('button', { name: '사업자등록증 이미지 크게 보기' }));
      expect(screen.getByRole('dialog')).not.toBeNull();

      fireEvent.click(screen.getByRole('button', { name: '배경 클릭하여 닫기' }));
      expect(screen.queryByRole('dialog')).toBeNull();
    });

    it('Esc 키를 누르면 라이트박스가 닫힌다', () => {
      renderWithImage();

      fireEvent.click(screen.getByRole('button', { name: '사업자등록증 이미지 크게 보기' }));
      expect(screen.getByRole('dialog')).not.toBeNull();

      fireEvent.keyDown(document, { key: 'Escape' });
      expect(screen.queryByRole('dialog')).toBeNull();
    });

    it('원본 이미지 자체 클릭으로는 닫히지 않는다', () => {
      renderWithImage();

      fireEvent.click(screen.getByRole('button', { name: '사업자등록증 이미지 크게 보기' }));
      expect(screen.getByRole('dialog')).not.toBeNull();

      fireEvent.click(screen.getByAltText('사업자등록증 원본 이미지'));
      expect(screen.getByRole('dialog')).not.toBeNull();
    });

    it('열려 있는 상태에서 파일이 삭제되면(previewUrl 소멸) 라이트박스도 함께 닫힌다', () => {
      const file = new File(['dummy'], 'license.png', { type: 'image/png' });
      const { rerender } = render(<BusinessLicenseUpload file={file} onFileSelect={vi.fn()} />);

      fireEvent.click(screen.getByRole('button', { name: '사업자등록증 이미지 크게 보기' }));
      expect(screen.getByRole('dialog')).not.toBeNull();

      rerender(<BusinessLicenseUpload file={null} onFileSelect={vi.fn()} />);
      expect(screen.queryByRole('dialog')).toBeNull();
    });
  });
});
