// 가입 승인 대기 화면 전달용 세션 저장소 — PR머신 P3: opaque signupToken을 쿼리스트링(?token=)
// 으로 노출하면 URL/브라우저 히스토리/Referer 헤더로 유출될 수 있어 sessionStorage로 전환.
// 새로고침해도 승인 대기 화면 복원이 가능하고, 탭을 닫으면 자동 소거된다(localStorage 대비 안전).
const COMPANY_SIGNUP_SESSION_KEY = 'hajacheckCompanySignupSession';

export interface CompanySignupSession {
  signupToken: string;
  companyName: string;
  maskedEmail: string;
}

// 프라이빗 모드 등으로 storage 접근이 막혀도 크래시하지 않도록 접근 실패는 조용히 무시한다
// (savedLoginId.ts와 동일 패턴).
export function saveCompanySignupSession(session: CompanySignupSession): void {
  try {
    sessionStorage.setItem(COMPANY_SIGNUP_SESSION_KEY, JSON.stringify(session));
  } catch {
    // 저장 실패 무시 — 승인 대기 화면에 신청정보가 비어 보일 뿐, 가입 자체엔 영향 없음
  }
}

export function getCompanySignupSession(): CompanySignupSession | null {
  try {
    const raw = sessionStorage.getItem(COMPANY_SIGNUP_SESSION_KEY);
    if (!raw) return null;
    const parsed = JSON.parse(raw) as Partial<CompanySignupSession>;
    if (!parsed.signupToken) return null;
    return {
      signupToken: parsed.signupToken,
      companyName: parsed.companyName ?? '-',
      maskedEmail: parsed.maskedEmail ?? '-',
    };
  } catch {
    return null;
  }
}
