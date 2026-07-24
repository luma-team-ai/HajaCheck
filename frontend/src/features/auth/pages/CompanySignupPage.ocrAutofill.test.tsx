// @vitest-environment jsdom
// 사업자등록증 OCR 자동채움(#587) — 파일 선택 시 jpeg/png만 OCR을 호출해 3필드를 자동채움하고,
// 실패는 조용히 폴백(수동 입력 유지)하며, PDF는 OCR을 호출하지 않는지 검증한다.
// 실제 HTTP 라운드트립: companyAuth.mock.ts의 OCR 핸들러는 request.formData()를 파싱하지 않아
// msw+jsdom+undici 조합에서도 File 파트를 포함한 요청이 안정적으로 성공/실패 응답을 받는다
// (authApi.businessLicenseOcr.test.ts에서 동일 방식으로 검증됨).
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterAll, afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import type { ApiResponse } from '../../../shared/api/types';
import { authApi } from '../api/authApi';
import {
  MOCK_OCR_BUSINESS_NUMBER,
  MOCK_OCR_BUSINESS_START_DATE,
  MOCK_OCR_COMPANY_NAME,
  MOCK_OCR_REPRESENTATIVE_NAME,
  companyAuthHandlers,
} from '../mocks/companyAuth.mock';
import { CompanySignupPage } from './CompanySignupPage';

vi.mock('../hooks/useDaumPostcodeSearch', () => ({
  useDaumPostcodeSearch: () => ({
    openPostcodeSearch: (onComplete: (address: string) => void) => {
      onComplete('서울시 강남구 테헤란로 1');
    },
  }),
}));

const server = setupServer(...companyAuthHandlers);

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => {
  server.resetHandlers();
  cleanup();
  vi.restoreAllMocks();
});
afterAll(() => server.close());

