import { useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import brandLogo from '../../../assets/brand/sidenav-brand-logo.png';
import { Button } from '../../../shared/components/Button';
import {
  BusinessLicenseUpload,
  type OcrFeedbackState,
} from '../components/BusinessLicenseUpload';
import { CompanyAddressField } from '../components/CompanyAddressField';
import { CompanySignupHeroPanel } from '../components/CompanySignupHeroPanel';
import { EmailDomainField } from '../components/EmailDomainField';
import { PasswordStrengthMeter } from '../components/PasswordStrengthMeter';
import { BUSINESS_LICENSE_OCR_SUPPORTED_TYPES, LANDING_ROUTE, LOGIN_ROUTE } from '../constants';
import { PRIVACY_POLICY_ROUTE, TERMS_OF_SERVICE_ROUTE } from '../../policy/constants';
import {
  AUTO_FILLED_BADGE_CLASSES,
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
import type { ApiError } from '../../../shared/api/types';
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
// 판정 불가 에러 게이트 통과(#910) — 국세청 전면 장애 중 rate-limit(#648, 전역 분당10/일300,
// 실패 요청도 카운터 차감)이 소진되면 그날 전원 가입이 완전히 막힌다. UNAVAILABLE(국세청 자체
// 장애 응답)은 이미 fail-open으로 통과시키는데, "국세청 응답을 아예 받지 못해 판정 불가"인
// 나머지 경우(429/5xx/408·499 같은 타임아웃/네트워크 오류)도 같은 결론이어야 한다는 게 #596에서
// 확립된 정책이다. 메시지 문자열 매칭은 하지 않는다(전역 컨벤션: 프론트는 status/code로 분기).
const WARNING_SOFT_CLASSES = 'text-xs text-warning-soft-fg';
const BUSINESS_VERIFICATION_ERROR_BYPASS_MESSAGE =
  '지금 국세청 확인이 어려워 확인 없이 진행합니다.';

// PR #912 P3 픽스 — 화이트리스트(429/5xx/네트워크만 통과)에서 블랙리스트(확정 차단 사유만 열거,
// 나머지는 전부 통과)로 뒤집는다. 정책 의도는 "국세청 응답을 못 받아 판정 불가면 통과"이지,
// "정해진 몇 가지 상태코드만 통과"가 아니다. 타임아웃이 408/499처럼 4xx로 표면화되는 경우
// status>=500 조건에 걸리지 않아 화이트리스트로는 게이트가 부당하게 닫혔다(원래 이슈와 동일한
// 증상 재발). 반대로 확정적으로 차단해야 하는 사유는 두 가지뿐이다 — ①입력값 자체가 잘못된
// 400 INVALID_INPUT, ②인증·인가 실패(401/403, 이 요청 자체가 정당한 사용자로 인증되지 않았다는
// 뜻이라 "국세청 판정 불가"와 무관한 별개 사유). 이 둘 다 국세청에 물어볼 필요조차 없이 요청
// 자체가 무효라는 확정 신호이므로 계속 차단한다. 404는 "존재하지 않는 사업자"가 아니라 검증
// API 자체가 없다는 뜻이라(그런 도메인 응답은 result 필드로 내려오지, HTTP 404가 아니다) 배포
// 사고 등으로 엔드포인트가 사라진 경우를 뜻할 가능성이 높다 — 이는 사용자 책임이 아니고 국세청
// 판정과도 무관하므로, "국세청 응답을 받지 못한 판정 불가" 쪽으로 분류해 통과시킨다(블랙리스트
// 미포함 = 기본 통과).
function isVerificationErrorGateBypassable(error: ApiError): boolean {
  if (error.code === 'INVALID_INPUT') return false; // 400 입력값 자체 오류 — 확정적 차단
  if (error.status === 401 || error.status === 403) return false; // 인증·인가 실패 — 확정적 차단
  return true; // 그 외 전부(429/5xx/408·499 타임아웃/네트워크 오류/404 등) — 판정 불가로 통과
}

// 자동채움 필드 배지(#748) — OCR이 실제로 값을 주입한 필드만 추적한다(진위확인 3필드 + 상호명).
type AutoFilledFieldKey = 'brn' | 'companyName' | 'representativeName' | 'startDate';

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
  UNAVAILABLE: WARNING_SOFT_CLASSES,
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
  // OCR 자동채움 판정용 ref(#748, 리뷰어 P2 픽스) — isCustomDomainRef와 동일한 이유: OCR
  // onSuccess는 파일 선택 시점 렌더의 클로저를 캡처하는데, OCR 왕복(수백 ms+) 동안 사용자가
  // 이 4필드를 직접 수정할 수 있다. state 대신 ref를 "현재 비어있는지" 판정의 유일한 진실
  // 소스로 삼아 각 필드의 onChange·OCR 자동채움 두 지점 모두에서 setState와 함께 동기 갱신한다
  // (React state updater를 이용한 "지연 실행 시점에만 안전한" 방식은 실제로는 신뢰할 수 없음—
  // 같은 콜백 안에서 여러 setState를 연달아 호출하면 첫 번째 이후는 React 내부 최적화(eager
  // bailout)가 비활성화되어 updater가 즉시 실행되지 않을 수 있다는 게 실측으로 확인됨).
  const businessRegistrationNumberRef = useRef('');
  const companyNameRef = useRef('');
  const representativeNameRef = useRef('');
  const businessStartDateRef = useRef('');
  const [address, setAddress] = useState('');
  const [addressDetail, setAddressDetail] = useState('');
  const [file, setFile] = useState<File | null>(null);
  const [agreeTermsOfService, setAgreeTermsOfService] = useState(false);
  const [agreePrivacyPolicy, setAgreePrivacyPolicy] = useState(false);
  const [showValidation, setShowValidation] = useState(false);
  // OCR 성공/실패 결과 피드백(#748) — BusinessLicenseUpload는 API를 모르므로 이 페이지가 판단해
  // prop으로 내려준다. 새 파일 선택 시 이전 피드백은 초기화한다(handleFileSelect 진입 시 리셋).
  const [ocrFeedback, setOcrFeedback] = useState<OcrFeedbackState | null>(null);
  // 자동채움 필드 배지(#748) — OCR로 실제 채운 필드만 담는다. 사용자가 해당 필드를 직접 수정하면
  // 제거한다(각 필드 onChange 핸들러 참고).
  const [autoFilledFields, setAutoFilledFields] = useState<Set<AutoFilledFieldKey>>(new Set());
  // 이미지 교체 시 갱신 판정용 ref(#879) — OCR onSuccess 콜백은 파일 선택 시점 렌더의 클로저를
  // 캡처하므로, autoFilledFields state를 그대로 읽으면 OCR 왕복 동안 사용자가 필드를 수정해
  // Set이 바뀐 경우(클로저 stale) 오판정한다. businessRegistrationNumberRef 등과 동일한 이유로,
  // "이 필드가 지금 자동채움 상태인지"의 유일한 진실 소스로 ref를 두고 state와 항상 동기 갱신한다.
  const autoFilledFieldsRef = useRef<Set<AutoFilledFieldKey>>(new Set());

  const {
    checkEmailAvailability,
    isPending: isCheckingEmail,
    result: emailCheckResult,
    reset: resetEmailCheck,
  } = useEmailAvailability();
  const { signup, isPending, error } = useCompanySignup();
  const { runOcr, isPending: isOcrPending, reset: resetOcr } = useBusinessLicenseOcr();
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

  // 자동채움 배지 해제(#748) — 사용자가 OCR로 채워진 필드를 직접 수정하면 그 필드는 더 이상
  // "자동인식" 값이 아니므로 배지를 뗀다. 이미 배지가 없는 필드면 상태 갱신을 생략한다.
  const clearAutoFilledField = (key: AutoFilledFieldKey) => {
    if (!autoFilledFieldsRef.current.has(key)) return;
    const next = new Set(autoFilledFieldsRef.current);
    next.delete(key);
    autoFilledFieldsRef.current = next;
    setAutoFilledFields(next);
  };

  // 진위확인 우회 방지(무효화) — 확인에 쓰인 3필드 중 하나라도 바뀌면 이전 결과/에러를 즉시
  // 무효화한다(이메일의 resetEmailCheck 패턴과 동일). "확인 후 몰래 값만 바꿔 통과된 결과로
  // 제출"하는 경로를 막는다.
  const handleBusinessRegistrationNumberChange = (value: string) => {
    businessRegistrationNumberRef.current = value;
    setBusinessRegistrationNumber(value);
    clearAutoFilledField('brn');
    if (businessVerificationResult || businessVerificationError) resetBusinessVerification();
  };
  const handleCompanyNameChange = (value: string) => {
    companyNameRef.current = value;
    setCompanyName(value);
    clearAutoFilledField('companyName');
  };
  const handleRepresentativeNameChange = (value: string) => {
    representativeNameRef.current = value;
    setRepresentativeName(value);
    clearAutoFilledField('representativeName');
    if (businessVerificationResult || businessVerificationError) resetBusinessVerification();
  };
  const handleBusinessStartDateChange = (value: string) => {
    businessStartDateRef.current = value;
    setBusinessStartDate(value);
    clearAutoFilledField('startDate');
    if (businessVerificationResult || businessVerificationError) resetBusinessVerification();
  };

  // 판정 불가 에러(429/5xx/네트워크 오류)도 UNAVAILABLE과 동일하게 게이트를 통과시킨다(#910).
  // 400 INVALID_INPUT은 확정적 차단 대상이라 isVerificationErrorGateBypassable이 false를 반환한다.
  const isBusinessVerificationErrorBypassable = businessVerificationError
    ? isVerificationErrorGateBypassable(businessVerificationError)
    : false;

  // 진위확인 통과 = VERIFIED(일치) 또는 UNAVAILABLE(국세청 장애 fail-open, 백엔드도 PENDING 허용)
  // 또는 판정 불가 에러(429/5xx/네트워크 오류, #910) "이면서" 현재 3필드가 확인 시점 스냅샷과
  // 여전히 일치할 때만이다(PR #666 P2 — in-flight 무효화 구멍 방지). 나머지(미확인·미등록·불일치·
  // 휴폐업·400 입력오류, 또는 확인 이후 필드가 바뀐 상태)는 막는다.
  const isBusinessVerified =
    (businessVerificationResult?.result === 'VERIFIED' ||
      businessVerificationResult?.result === 'UNAVAILABLE' ||
      isBusinessVerificationErrorBypassable) &&
    verifiedSnapshot !== null &&
    verifiedSnapshot.brn === businessRegistrationNumber &&
    verifiedSnapshot.name === representativeName.trim() &&
    verifiedSnapshot.startDate === businessStartDate;

  // 사업자등록증 OCR 자동채움(#587) — jpeg/png만 백엔드 OCR이 지원하므로 그 외 타입(PDF 등)은
  // OCR 호출 자체를 생략한다. 실패(400/429/5xx/네트워크)는 onError를 등록하지 않아 조용히
  // 폴백되고(useBusinessLicenseOcr 참고), 성공 시에도 사용자가 직접 입력·수정한 값(=
  // autoFilledFields에 없고 비어있지도 않은 필드)은 절대 덮어쓰지 않는다 — 자동채움은 초기값
  // 제공일 뿐 이후 자유롭게 수정 가능해야 한다(요구사항 #587).
  //
  // 이미지 교체 시 갱신(#879) — 각 필드의 채움 조건은 "값이 비어 있음" 또는 "그 필드가 여전히
  // autoFilledFields에 있음(OCR이 채웠고 이후 사용자가 직접 수정하지 않음)"으로 판정한다.
  // 즉 이미지를 다른 것으로 교체하면 이전 OCR이 채운 필드는 새 OCR 결과로 갱신되고, 사용자가
  // 직접 수정한 필드는 그대로 유지된다(과거엔 "빈 필드만 채움"이라 재교체해도 갱신되지
  // 않는 트레이드오프가 있었으나 이번 이슈로 해소). 자동채움 배지(#748, autoFilledFields)도
  // 새 파일 선택 시 초기화하지 않는다 — 갱신된 필드는 계속 배지가 붙어 있어야 하고, 값이 그대로
  // 유지된 필드도 이미 배지가 있는 상태 그대로 정합이 맞다.
  //
  // stale 응답 가드(P1, 리뷰어 픽스) — 파일 A 선택 후 OCR 진행 중 파일을 삭제하거나 B로 빠르게
  // 교체하면, 뒤늦게 도착한 A의 OCR 응답이 "지금 선택과 무관한" 값을 필드에 주입할 수 있다.
  // 단조 증가 request-id로 "이 응답이 아직 최신 선택에 대한 것인지"를 onSuccess에서 확인하고,
  // 아니면 무시한다. 파일 삭제·미지원 타입 등 OCR을 호출하지 않는 경로도 id를 반드시 증가시켜야
  // 직전에 진행 중이던(아직 응답 안 온) OCR의 결과가 새 선택 상태에 적용되지 않는다.
  const ocrRequestIdRef = useRef(0);

  const handleFileSelect = (selected: File | null) => {
    setFile(selected);
    // 결과 피드백 초기화(#748) — 새 파일을 다시 선택하면 이전 성공/실패 피드백은 더 이상
    // 유효하지 않다. 아래에서 OCR을 호출하지 않는 경로(파일 삭제·미지원 타입·오버사이즈)는
    // 이 초기화 이후 아무것도 설정하지 않으므로 피드백이 뜨지 않는다(의도된 동작).
    setOcrFeedback(null);
    // mutation 상태 초기화(#748, 리뷰어 P3) — 이전 호출의 data/error를 지워 다음 runOcr가
    // 깨끗한 mutation 상태에서 시작하게 한다(ocrFeedback과 함께 "새 파일 = 새로 시작").
    resetOcr();
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

        // 실제 채운 필드 판정(#748, 리뷰어 P2 픽스) — OCR 왕복(수백 ms+) 동안 사용자가 필드를
        // 수정할 수 있어, 파일 선택 시점 렌더의 클로저 값(businessRegistrationNumber 등)을 직접
        // 읽으면 응답 도착 시점의 실제 값과 어긋난다. setState functional updater 안에서
        // "prev"를 판정에 쓰는 방식도 신뢰할 수 없다 — 같은 콜백에서 setXxx를 여러 번 연달아
        // 호출하면 첫 호출 이후로는 React의 eager state 최적화가 비활성화돼 updater가 이
        // 콜백이 끝나기 전에 실행된다는 보장이 없다(실측 확인: 뒤쪽 updater들이 지연 실행돼
        // 판정용 accumulator가 항상 false로 읽힘). 대신 각 필드 onChange에서 setState와 함께
        // 동기 갱신해 온 ref(businessRegistrationNumberRef 등)를 "현재 값"의 유일한 진실
        // 소스로 삼는다 — 판정과 실제 write가 항상 같은 값을 본다. 이미지 교체 갱신(#879)
        // 판정도 같은 이유로 autoFilledFields state 대신 autoFilledFieldsRef를 쓴다.
        const filled: Record<AutoFilledFieldKey, boolean> = {
          brn: false,
          companyName: false,
          representativeName: false,
          startDate: false,
        };

        // 각 필드 채움 조건(#879) — "값이 비어 있음" 또는 "OCR이 채웠고 아직 사용자가 직접
        // 수정하지 않음(autoFilledFieldsRef에 있음)"일 때만 새 OCR 값으로 쓴다. 값이 실제로
        // 달라질 때만 write해 no-op 갱신(같은 값 재주입)을 filledCount·진위확인 무효화 판정에
        // 잡히지 않게 한다.
        if (
          (!businessRegistrationNumberRef.current.trim() ||
            autoFilledFieldsRef.current.has('brn')) &&
          data.businessRegistrationNumber &&
          data.businessRegistrationNumber !== businessRegistrationNumberRef.current
        ) {
          filled.brn = true;
          businessRegistrationNumberRef.current = data.businessRegistrationNumber;
          setBusinessRegistrationNumber(data.businessRegistrationNumber);
        }
        if (
          (!companyNameRef.current.trim() || autoFilledFieldsRef.current.has('companyName')) &&
          data.companyName &&
          data.companyName !== companyNameRef.current
        ) {
          filled.companyName = true;
          companyNameRef.current = data.companyName;
          setCompanyName(data.companyName);
        }
        if (
          (!representativeNameRef.current.trim() ||
            autoFilledFieldsRef.current.has('representativeName')) &&
          data.representativeName &&
          data.representativeName !== representativeNameRef.current
        ) {
          filled.representativeName = true;
          representativeNameRef.current = data.representativeName;
          setRepresentativeName(data.representativeName);
        }
        // 개업일자 자동채움(#598, #600) — 기존 3필드와 동일 규칙(#879로 갱신 조건 확장).
        // OCR이 null이면 건드리지 않는다(수기 입력·기존 자동채움 값 유지).
        if (
          (!businessStartDateRef.current.trim() ||
            autoFilledFieldsRef.current.has('startDate')) &&
          data.businessStartDate &&
          data.businessStartDate !== businessStartDateRef.current
        ) {
          filled.startDate = true;
          businessStartDateRef.current = data.businessStartDate;
          setBusinessStartDate(data.businessStartDate);
        }

        const newlyFilledKeys = (Object.keys(filled) as AutoFilledFieldKey[]).filter(
          (key) => filled[key],
        );

        // 진위확인 무효화(#663, #879로 조건 확장) — 위 filled.* 는 "실제로 값이 달라졌을 때만"
        // true가 되므로(no-op 재주입은 이미 걸러짐), 진위확인 대상 3필드(brn·representativeName·
        // startDate) 중 하나라도 이번에 실제로 값이 바뀌었으면 이전 결과를 무효화한다. 같은 값으로
        // 다시 채워졌을 뿐이면(filled=false) 불필요한 리셋을 하지 않는다.
        const willFillVerifiedField =
          filled.brn || filled.representativeName || filled.startDate;
        if (willFillVerifiedField && (businessVerificationResult || businessVerificationError)) {
          resetBusinessVerification();
        }

        if (newlyFilledKeys.length > 0) {
          const nextAutoFilledFields = new Set(autoFilledFieldsRef.current);
          newlyFilledKeys.forEach((key) => nextAutoFilledFields.add(key));
          autoFilledFieldsRef.current = nextAutoFilledFields;
          setAutoFilledFields(nextAutoFilledFields);
        }
        setOcrFeedback({
          status: newlyFilledKeys.length > 0 ? 'success' : 'empty',
          filledCount: newlyFilledKeys.length,
        });
      },
      onError: () => {
        // 실패 인라인 피드백(#748) — 보조 기능 원칙(useBusinessLicenseOcr) 유지: 폼 제출을
        // 막지 않고 콘솔 에러·팝업 없이 인라인 안내만 노출한다. stale 응답이면 무시.
        if (requestId !== ocrRequestIdRef.current) return;
        setOcrFeedback({ status: 'error', filledCount: 0 });
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
          {/* lg 미만(1024px 미만)에서는 CompanySignupHeroPanel 전체가 hidden이라, 그 안에만
              있는 브랜드 로고(홈 진입점)가 화면에서 완전히 사라진다(#720, LoginPage와 동일
              패턴). 폼 컨테이너 상단에 동일 로고를 별도로 렌더하되 lg 이상에서는 숨겨 데스크톱
              시안과 동일하게 유지한다. 우측은 흰 배경이라 hero 패널의 흰색 반전(brightness-0
              invert)은 쓰지 않는다 — 그건 어두운 hero 배경 전용이라 흰 배경에서는 로고가
              보이지 않게 된다. */}
          <div className="mb-6 flex justify-center lg:hidden" data-testid="mobile-brand-logo">
            <Link to={LANDING_ROUTE} className="w-fit" aria-label="HajaCheck 홈으로">
              <img src={brandLogo} alt="HajaCheck" className="h-7 w-auto object-contain" />
            </Link>
          </div>

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
                ocrFeedback={ocrFeedback}
              />

              <div className="flex flex-col gap-1.5">
                {/* 배지는 <label> 밖의 형제 요소로 둔다 — <label> 안에 두면 getByLabelText의
                    접근성 이름 계산에 "자동인식"까지 붙어 exact-match 테스트가 깨진다(#748). */}
                <div className="flex items-center">
                  <label className={LABEL_CLASSES} htmlFor="signup-business-number">
                    사업자등록번호
                  </label>
                  {autoFilledFields.has('brn') && (
                    <span className={AUTO_FILLED_BADGE_CLASSES}>자동인식</span>
                  )}
                </div>
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
                <div className="flex items-center">
                  <label className={LABEL_CLASSES} htmlFor="signup-company-name">
                    상호명
                  </label>
                  {autoFilledFields.has('companyName') && (
                    <span className={AUTO_FILLED_BADGE_CLASSES}>자동인식</span>
                  )}
                </div>
                <input
                  id="signup-company-name"
                  type="text"
                  className={INPUT_CLASSES}
                  value={companyName}
                  onChange={(event) => handleCompanyNameChange(event.target.value)}
                />
              </div>

              <div className="flex flex-col gap-1.5">
                <div className="flex items-center">
                  <label className={LABEL_CLASSES} htmlFor="signup-representative-name">
                    대표자명
                  </label>
                  {autoFilledFields.has('representativeName') && (
                    <span className={AUTO_FILLED_BADGE_CLASSES}>자동인식</span>
                  )}
                </div>
                <input
                  id="signup-representative-name"
                  type="text"
                  className={INPUT_CLASSES}
                  value={representativeName}
                  onChange={(event) => handleRepresentativeNameChange(event.target.value)}
                />
              </div>

              <div className="flex flex-col gap-1.5">
                <div className="flex items-center">
                  <label className={LABEL_CLASSES} htmlFor="signup-business-start-date">
                    개업일자
                  </label>
                  {autoFilledFields.has('startDate') && (
                    <span className={AUTO_FILLED_BADGE_CLASSES}>자동인식</span>
                  )}
                </div>
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
                {businessVerificationError &&
                  (isBusinessVerificationErrorBypassable ? (
                    // 판정 불가 에러 통과 안내(#910) — VERIFIED 성공 뱃지와 구분되도록
                    // UNAVAILABLE과 동일한 warning 톤을 재사용한다. role="alert"는 붙이지 않는다
                    // (제출을 막는 에러가 아니라 통과를 알리는 안내이므로).
                    <p className={WARNING_SOFT_CLASSES}>
                      ⏳ {BUSINESS_VERIFICATION_ERROR_BYPASS_MESSAGE}
                    </p>
                  ) : (
                    <p role="alert" className={ERROR_CLASSES}>
                      {ERROR_MESSAGES[businessVerificationError.code] ??
                        BUSINESS_VERIFICATION_DEFAULT_ERROR_MESSAGE}
                    </p>
                  ))}
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

            {submitErrorMessage && (
              <p role="alert" className={ERROR_CLASSES}>
                {submitErrorMessage}
              </p>
            )}

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
