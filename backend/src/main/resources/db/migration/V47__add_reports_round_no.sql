-- Flyway V47 — reports.round_no(보고서 발급 시점 회차 스냅샷) 추가 + 백필 + NOT NULL(#1702).
--
-- 배경: #1702가 점검일 소급 입력을 허용하면서 inspections.round_no를 점검일 순서로 재정렬한다(뒤 회차 +1 시프트).
-- 그런데 보고서는 회차를 따로 갖지 않고 표시·필터 모두 inspections.round_no를 실시간으로 읽고 있었다
-- (ReportService.toInspectionContext / ReportRepository.findCompanyPage). 그대로 두면 재정렬 순간
-- **이미 발급·인쇄된 PDF 표지의 "제N회차"와 시스템 표기가 어긋난다**(PDF는 파일로 나가서 되돌릴 수 없다).
-- 그래서 회차를 보고서 자신의 값으로 스냅샷하고, 이후 FINALIZED는 동결 / DRAFT만 재동기화한다
-- (ReportRepository.syncDraftRoundNoToInspection).
--
-- 데이터 전제: reports.inspection_id는 V1(:700)에서 `not null references inspections`라 대상 점검이 없는
-- 고아 행이 구조적으로 존재할 수 없다 → 백필이 전 행을 채우므로 이어지는 set not null이 안전하다.
-- (그럼에도 승격 전 프리플라이트로 행 수·정합을 실측한다 — #660 V11 전례.)
--
-- 멱등 가드(#544 P1 회귀 방지 패턴, V41/V44/V46과 동일): add column if not exists + 백필 대상을
-- `round_no is null`로 한정 + set not null 재실행 무해 → 캐노니컬 DDL이 이미 이 컬럼을 not null로
-- 포함한 기존 DB(baseline-on-existing 경로)에서도 전 구간이 no-op으로 통과한다.
alter table reports
    add column if not exists round_no integer;

update reports r
set round_no = i.round_no
from inspections i
where r.inspection_id = i.id
  and r.round_no is null;

alter table reports
    alter column round_no set not null;

comment on column reports.round_no is '보고서 발급 시점의 점검 회차 스냅샷(#1702). 점검일 소급 입력으로 inspections.round_no가 재정렬돼도 FINALIZED 보고서는 이 값이 동결되어 이미 발급된 PDF 표지와 영구 일치한다. DRAFT만 재정렬을 따라 재동기화된다.';