function renderPage() {
  const queryClient = new QueryClient();
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/signup/company']}>
        <CompanySignupPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

function pngFile(name = 'license.png') {
  return new File(['dummy'], name, { type: 'image/png' });
}

function pdfFile(name = 'license.pdf') {
  return new File(['%PDF-1.4'], name, { type: 'application/pdf' });
}

// 실제 11MB 콘텐츠를 만들지 않고 size만 오버라이드 — 폼 검증(validateBusinessLicenseFile)이
// FILE_TOO_LARGE로 판정하기에 충분(P2 테스트 전용).
function oversizedPngFile(name = 'big-license.png') {
  const file = new File(['dummy'], name, { type: 'image/png' });
  Object.defineProperty(file, 'size', { value: 11 * 1024 * 1024 });
  return file;
}

describe('CompanySignupPage — 사업자등록증 OCR 자동채움(#587)', () => {
  it('PNG 업로드 시 OCR이 성공하면 사업자등록번호·상호명·대표자명·개업일자가 자동으로 채워진다(#600)', async () => {
    renderPage();

    fireEvent.change(screen.getByLabelText('사업자등록증'), { target: { files: [pngFile()] } });

    await waitFor(() => {
      expect((screen.getByLabelText('사업자등록번호') as HTMLInputElement).value).toBe(
        MOCK_OCR_BUSINESS_NUMBER,
      );
    });
    expect((screen.getByLabelText('상호명') as HTMLInputElement).value).toBe(MOCK_OCR_COMPANY_NAME);
    expect((screen.getByLabelText('대표자명') as HTMLInputElement).value).toBe(
      MOCK_OCR_REPRESENTATIVE_NAME,
    );
    expect((screen.getByLabelText('개업일자') as HTMLInputElement).value).toBe(
      MOCK_OCR_BUSINESS_START_DATE,
    );
  });

  it('OCR 호출 중 로딩 문구를 노출하고, 응답 후 사라진다', async () => {
    server.use(
      http.post('/api/auth/business-license/ocr', async () => {
        await new Promise((resolve) => setTimeout(resolve, 30));
        const success: ApiResponse<{
          businessRegistrationNumber: string | null;
          companyName: string | null;
          representativeName: string | null;
        }> = {
          success: true,
          data: {
            businessRegistrationNumber: MOCK_OCR_BUSINESS_NUMBER,
            companyName: MOCK_OCR_COMPANY_NAME,
            representativeName: MOCK_OCR_REPRESENTATIVE_NAME,
          },
        };
        return HttpResponse.json(success);
      }),
    );

    renderPage();
    fireEvent.change(screen.getByLabelText('사업자등록증'), { target: { files: [pngFile()] } });

    await waitFor(() => {
      expect(screen.getByRole('status')).not.toBeNull();
    });

    await waitFor(() => {
      expect(screen.queryByRole('status')).toBeNull();
    });
    expect((screen.getByLabelText('사업자등록번호') as HTMLInputElement).value).toBe(
      MOCK_OCR_BUSINESS_NUMBER,
    );
  });

  it('이미 입력된 필드는 OCR 결과로 덮어쓰지 않고, 빈 필드만 채운다', async () => {
    renderPage();

    fireEvent.change(screen.getByLabelText('사업자등록번호'), { target: { value: '9999999999' } });
    fireEvent.change(screen.getByLabelText('사업자등록증'), { target: { files: [pngFile()] } });

    await waitFor(() => {
      expect((screen.getByLabelText('상호명') as HTMLInputElement).value).toBe(MOCK_OCR_COMPANY_NAME);
    });
    // 사용자가 이미 입력한 사업자등록번호는 OCR 응답으로 덮어써지지 않는다.
    expect((screen.getByLabelText('사업자등록번호') as HTMLInputElement).value).toBe('9999999999');
  });

  // #600 — 개업일자도 기존 3필드와 동일한 "빈 필드만 채움" 규칙을 따라야 한다.
  it('이미 입력된 개업일자는 OCR 결과로 덮어쓰지 않는다(#600)', async () => {
    renderPage();

    fireEvent.change(screen.getByLabelText('개업일자'), { target: { value: '2020-01-01' } });
    fireEvent.change(screen.getByLabelText('사업자등록증'), { target: { files: [pngFile()] } });

    await waitFor(() => {
      expect((screen.getByLabelText('상호명') as HTMLInputElement).value).toBe(MOCK_OCR_COMPANY_NAME);
    });
    expect((screen.getByLabelText('개업일자') as HTMLInputElement).value).toBe('2020-01-01');
  });

  // #600 — OCR이 개업일자를 인식하지 못해 null을 주면 다른 필드는 채워지되 개업일자는
  // 채워지지 않고 빈 채로 남아 수기 입력을 유도해야 한다.
  it('OCR이 개업일자를 null로 주면 해당 필드는 채워지지 않는다(#600)', async () => {
    server.use(
      http.post('/api/auth/business-license/ocr', () => {
        const success: ApiResponse<{
          businessRegistrationNumber: string | null;
          companyName: string | null;
          representativeName: string | null;
          businessStartDate: string | null;
        }> = {
          success: true,
          data: {
            businessRegistrationNumber: MOCK_OCR_BUSINESS_NUMBER,
            companyName: MOCK_OCR_COMPANY_NAME,
            representativeName: MOCK_OCR_REPRESENTATIVE_NAME,
            businessStartDate: null,
          },
        };
        return HttpResponse.json(success);
      }),
    );

    renderPage();
    fireEvent.change(screen.getByLabelText('사업자등록증'), { target: { files: [pngFile()] } });

    await waitFor(() => {
      expect((screen.getByLabelText('사업자등록번호') as HTMLInputElement).value).toBe(
        MOCK_OCR_BUSINESS_NUMBER,
      );
    });
    expect((screen.getByLabelText('개업일자') as HTMLInputElement).value).toBe('');
  });

  it('OCR이 400으로 실패해도 에러 팝업 없이 조용히 폴백되고 수동 입력이 유지된다', async () => {
    server.use(
      http.post('/api/auth/business-license/ocr', () => {
        const failure: ApiResponse<null> = {
          success: false,
          data: null,
          error: { code: 'FILE_INVALID_TYPE', message: '지원하지 않는 파일 형식입니다.' },
        };
        return HttpResponse.json(failure, { status: 400 });
      }),
    );

    renderPage();
    fireEvent.change(screen.getByLabelText('사업자등록증'), { target: { files: [pngFile()] } });

    // 로딩 문구가 사라질 때까지(요청이 끝날 때까지) 기다린 뒤에도 필드는 그대로 빈 값이어야 한다.
    await waitFor(() => {
      expect(screen.queryByRole('status')).toBeNull();
    });
    expect((screen.getByLabelText('사업자등록번호') as HTMLInputElement).value).toBe('');
    expect((screen.getByLabelText('상호명') as HTMLInputElement).value).toBe('');
    expect((screen.getByLabelText('대표자명') as HTMLInputElement).value).toBe('');

    // 수동 입력은 그대로 가능해야 한다(가입을 막지 않음).
    fireEvent.change(screen.getByLabelText('사업자등록번호'), { target: { value: '1112223334' } });
    expect((screen.getByLabelText('사업자등록번호') as HTMLInputElement).value).toBe('1112223334');
  });

  it('OCR이 네트워크 오류로 실패해도 조용히 폴백된다', async () => {
    server.use(
      http.post('/api/auth/business-license/ocr', () => {
        return HttpResponse.error();
      }),
    );

    renderPage();
    fireEvent.change(screen.getByLabelText('사업자등록증'), { target: { files: [pngFile()] } });

    await waitFor(() => {
      expect(screen.queryByRole('status')).toBeNull();
    });
    expect((screen.getByLabelText('사업자등록번호') as HTMLInputElement).value).toBe('');
  });

  it('PDF 업로드 시 OCR을 호출하지 않는다(백엔드 미지원)', async () => {
    const ocrSpy = vi.spyOn(authApi, 'businessLicenseOcr');

    renderPage();
    fireEvent.change(screen.getByLabelText('사업자등록증'), { target: { files: [pdfFile()] } });

    // 비동기 호출이 없다는 것을 확인하기 위해 한 틱 기다린다.
    await new Promise((resolve) => setTimeout(resolve, 10));

    expect(ocrSpy).not.toHaveBeenCalled();
    expect((screen.getByLabelText('사업자등록번호') as HTMLInputElement).value).toBe('');
    expect(screen.queryByRole('status')).toBeNull();
  });

  // P2(리뷰어 픽스) — 오버사이즈/무효 파일은 제출 시 폼 검증에서 어차피 막히므로, 뻔히 실패할
  // OCR 요청으로 백엔드 rate-limit(분당+일일 캡)을 낭비하지 않는다.
  it('오버사이즈 PNG는 폼 검증에서 걸리는 파일이라 OCR을 호출하지 않는다(P2)', async () => {
    const ocrSpy = vi.spyOn(authApi, 'businessLicenseOcr');

    renderPage();
    fireEvent.change(screen.getByLabelText('사업자등록증'), {
      target: { files: [oversizedPngFile()] },
    });

    await new Promise((resolve) => setTimeout(resolve, 10));

    expect(ocrSpy).not.toHaveBeenCalled();
    expect((screen.getByLabelText('사업자등록번호') as HTMLInputElement).value).toBe('');
    expect(screen.queryByRole('status')).toBeNull();
  });

  // P1(리뷰어 픽스) — stale 응답 가드. 파일 선택 후 OCR 진행 중 파일을 삭제하면, 뒤늦게 도착한
  // 응답이 "첨부 파일 없는" 현재 상태에 값을 주입해서는 안 된다.
  it('OCR 진행 중 파일을 삭제하면, 응답이 늦게 도착해도 필드를 채우지 않는다(P1 stale 가드)', async () => {
    server.use(
      http.post('/api/auth/business-license/ocr', async () => {
        await new Promise((resolve) => setTimeout(resolve, 50));
        const success: ApiResponse<{
          businessRegistrationNumber: string | null;
          companyName: string | null;
          representativeName: string | null;
        }> = {
          success: true,
          data: {
            businessRegistrationNumber: MOCK_OCR_BUSINESS_NUMBER,
            companyName: MOCK_OCR_COMPANY_NAME,
            representativeName: MOCK_OCR_REPRESENTATIVE_NAME,
          },
        };
        return HttpResponse.json(success);
      }),
    );

    renderPage();
    fireEvent.change(screen.getByLabelText('사업자등록증'), { target: { files: [pngFile()] } });

    // 응답(50ms) 도착 전에 사용자가 첨부 파일을 삭제 — id가 증가해 이후 응답은 stale 처리되어야 한다.
    fireEvent.click(await screen.findByRole('button', { name: '첨부 파일 삭제' }));

    // 응답 지연(50ms)보다 넉넉히 기다린 뒤에도 필드가 채워지지 않아야 한다.
    await new Promise((resolve) => setTimeout(resolve, 120));

    expect((screen.getByLabelText('사업자등록번호') as HTMLInputElement).value).toBe('');
    expect((screen.getByLabelText('상호명') as HTMLInputElement).value).toBe('');
    expect((screen.getByLabelText('대표자명') as HTMLInputElement).value).toBe('');
    // 파일 자체도 삭제된 상태 그대로 유지(칩 미노출 = 삭제 버튼 사라짐)
    expect(screen.queryByRole('button', { name: '첨부 파일 삭제' })).toBeNull();
  });

  // P1(리뷰어 픽스) — A→B 빠른 교체 시 A의 응답이 늦게 도착해도 B의 응답만 반영되어야 한다.
  it('파일 A→B로 빠르게 교체하면, A의 늦은 응답은 무시되고 B의 응답만 반영된다(P1 stale 가드)', async () => {
    let callCount = 0;
    server.use(
      http.post('/api/auth/business-license/ocr', async () => {
        callCount += 1;
        const isFirstCall = callCount === 1;
        // A(첫 호출)는 느리게, B(두번째 호출)는 빠르게 응답 — B가 먼저 도착해도 이후 A가
        // 도착했을 때 무시되는지까지 함께 검증한다.
        await new Promise((resolve) => setTimeout(resolve, isFirstCall ? 80 : 10));
        const success: ApiResponse<{
          businessRegistrationNumber: string | null;
          companyName: string | null;
          representativeName: string | null;
        }> = {
          success: true,
          data: isFirstCall
            ? {
                businessRegistrationNumber: '1111111111',
                companyName: 'A상호(잘못된 값)',
                representativeName: 'A대표(잘못된 값)',
              }
            : {
                businessRegistrationNumber: MOCK_OCR_BUSINESS_NUMBER,
                companyName: MOCK_OCR_COMPANY_NAME,
                representativeName: MOCK_OCR_REPRESENTATIVE_NAME,
              },
        };
        return HttpResponse.json(success);
      }),
    );

    renderPage();
    const fileInput = screen.getByLabelText('사업자등록증');

    fireEvent.change(fileInput, { target: { files: [pngFile('a.png')] } }); // A 요청 발화(id=1)
    fireEvent.change(fileInput, { target: { files: [pngFile('b.png')] } }); // B 요청 발화(id=2), A는 아직 진행 중

    await waitFor(() => {
      expect((screen.getByLabelText('사업자등록번호') as HTMLInputElement).value).toBe(
        MOCK_OCR_BUSINESS_NUMBER,
      );
    });

    // A의 응답(80ms)이 도착할 시점까지 기다려도 A값('1111111111' 등)으로 덮어써지지 않아야 한다.
    await new Promise((resolve) => setTimeout(resolve, 60));
    expect((screen.getByLabelText('사업자등록번호') as HTMLInputElement).value).toBe(
      MOCK_OCR_BUSINESS_NUMBER,
    );
    expect((screen.getByLabelText('상호명') as HTMLInputElement).value).toBe(MOCK_OCR_COMPANY_NAME);
  });
});

describe('CompanySignupPage — OCR 결과 피드백·자동채움 배지(#748)', () => {
  it('OCR 성공(4필드 모두 채움) 시 "4개 항목이 자동입력됐어요" 피드백을 노출한다', async () => {
    renderPage();

    fireEvent.change(screen.getByLabelText('사업자등록증'), { target: { files: [pngFile()] } });

    await waitFor(() => {
      expect(screen.getByText('✓ 4개 항목이 자동입력됐어요')).not.toBeNull();
    });
  });

  it('OCR이 인식된 값을 하나도 주지 못하면 중립 안내를 노출한다', async () => {
    server.use(
      http.post('/api/auth/business-license/ocr', () => {
        const success: ApiResponse<{
          businessRegistrationNumber: string | null;
          companyName: string | null;
          representativeName: string | null;
          businessStartDate: string | null;
        }> = {
          success: true,
          data: {
            businessRegistrationNumber: null,
            companyName: null,
            representativeName: null,
            businessStartDate: null,
          },
        };
        return HttpResponse.json(success);
      }),
    );

    renderPage();
    fireEvent.change(screen.getByLabelText('사업자등록증'), { target: { files: [pngFile()] } });

    await waitFor(() => {
      expect(
        screen.getByText('인식된 정보가 없어요. 아래 항목을 직접 입력해 주세요'),
      ).not.toBeNull();
    });
  });

  it('OCR이 400으로 실패하면 인라인 실패 안내를 노출하되 폼 제출은 막지 않는다', async () => {
    server.use(
      http.post('/api/auth/business-license/ocr', () => {
        const failure: ApiResponse<null> = {
          success: false,
          data: null,
          error: { code: 'FILE_INVALID_TYPE', message: '지원하지 않는 파일 형식입니다.' },
        };
        return HttpResponse.json(failure, { status: 400 });
      }),
    );

    renderPage();
    fireEvent.change(screen.getByLabelText('사업자등록증'), { target: { files: [pngFile()] } });

    await waitFor(() => {
      expect(
        screen.getByText('자동인식에 실패했어요. 아래 항목을 직접 입력해 주세요'),
      ).not.toBeNull();
    });
    // 수동 입력은 여전히 가능해야 한다(가입을 막지 않음).
    fireEvent.change(screen.getByLabelText('사업자등록번호'), { target: { value: '1112223334' } });
    expect((screen.getByLabelText('사업자등록번호') as HTMLInputElement).value).toBe('1112223334');
  });

  it('새 파일을 다시 선택하면 이전 피드백이 초기화된다', async () => {
    renderPage();

    fireEvent.change(screen.getByLabelText('사업자등록증'), { target: { files: [pngFile()] } });
    await waitFor(() => {
      expect(screen.getByText('✓ 4개 항목이 자동입력됐어요')).not.toBeNull();
    });

    // PDF는 OCR을 호출하지 않으므로 재선택 직후 이전 피드백이 즉시 사라져야 한다.
    fireEvent.change(screen.getByLabelText('사업자등록증'), { target: { files: [pdfFile()] } });
    expect(screen.queryByText('✓ 4개 항목이 자동입력됐어요')).toBeNull();
  });

  it('PDF 업로드(OCR 미호출) 시에는 로딩·성공·실패 피드백이 전혀 뜨지 않는다', async () => {
    renderPage();

    fireEvent.change(screen.getByLabelText('사업자등록증'), { target: { files: [pdfFile()] } });
    await new Promise((resolve) => setTimeout(resolve, 10));

    expect(screen.queryByRole('status')).toBeNull();
    expect(screen.queryByText(/자동입력됐어요/)).toBeNull();
    expect(screen.queryByText(/인식된 정보가 없어요/)).toBeNull();
    expect(screen.queryByText(/자동인식에 실패했어요/)).toBeNull();
  });

  it('OCR로 채워진 필드에는 "자동인식" 배지가 붙고, 값을 직접 수정하면 배지가 사라진다', async () => {
    renderPage();

    fireEvent.change(screen.getByLabelText('사업자등록증'), { target: { files: [pngFile()] } });

    await waitFor(() => {
      expect((screen.getByLabelText('상호명') as HTMLInputElement).value).toBe(
        MOCK_OCR_COMPANY_NAME,
      );
    });
    expect(screen.getAllByText('자동인식')).toHaveLength(4);

    fireEvent.change(screen.getByLabelText('상호명'), { target: { value: '수정된 상호명' } });

    expect(screen.getAllByText('자동인식')).toHaveLength(3);
  });

  it('이미 값이 채워진 필드는 OCR로 새로 채워지지 않으므로 배지가 붙지 않는다', async () => {
    renderPage();

    fireEvent.change(screen.getByLabelText('사업자등록번호'), { target: { value: '9999999999' } });
    fireEvent.change(screen.getByLabelText('사업자등록증'), { target: { files: [pngFile()] } });

    await waitFor(() => {
      expect((screen.getByLabelText('상호명') as HTMLInputElement).value).toBe(
        MOCK_OCR_COMPANY_NAME,
      );
    });
    // 4필드 중 사용자가 이미 입력한 사업자등록번호를 제외한 3필드만 자동채움됐다.
    expect(screen.getAllByText('자동인식')).toHaveLength(3);
    expect(screen.getByText('✓ 3개 항목이 자동입력됐어요')).not.toBeNull();
  });

  // 리뷰어 P2 픽스 — OCR 왕복(수백 ms+) 동안 사용자가 필드를 수정하면, 판정(배지·카운트)이
  // "파일 선택 시점"이 아니라 "응답 도착 시점의 실제 write 결과"를 따라가야 한다.
  it('OCR 진행 중 빈 필드에 사용자가 직접 입력하면, 응답 도착 후에도 사용자 값이 유지되고 배지가 붙지 않는다(P2)', async () => {
    server.use(
      http.post('/api/auth/business-license/ocr', async () => {
        await new Promise((resolve) => setTimeout(resolve, 50));
        const success: ApiResponse<{
          businessRegistrationNumber: string | null;
          companyName: string | null;
          representativeName: string | null;
          businessStartDate: string | null;
        }> = {
          success: true,
          data: {
            businessRegistrationNumber: MOCK_OCR_BUSINESS_NUMBER,
            companyName: MOCK_OCR_COMPANY_NAME,
            representativeName: MOCK_OCR_REPRESENTATIVE_NAME,
            businessStartDate: MOCK_OCR_BUSINESS_START_DATE,
          },
        };
        return HttpResponse.json(success);
      }),
    );

    renderPage();
    fireEvent.change(screen.getByLabelText('사업자등록증'), { target: { files: [pngFile()] } });

    // 응답(50ms) 도착 전, 선택 시점엔 비어있던 상호명에 사용자가 직접 입력한다.
    fireEvent.change(screen.getByLabelText('상호명'), { target: { value: '사용자입력상호' } });

    // 응답이 도착할 시점까지 기다린다 — 사업자등록번호는 여전히 비어있으니 정상 자동채움된다.
    await waitFor(() => {
      expect((screen.getByLabelText('사업자등록번호') as HTMLInputElement).value).toBe(
        MOCK_OCR_BUSINESS_NUMBER,
      );
    });

    // 상호명은 응답 도착 시점에 이미 사용자 입력값이 있었으므로 OCR값으로 덮어써지지 않는다
    // (functional updater가 응답 시점의 최신 prev를 본다) — 그리고 배지도 붙지 않아야 한다.
    expect((screen.getByLabelText('상호명') as HTMLInputElement).value).toBe('사용자입력상호');
    expect(screen.getAllByText('자동인식')).toHaveLength(3); // 사업자등록번호·대표자명·개업일자만
    expect(screen.getByText('✓ 3개 항목이 자동입력됐어요')).not.toBeNull();
  });

  it('OCR 진행 중 이미 값이 있던 필드를 사용자가 비우면, 응답 도착 시 그 필드도 자동채움되고 배지가 붙는다(P2)', async () => {
    server.use(
      http.post('/api/auth/business-license/ocr', async () => {
        await new Promise((resolve) => setTimeout(resolve, 50));
        const success: ApiResponse<{
          businessRegistrationNumber: string | null;
          companyName: string | null;
          representativeName: string | null;
          businessStartDate: string | null;
        }> = {
          success: true,
          data: {
            businessRegistrationNumber: MOCK_OCR_BUSINESS_NUMBER,
            companyName: MOCK_OCR_COMPANY_NAME,
            representativeName: MOCK_OCR_REPRESENTATIVE_NAME,
            businessStartDate: MOCK_OCR_BUSINESS_START_DATE,
          },
        };
        return HttpResponse.json(success);
      }),
    );

    renderPage();
    // 대표자명을 선택 이전에 미리 채워둔다 — 선택 시점 판정만 봤다면 "이미 채워진 필드"라
    // newlyFilled에서 제외됐을 값이다.
    fireEvent.change(screen.getByLabelText('대표자명'), { target: { value: '임시대표' } });
    fireEvent.change(screen.getByLabelText('사업자등록증'), { target: { files: [pngFile()] } });

    // 응답 도착 전, 사용자가 대표자명을 다시 비운다 — 응답 시점엔 이 필드가 실제로 빈 상태다.
    fireEvent.change(screen.getByLabelText('대표자명'), { target: { value: '' } });

    await waitFor(() => {
      expect((screen.getByLabelText('대표자명') as HTMLInputElement).value).toBe(
        MOCK_OCR_REPRESENTATIVE_NAME,
      );
    });

    // 응답 시점의 실제 값(빈 문자열)을 기준으로 자동채움됐으므로 배지도 붙어야 한다.
    expect(screen.getAllByText('자동인식')).toHaveLength(4);
    expect(screen.getByText('✓ 4개 항목이 자동입력됐어요')).not.toBeNull();
  });
});
