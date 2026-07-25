-- Flyway V17 — 시나리오 챗봇 트리 시드(FR-7, #20/HAJA-33).
-- dev에 V13(media.detail_url, #788/#789)~V16(defects.area_ratio, #803)이 이미 선점해
-- V17로 재번호했다(V6/V10과 동일한 재번호 컨벤션).
--
-- bot_scenarios 테이블은 V1(baseline)에 이미 존재하나 비어 있어(시드 없음) 챗봇 위젯이 표시할 버튼이
-- 없다. 여기서 4개 최상위 카테고리와 그 하위 노드를 데이터로만 채운다(스키마 변경 없음).
--
-- self-reference(parent_id → bot_scenarios.id)라 부모를 먼저 insert하고 그 id를 참조해야 한다.
-- PostgreSQL이라 RETURNING id를 변수로 못 쓰므로, 자식 insert는 부모를 (category, button_label)
-- 서브쿼리로 매칭한다(최상위 button_label은 유일, 중간 노드 button_label도 category 내에서 유일하므로
-- 결정론적으로 부모가 특정된다). Flyway는 이 마이그레이션을 DB당 1회만 실행하므로 재실행 중복은 없다.
-- category는 최상위 라벨을 서브트리 전체가 상속한다(스냅샷은 최상위 category를 읽음, CounselTicketService 참고).

-- ── 1단계: 최상위 4개(parent_id NULL, responseText NULL, leadsToCounselor false) ──
insert into bot_scenarios (parent_id, category, button_label, response_text, leads_to_counselor, sort_order)
values
    (null, 'INSPECTION_REPORT', '점검 결과서 관련', null, false, 1),
    (null, 'ACCOUNT_BILLING', '계정 및 결제', null, false, 2),
    (null, 'USAGE_GUIDE', '이용 방법 안내', null, false, 3),
    (null, 'ERROR_REPORT', '오류 신고', null, false, 4);

-- ── 2단계: 최상위 직속 자식(중간/리프 노드) ──
insert into bot_scenarios (parent_id, category, button_label, response_text, leads_to_counselor, sort_order)
select p.id, p.category, c.button_label, c.response_text, c.leads_to_counselor, c.sort_order
from (values
    ('INSPECTION_REPORT', '결과서 다운로드 방법',
        '점검 결과서는 [점검 관리 > 완료된 점검] 메뉴에서 해당 점검을 선택한 뒤 우측 상단 ''PDF 다운로드'' 버튼으로 받으실 수 있습니다.',
        false, 1),
    ('INSPECTION_REPORT', 'AI 분석 결과 등급 문의',
        'AI 분석 등급에 대해 궁금하신 점을 상담원이 안내해드리겠습니다.', false, 2),
    ('INSPECTION_REPORT', '결측치 수정 요청',
        '결측치 수정은 상담원 확인이 필요한 항목입니다.', false, 3),
    ('ACCOUNT_BILLING', '요금제 변경/해지',
        '요금제 변경은 [마이페이지 > 요금제 관리]에서 직접 가능합니다. 해지·환불 관련 문의는 상담원 연결이 필요합니다.',
        false, 1),
    ('ACCOUNT_BILLING', '결제 수단 변경',
        '[마이페이지 > 결제 정보]에서 카드 정보를 직접 변경하실 수 있습니다.', false, 2),
    ('ACCOUNT_BILLING', '계정 권한 설정 방법',
        '회사 관리자만 팀원 권한을 변경할 수 있습니다. [설정 > 팀 관리]를 확인해주세요.', false, 3),
    ('USAGE_GUIDE', '점검 등록 방법',
        '[점검 관리 > 새 점검 등록]에서 시설물을 선택하고 촬영 데이터를 업로드하면 자동으로 분석이 시작됩니다.',
        false, 1),
    ('USAGE_GUIDE', '하자 관리 사용법',
        '분석 결과에서 발견된 하자는 [하자 관리] 메뉴에서 상태별로 확인하고 조치 결과를 기록할 수 있습니다.',
        false, 2),
    ('USAGE_GUIDE', '보고서 생성 방법',
        '점검 완료 후 [보고서] 메뉴에서 자동 생성된 보고서를 확인하실 수 있습니다.', false, 3),
    ('ERROR_REPORT', '앱 오류/버그 신고',
        '어떤 화면에서 오류가 발생했는지 상담원에게 자세히 알려주시면 빠르게 확인하겠습니다.', false, 1),
    ('ERROR_REPORT', '보고서 PDF 생성 오류',
        'PDF 생성 오류는 상담원이 직접 확인이 필요한 사안입니다.', false, 2),
    ('ERROR_REPORT', '로그인 문제',
        '비밀번호를 잊으신 경우 로그인 화면의 ''비밀번호 찾기''를 이용해주세요. 계속 로그인이 안 되면 상담원 연결을 이용해주세요.',
        false, 3)
) as c(category, button_label, response_text, leads_to_counselor, sort_order)
join bot_scenarios p on p.parent_id is null and p.category = c.category;

-- ── 3단계: 상담원 연결 리프(leadsToCounselor=true) — 부모는 (category, 중간노드 button_label)로 특정 ──
insert into bot_scenarios (parent_id, category, button_label, response_text, leads_to_counselor, sort_order)
select p.id, p.category, '상담원 연결', null, true, 1
from bot_scenarios p
where p.category in ('INSPECTION_REPORT', 'ACCOUNT_BILLING', 'ERROR_REPORT')
  and p.button_label in (
      'AI 분석 결과 등급 문의', '결측치 수정 요청',
      '요금제 변경/해지',
      '앱 오류/버그 신고', '보고서 PDF 생성 오류', '로그인 문제');
