import { useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { Button } from '../../../shared/components/Button';
import { BusinessLicenseUpload } from '../components/BusinessLicenseUpload';
import { CompanyAddressField } from '../components/CompanyAddressField';
import { CompanySignupHeroPanel } from '../components/CompanySignupHeroPanel';
import { EmailDomainField } from '../components/EmailDomainField';
import { PasswordStrengthMeter } from '../components/PasswordStrengthMeter';
import { BUSINESS_LICENSE_OCR_SUPPORTED_TYPES, LOGIN_ROUTE } from '../constants';
import { PRIVACY_POLICY_ROUTE, TERMS_OF_SERVICE_ROUTE } from '../../policy/constants';
import {
  ERROR_CLASSES,
  INPUT_CLASSES,
  LABEL_CLASSES,
  PASSWORD_TOGGLE_CLASSES,
  SUCCESS_CLASSES,
} from '../formClasses';
import { useBusinessLicenseOcr } from '../hooks/useBusinessLicenseOcr';
import { useBusinessVerification } from '../hooks/useBusinessVerification';
import { useCompanySignup } from '../hooks/useCompanySignup';
import { useEmailAvailability } from '../hooks/useEmailAvailability';
import type { BusinessVerificationResult } from '../types';
import {
  doPasswordsMatch,
  getPasswordStrength,
  isValidBusinessNumber,
  isValidBusinessStartDate,
  isValidEmail,
  isValidPassword,
} from '../utils/authFormValidators';
import { validateBusinessLicenseFile } from '../utils/validateBusinessLicenseFile';
import { isCompanySignupFormValid } from '../utils/validateCompanySignupForm';

const ERROR_MESSAGES: Record<string, string> = {
  AUTH_EMAIL_DUPLICATED: '이미 가입된 이메일입니다.',
  AUTH_BUSINESS_NUMBER_DUPLICATED: '이미 등록된 사업자등록번호입니다.',
  FILE_REQUIRED: '사업자등록증 파일을 첨부해 주세요.',
  FILE_INVALID_TYPE: '지원하지 않는 파일 형식입니다. (JPG, PNG, PDF만 가능)',
  FILE_TOO_LARGE: '파일 용량이 너무 큽니다. (최대 10MB)',
  INVALID_INPUT: '입력값을 다시 확인해 주세요.',
  FILE_UPLOAD_FAILED: '파일 업로드에 실패했습니다. 잠시 후 다시 시도해 주세요.',
  // 사업자 진위확인(#663) rate limit — 전역 분당10/일300(#648)
  AUTH_TOO_MANY_REQUESTS: '잠시 후 다시 시도해 주세요.',
};
const DEFAULT_ERROR_MESSAGE = '가입 신청에 실패했습니다. 잠시 후 다시 시도해 주세요.';
const BUSINESS_VERIFICATION_DEFAULT_ERROR_MESSAGE =
  '진위확인에 실패했습니다. 잠시 후 다시 시도해 주세요.';
const BUSINESS_VERIFICATION_GATE_MESSAGE = '사업자 진위확인을 먼저 완료해 주세요.';

const INLINE_BTN_CLASSES =
  'shrink-0 cursor-pointer whitespace-nowrap rounded-lg border border-border bg-surface px-4 text-sm font-semibold text-text-default enabled:hover:bg-surface-muted disabled:cursor-not-allowed disabled:text-text-muted';

// 사업자 진위확인 결과 6종(#648) — 뱃지 문구는 서버 message를 그대로 쓰고, 아이콘/색만 result로 분기.
// VERIFIED만 성공색, UNAVAILABLE(국세청 장애 fail-open)은 중립/주의색, 나머지는 에러색.
const BUSINESS_VERIFICATION_BADGE_CLASSES: Record<BusinessVerificationResult, string> = {
  VERIFIED: SUCCESS_CLASSES,
  NOT_REGISTERED: ERROR_CLASSES,
  MISMATCH: ERROR_CLASSES,
  SUSPENDED: ERROR_CLASSES,
  CLOSED: ERROR_CLASSES,
  UNAVAILABLE: 'text-xs text-warning-soft-fg',
};
const BUSINESS_VERIFICATION_BADGE_ICONS: Record<BusinessVerificationResult, string> = {
  VERIFIED: '✅',
  NOT_REGISTERED: '❌',
  MISMATCH: '❌',
  SUSPENDED: '⚠️',
  CLOSED: '❌',
  UNAVAILABLE: '⏳',
};

export function CompanySignupPage() {
  const [emailLocal, setEmailLocal] = useState('');
  const [emailDomain, setEmailDomain] = useState('');
  const [isCustomDomain, setIsCustomDomain] = useState(true);
  // 직접입력 모드에서 마지막으로 입력한 도메인 값 — 프리셋으로 갔다가 다시 직접입력으로
  // 돌아올 때 emailDomain을 이 값으로 복원해 재입력 마찰을 없앤다(code-reviewer P2, #417).
  const [lastCustomDomain, setLastCustomDomain] = useState('');
  // EmailDomainField의 select onChange 한 번에서 onCustomModeChange→onDomainChange가 연달아
  // 호출되는데, 둘 다 같은 렌더의 클로저를 참조해 isCustomDomain state를 읽으면 stale 값이
  // 잡힌다(프리셋 선택 시 방금 false로 바뀐 모드를 아직 true로 봐서 lastCustomDomain을 프리셋
  // 값으로 덮어쓰는 버그). ref는 즉시(동기) 갱신되므로 handleEmailDomainChange가 항상 최신
  // 모드를 보도록 상태와 함께 유지한다.
  const isCustomDomainRef = useRef(true);
  const [password, setPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [isPasswordVisible, setIsPasswordVisible] = useState(false);
  const [isConfirmPasswordVisible, setIsConfirmPasswordVisible] = useState(false);
  const [companyName, setCompanyName] = useState('');
  const [businessRegistrationNumber, setBusinessRegistrationNumber] = useState('');
  const [representativeName, setRepresentativeName] = useState('');
  // 개업일자 — 국세청 진위확인(#596)이 요구하는 필수값. OCR 자동채움(#598)의 4번째 필드로도
  // 채워진다(#600). `<input type="date">` 값은 ISO `yyyy-MM-dd` 문자열 그대로 사용.
  const [businessStartDate, setBusinessStartDate] = useState('');
  const [address, setAddress] = useState('');
  const [addressDetail, setAddressDetail] = useState('');
  const [file, setFile] = useState<File | null>(null);
  const [agreeTermsOfService, setAgreeTermsOfService] = useState(false);
  const [agreePrivacyPolicy, setAgreePrivacyPolicy] = useState(false);
  const [showValidation, setShowValidation] = useState(false);

  const {
    checkEmailAvailability,
    isPending: isCheckingEmail,
    result: emailCheckResult,
    reset: resetEmailCheck,
  } = useEmailAvailability();
  const { signup, isPending, error } = useCompanySignup();
  const { runOcr, isPending: isOcrPending } = useBusinessLicenseOcr();
  const {
    verify: verifyBusiness,
    isPending: isVerifyingBusiness,
    result: businessVerificationResult,
    error: businessVerificationError,
    reset: resetBusinessVerification,
  } = useBusinessVerification();
  // 진위확인 in-flight 무효화 구멍 방지(PR #666 P2) — verify() 호출 시점의 3필드 스냅샷.
  // mutation이 pending인 동안(result·error 모두 undefined)엔 onChange의 reset()이 걸리지 않으므로,
  // "결과 존재 여부"만으로 게이트를 열면 그 창에서 필드를 바꾼 뒤 이전 값 기준 VERIFIED 응답이
  // 뒤늦게 와도 게이트가 열려버린다. isBusinessVerified는 결과뿐 아니라 "현재 3필드가 확인 시점
  // 스냅샷과 여전히 일치하는지"까지 함께 확인한다.
  const [verifiedSnapshot, setVerifiedSnapshot] = useState<{
    brn: string;
    name: string;
    startDate: string;
  } | null>(null);

  // 로컬파트 + '@' + 도메인 조합 — 기존 email 흐름(중복확인·검증·제출)이 이 파생값을 그대로
  // 사용한다(#417, EmailDomainField). 둘 다 비면 '@'만 남아 isValidEmail이 false로 판정한다.
  const email = `${emailLocal.trim()}@${emailDomain.trim()}`;

  const handleCheckEmail = () => {
    if (!isValidEmail(email)) return;
    checkEmailAvailability(email.trim());
  };

  // 이메일을 바꾸면 이전 중복확인 결과(stale)를 즉시 무효화 — A로 확인 후 B로 바꿔도
  // A의 "사용 가능" 결과가 남아 잘못된 메시지·제출 판정으로 이어지는 것 방지(PR머신 P2)
  const handleEmailLocalChange = (value: string) => {
    setEmailLocal(value);
    if (emailCheckResult) resetEmailCheck();
  };

  const handleEmailDomainChange = (value: string) => {
    setEmailDomain(value);
    // 직접입력 모드에서 친 값만 "마지막 커스텀 도메인"으로 보존 — 프리셋 선택으로 인한
    // onDomainChange(프리셋값) 호출은 커스텀 값을 덮어쓰면 안 된다. isCustomDomain state가
    // 아니라 ref를 봐야 같은 이벤트 내 onCustomModeChange(false) 직후에도 최신값을 읽는다.
    if (isCustomDomainRef.current) setLastCustomDomain(value);
    if (emailCheckResult) resetEmailCheck();
  };

  const handleEmailCustomModeChange = (isCustom: boolean) => {
    isCustomDomainRef.current = isCustom;
    setIsCustomDomain(isCustom);
    // 직접입력으로 재전환 시 빈 문자열이 아니라 마지막으로 입력했던 커스텀 도메인으로 복원.
    if (isCustom) setEmailDomain(lastCustomDomain);
    if (emailCheckResult) resetEmailCheck();
  };

  // 사업자 진위확인(#648 BE, #663 FE) — 개업일자 필드 아래 [진위확인] 버튼. 3필드(사업자등록번호·
  // 대표자명·개업일자)가 모두 유효해야 활성화(기존 검증 유틸 재사용, 새 규칙 추가 없음).
  const canVerifyBusiness =
    isValidBusinessNumber(businessRegistrationNumber) &&
    representativeName.trim().length > 0 &&
    isValidBusinessStartDate(businessStartDate);

  const handleVerifyBusiness = () => {
    if (!canVerifyBusiness) return;
    const trimmedRepresentativeName = representativeName.trim();
    setVerifiedSnapshot({
      brn: businessRegistrationNumber,
      name: trimmedRepresentativeName,
      startDate: businessStartDate,
    });
    verifyBusiness({
      businessRegistrationNumber,
      representativeName: trimmedRepresentativeName,
      businessStartDate,
    });
  };

  // 진위확인 우회 방지(무효화) — 확인에 쓰인 3필드 중 하나라도 바뀌면 이전 결과/에러를 즉시
  // 무효화한다(이메일의 resetEmailCheck 패턴과 동일). "확인 후 몰래 값만 바꿔 통과된 결과로
  // 제출"하는 경로를 막는다.
  const handleBusinessRegistrationNumberChange = (value: string) => {
    setBusinessRegistrationNumber(value);
    if (businessVerificationResult || businessVerificationError) resetBusinessVerification();
  };
  const handleRepresentativeNameChange = (value: string) => {
    setRepresentativeName(value);
    if (businessVerificationResult || businessVerificationError) resetBusinessVerification();
  };
  const handleBusinessStartDateChange = (value: string) => {
    setBusinessStartDate(value);
    if (businessVerificationResult || businessVerificationError) resetBusinessVerification();
  };

  // 진위확인 통과 = VERIFIED(일치) 또는 UNAVAILABLE(국세청 장애 fail-open, 백엔드도 PENDING 허용)
  // "이면서" 현재 3필드가 확인 시점 스냅샷과 여전히 일치할 때만이다(PR #666 P2 — in-flight 무효화
  // 구멍 방지). 나머지(미확인·미등록·불일치·휴폐업, 또는 확인 이후 필드가 바뀐 상태)는 막는다.
  const isBusinessVerified =
    (businessVerificationResult?.result === 'VERIFIED' ||
      businessVerificationResult?.result === 'UNAVAILABLE') &&
    verifiedSnapshot !== null &&
    verifiedSnapshot.brn === businessRegistrationNumber &&
    verifiedSnapshot.name === representativeName.trim() &&
    verifiedSnapshot.startDate === businessStartDate;

  // 사업자등록증 OCR 자동채움(#587) — jpeg/png만 백엔드 OCR이 지원하므로 그 외 타입(PDF 등)은
  // OCR 호출 자체를 생략한다. 실패(400/429/5xx/네트워크)는 onError를 등록하지 않아 조용히
  // 폴백되고(useBusinessLicenseOcr 참고), 성공 시에도 이미 사용자가 입력한 값(빈 문자열이
  // 아닌 필드)은 덮어쓰지 않는다 — 자동채움은 초기값 제공일 뿐 이후 자유롭게 수정 가능해야
  // 한다(요구사항 #587). 단, 이 규칙 때문에 파일을 다른 것으로 교체해도 이미 자동채움된
  // 값은 재덮어쓰기 되지 않는다 — 재인식이 필요하면 사용자가 해당 필드를 직접 비우고 다시
  // 채워야 한다(알려진 트레이드오프, 후속 개선은 별도 이슈로 분리 가능).
  //
  // stale 응답 가드(P1, 리뷰어 픽스) — 파일 A 선택 후 OCR 진행 중 파일을 삭제하거나 B로 빠르게
  // 교체하면, 뒤늦게 도착한 A의 OCR 응답이 "지금 선택과 무관한" 값을 필드에 주입할 수 있다.
  // 단조 증가 request-id로 "이 응답이 아직 최신 선택에 대한 것인지"를 onSuccess에서 확인하고,
  // 아니면 무시한다. 파일 삭제·미지원 타입 등 OCR을 호출하지 않는 경로도 id를 반드시 증가시켜야
  // 직전에 진행 중이던(아직 응답 안 온) OCR의 결과가 새 선택 상태에 적용되지 않는다.
  const ocrRequestIdRef = useRef(0);

  const handleFileSelect = (selected: File | null) => {
    setFile(selected);
    const requestId = ++ocrRequestIdRef.current;
    if (!selected) return;
    if (!BUSINESS_LICENSE_OCR_SUPPORTED_TYPES.includes(selected.type)) return;
    // rate-limit 낭비 방지(P2, 리뷰어 픽스) — 오버사이즈·무효 파일은 어차피 폼 검증에서 걸러지므로
    // 백엔드 OCR(분당+일일 캡)을 호출하지 않는다. 파일 자체는 그대로 state에 세팅해 기존 제출 시
    // 검증(validateBusinessLicenseFile) 흐름을 그대로 탄다.
    if (validateBusinessLicenseFile(selected) !== null) return;

    runOcr(selected, {
      onSuccess: (data) => {
        if (requestId !== ocrRequestIdRef.current) return; // stale 응답 — 이후 선택으로 이미 무효화됨

        // 진위확인 무효화 방어(#663) — OCR은 빈 필드만 채우므로 이미 진위확인을 통과한 상태(3필드
        // 모두 값이 참)에선 실질적으로 값이 바뀌지 않아 자연히 안전하다. 그래도 방어적으로, 이번
        // 자동채움이 진위확인 대상 3필드 중 하나라도 실제로 채울 예정이면(현재 비어있고 OCR값이
        // 있으면) 이전 결과를 무효화한다.
        const willFillVerifiedField =
          (!businessRegistrationNumber.trim() && !!data.businessRegistrationNumber) ||
          (!representativeName.trim() && !!data.representativeName) ||
          (!businessStartDate.trim() && !!data.businessStartDate);
        if (willFillVerifiedField && (businessVerificationResult || businessVerificationError)) {
          resetBusinessVerification();
        }

        setBusinessRegistrationNumber((prev) =>
          prev.trim() ? prev : (data.businessRegistrationNumber ?? prev),
        );
        setCompanyName((prev) => (prev.trim() ? prev : (data.companyName ?? prev)));
        setRepresentativeName((prev) => (prev.trim() ? prev : (data.representativeName ?? prev)));
        // 개업일자 자동채움(#598, #600) — 기존 3필드와 동일 규칙: 빈 필드만 채우고, OCR이 null이면
        // 건드리지 않는다(수기 입력 유지).
        setBusinessStartDate((prev) => (prev.trim() ? prev : (data.businessStartDate ?? prev)));
      },
    });
  };

  const handleSubmit = (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    setShowValidation(true);

    const form = {
      email,
      password,
      confirmPassword,
      companyName,
      businessRegistrationNumber,
      representativeName,
      businessStartDate,
      address,
      businessRegistrationFile: file,
      agreeTermsOfService,
      agreePrivacyPolicy,
    };
    if (!isCompanySignupFormValid(form)) return;
    if (emailCheckResult?.available === false) return;
    // 진위확인 게이트(#648, #663) — 미확인·미등록·불일치·휴폐업이면 제출 자체를 막는다.
    if (!isBusinessVerified) return;

    signup({
      email: email.trim(),
      password,
      companyName: companyName.trim(),
      businessRegistrationNumber,
      representativeName: representativeName.trim(),
      businessStartDate,
      address,
      addressDetail,
      agreeTermsOfService,
      agreePrivacyPolicy,
      businessRegistrationFile: file as File,
    });
  };

  const submitErrorMessage = error ? (ERROR_MESSAGES[error.code] ?? DEFAULT_ERROR_MESSAGE) : null;
  const fileError = showValidation ? validateBusinessLicenseFile(file) : null;

  return (
    <div className="flex min-h-screen bg-white">
      <CompanySignupHeroPanel />

      <section className="flex flex-1 justify-center overflow-y-auto px-6 py-14 sm:px-10 lg:px-16">
        {/* 오른쪽 입력 폼을 테두리 카드로 감싼다(#424) — 왼쪽 히어로(이미지)는 테두리 없음.
            내부 콘텐츠 폭 유지를 위해 max-w를 패딩만큼 넓힌다. */}
        <div className="h-fit w-full max-w-[504px] rounded-2xl border border-border bg-white p-8 shadow-sm">
          <p className="m-0 text-sm font-medium text-text-muted">기업 회원가입</p>
          <h1 className="m-0 mt-1.5 text-2xl font-bold text-heading">회사 계정을 만들어 주세요</h1>
          <p className="m-0 mt-2 text-sm text-text-muted">
            기업 승인 후 검수 워크스페이스가 활성화됩니다.
          </p>

          <form className="mt-8 flex flex-col gap-5" onSubmit={handleSubmit}>
            <div className="flex flex-col gap-1.5">
              <label className={LABEL_CLASSES} htmlFor="signup-email">
                아이디(이메일)
              </label>
              <EmailDomainField
                localPart={emailLocal}
                domain={emailDomain}
                isCustomDomain={isCustomDomain}
                onLocalPartChange={handleEmailLocalChange}
                onDomainChange={handleEmailDomainChange}
                onCustomModeChange={handleEmailCustomModeChange}
              />
              <button
                type="button"
                className={`${INLINE_BTN_CLASSES} self-start`}
                onClick={handleCheckEmail}
                disabled={isCheckingEmail || !isValidEmail(email)}
              >
                중복확인
              </button>
              {emailCheckResult && (
                <p className={emailCheckResult.available ? SUCCESS_CLASSES : ERROR_CLASSES}>
                  {emailCheckResult.available ? '사용 가능한 이메일입니다.' : '이미 가입된 이메일입니다.'}
                </p>
              )}
              {/* 실시간 인라인 검증(#424) — 입력 중(로컬파트·도메인 중 하나라도 입력)엔 즉시,
                  제출 시엔 빈 값도 안내. 조합 이메일은 항상 '@'를 포함해 length>0이므로
                  로컬파트/도메인 각각의 입력 여부로 판정한다(#417). */}
              {(emailLocal.length > 0 || emailDomain.length > 0 || showValidation) &&
                !isValidEmail(email) && <p className={ERROR_CLASSES}>올바른 이메일 형식을 입력해 주세요.</p>}
            </div>

            <div className="flex flex-col gap-1.5">
              <label className={LABEL_CLASSES} htmlFor="signup-password">
                비밀번호
              </label>
              <div className="relative flex items-center">
                <input
                  id="signup-password"
                  type={isPasswordVisible ? 'text' : 'password'}
                  className={`${INPUT_CLASSES} pr-11`}
                  value={password}
                  onChange={(event) => setPassword(event.target.value)}
                  autoComplete="new-password"
                  // 시안 문구는 "8자 이상"만 요구하나 실제 검증(isValidPassword)·에러 문구는
                  // "8자 이상 + 영문+숫자 포함"이라, 시안 그대로 두면 8자만 채우고 통과를 기대하게
                  // 된다(placeholder-검증 불일치, PR머신 P3) — 실제 규칙에 맞춰 정확성 우선(#292)
                  placeholder="8자 이상, 영문+숫자 포함"
                />
                <button
                  type="button"
                  className={PASSWORD_TOGGLE_CLASSES}
                  aria-label={isPasswordVisible ? '비밀번호 숨기기' : '비밀번호 표시'}
                  onClick={() => setIsPasswordVisible((prev) => !prev)}
                >
                  {isPasswordVisible ? '🙈' : '👁'}
                </button>
              </div>
              <PasswordStrengthMeter strength={getPasswordStrength(password)} />
              {/* 실시간 인라인 검증(#424) — 입력 중(비어있지 않음)엔 즉시, 제출 시엔 빈 값도 안내 */}
              {(password.length > 0 || showValidation) && !isValidPassword(password) && (
                <p className={ERROR_CLASSES}>8자 이상, 영문+숫자를 포함해 주세요.</p>
              )}
            </div>

            <div className="flex flex-col gap-1.5">
              <label className={LABEL_CLASSES} htmlFor="signup-confirm-password">
                비밀번호 재입력
              </label>
              <div className="relative flex items-center">
                <input
                  id="signup-confirm-password"
                  type={isConfirmPasswordVisible ? 'text' : 'password'}
                  className={`${INPUT_CLASSES} pr-11`}
                  value={confirmPassword}
                  onChange={(event) => setConfirmPassword(event.target.value)}
                  autoComplete="new-password"
                  placeholder="비밀번호를 다시 입력해 주세요"
                />
                <button
                  type="button"
                  className={PASSWORD_TOGGLE_CLASSES}
                  aria-label={isConfirmPasswordVisible ? '비밀번호 숨기기' : '비밀번호 표시'}
                  onClick={() => setIsConfirmPasswordVisible((prev) => !prev)}
                >
                  {isConfirmPasswordVisible ? '🙈' : '👁'}
                </button>
              </div>
              {confirmPassword && (
                <p className={doPasswordsMatch(password, confirmPassword) ? SUCCESS_CLASSES : ERROR_CLASSES}>
                  {doPasswordsMatch(password, confirmPassword)
                    ? '✓ 비밀번호가 일치합니다'
                    : '비밀번호가 일치하지 않습니다.'}
                </p>
              )}
            </div>

            <CompanyAddressField
              address={address}
              addressDetail={addressDetail}
              onAddressChange={setAddress}
              onAddressDetailChange={setAddressDetail}
            />

            {/* 사업자등록증 블록 — 시안 기준 파일업로드 + 사업자정보 4필드(개업일자 포함, #600)를
                카드 하나로 그룹핑(#292) */}
            <div className="flex flex-col gap-4 rounded-xl border border-border p-4">
              <BusinessLicenseUpload
                file={file}
                onFileSelect={handleFileSelect}
                errorMessage={fileError ? ERROR_MESSAGES[fileError] : null}
                isOcrLoading={isOcrPending}
              />

              <div className="flex flex-col gap-1.5">
                <label className={LABEL_CLASSES} htmlFor="signup-business-number">
                  사업자등록번호
                </label>
                <input
                  id="signup-business-number"
                  type="text"
                  className={INPUT_CLASSES}
                  value={businessRegistrationNumber}
                  onChange={(event) => handleBusinessRegistrationNumberChange(event.target.value)}
                  placeholder="000-00-00000"
                />
                {showValidation && !isValidBusinessNumber(businessRegistrationNumber) && (
                  <p className={ERROR_CLASSES}>사업자등록번호 10자리를 입력해 주세요.</p>
                )}
              </div>

              <div className="flex flex-col gap-1.5">
                <label className={LABEL_CLASSES} htmlFor="signup-company-name">
                  상호명
                </label>
                <input
                  id="signup-company-name"
                  type="text"
                  className={INPUT_CLASSES}
                  value={companyName}
                  onChange={(event) => setCompanyName(event.target.value)}
                />
              </div>

              <div className="flex flex-col gap-1.5">
                <label className={LABEL_CLASSES} htmlFor="signup-representative-name">
                  대표자명
                </label>
                <input
                  id="signup-representative-name"
                  type="text"
                  className={INPUT_CLASSES}
                  value={representativeName}
                  onChange={(event) => handleRepresentativeNameChange(event.target.value)}
                />
              </div>

              <div className="flex flex-col gap-1.5">
                <label className={LABEL_CLASSES} htmlFor="signup-business-start-date">
                  개업일자
                </label>
                <input
                  id="signup-business-start-date"
                  type="date"
                  className={INPUT_CLASSES}
                  value={businessStartDate}
                  onChange={(event) => handleBusinessStartDateChange(event.target.value)}
                />
                {(businessStartDate.length > 0 || showValidation) &&
                  !isValidBusinessStartDate(businessStartDate) && (
                    <p className={ERROR_CLASSES}>
                      {businessStartDate.length > 0
                        ? '개업일자는 오늘 이전이어야 합니다.'
                        : '개업일자를 입력해 주세요.'}
                    </p>
                  )}
              </div>

              {/* 사업자 진위확인(#648 BE, #663 FE) — 제출 전 [진위확인] 버튼 + 결과 뱃지.
                  이메일 중복확인 버튼(INLINE_BTN_CLASSES)과 시각적으로 일관되게 재사용. */}
              <div className="flex flex-col gap-1.5">
                <button
                  type="button"
                  className={`${INLINE_BTN_CLASSES} self-start`}
                  onClick={handleVerifyBusiness}
                  disabled={isVerifyingBusiness || !canVerifyBusiness}
                >
                  진위확인
                </button>
                {businessVerificationResult && (
                  <p className={BUSINESS_VERIFICATION_BADGE_CLASSES[businessVerificationResult.result]}>
                    {BUSINESS_VERIFICATION_BADGE_ICONS[businessVerificationResult.result]}{' '}
                    {businessVerificationResult.message}
                  </p>
                )}
                {businessVerificationError && (
                  <p className={ERROR_CLASSES}>
                    {ERROR_MESSAGES[businessVerificationError.code] ??
                      BUSINESS_VERIFICATION_DEFAULT_ERROR_MESSAGE}
                  </p>
                )}
                {/* 실패 계열 뱃지(NOT_REGISTERED 등)나 에러가 이미 떠 있으면 게이트 문구와
                    중복되므로 "결과·에러가 전혀 없을 때"(=아예 확인을 시도한 적 없을 때)만
                    노출한다(PR #666 P3). */}
                {showValidation &&
                  !isBusinessVerified &&
                  !businessVerificationResult &&
                  !businessVerificationError && (
                    <p className={ERROR_CLASSES}>{BUSINESS_VERIFICATION_GATE_MESSAGE}</p>
                  )}
              </div>
            </div>

            {/* 이용약관 동의·개인정보 수집·이용 동의는 개인정보보호법상 별도 동의 대상이라 체크박스를
                분리한다(PR머신 P2) — 시안은 1개 통합 체크박스이나 법적 요건상 2개 분리를 유지한다(#292) */}
            <div className="flex flex-col gap-1.5">
              <label className="flex cursor-pointer items-center gap-2 text-sm text-text-default">
                <input
                  type="checkbox"
                  checked={agreeTermsOfService}
                  onChange={(event) => setAgreeTermsOfService(event.target.checked)}
                />
                <span>
                  (필수){' '}
                  <Link
                    to={TERMS_OF_SERVICE_ROUTE}
                    target="_blank"
                    rel="noopener noreferrer"
                    className="font-medium text-heading underline"
                  >
                    이용약관
                  </Link>
                  에 동의합니다.
                </span>
              </label>
              {showValidation && !agreeTermsOfService && (
                <p className={ERROR_CLASSES}>이용약관에 동의해야 가입할 수 있습니다.</p>
              )}
            </div>

            <div className="flex flex-col gap-1.5">
              <label className="flex cursor-pointer items-center gap-2 text-sm text-text-default">
                <input
                  type="checkbox"
                  checked={agreePrivacyPolicy}
                  onChange={(event) => setAgreePrivacyPolicy(event.target.checked)}
                />
                <span>
                  (필수){' '}
                  <Link
                    to={PRIVACY_POLICY_ROUTE}
                    target="_blank"
                    rel="noopener noreferrer"
                    className="font-medium text-heading underline"
                  >
                    개인정보 수집 및 이용
                  </Link>
                  에 동의합니다.
                </span>
              </label>
              {showValidation && !agreePrivacyPolicy && (
                <p className={ERROR_CLASSES}>개인정보 수집·이용에 동의해야 가입할 수 있습니다.</p>
              )}
            </div>

            {submitErrorMessage && <p className={ERROR_CLASSES}>{submitErrorMessage}</p>}

            <Button
              type="submit"
              size="lg"
              className="w-full"
              disabled={isPending || (showValidation && !isBusinessVerified)}
            >
              {isPending ? '신청 중...' : '가입 신청하기'}
            </Button>

            <p className="m-0 text-center text-sm text-text-muted">
              이미 계정이 있으신가요?{' '}
              <Link to={LOGIN_ROUTE} className="font-medium text-heading underline">
                로그인
              </Link>
            </p>
          </form>
        </div>
      </section>
    </div>
  );
}
