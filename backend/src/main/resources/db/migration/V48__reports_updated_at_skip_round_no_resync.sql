-- Flyway V48 — trg_reports_set_updated_at 에 WHEN절 추가: round_no만 바뀌는 UPDATE는 updated_at을
-- 건드리지 않는다(#1702 리뷰 P2).
--
-- 배경: ReportRepository.syncDraftRoundNoToInspection의 javadoc은 "updated_at은 일부러 갱신하지
-- 않는다 — 시스템 재번호는 사용자의 편집이 아니라서"라고 명시하지만, 실제로는 그렇지 않았다. V1
-- (:1150-1158)의 set_updated_at() 트리거 함수는 BEFORE UPDATE FOR EACH ROW로 걸린
-- trg_reports_set_updated_at(V1:1213 부근)에서 WHERE/WHEN 조건 없이 NEW.updated_at = now()를
-- 무조건 덮어쓴다. syncDraftRoundNoToInspection의 네이티브 벌크 UPDATE(round_no만 SET)도 대상 행마다
-- 이 트리거를 발동시키므로, 점검일 소급 입력으로 회차가 재정렬될 때마다 해당 시설물의 모든 DRAFT
-- 보고서 updated_at이 재번호 시점으로 밀려 있었다(prod에서 tgenabled='O' 실측 확인). 그 결과
-- ReportRepository.findCompanyPage의 "r.updatedAt >= :updatedAtFrom" 기간 필터가 손대지 않은
-- 초안까지 오탐으로 끌어오고, "최근 수정" 정렬도 왜곡됐다.
--
-- 왜 WHEN절인가: Report.roundNo는 Report.java에서 updatable=false다 — round_no가 실제로 바뀌는
-- UPDATE는 JPA dirty checking 경로로는 절대 발생하지 않고, syncDraftRoundNoToInspection의 벌크
-- 재동기화가 구조적으로 유일한 경로다(엔티티 javadoc 참고). 그래서 "NEW.round_no가 OLD.round_no와
-- 다르지 않을 때만 갱신"이라는 조건이 곧 "시스템 재번호일 때만 건너뛰고, 그 외 모든 UPDATE는 그대로
-- 갱신"과 정확히 일치한다. 일반 편집(content/status 변경 등)은 round_no를 함께 SET하지 않으므로
-- NEW.round_no와 OLD.round_no가 항상 같아 WHEN절이 참이 되고, 트리거는 이전과 동일하게 정상 발동한다.
--
-- 멱등성: DROP TRIGGER IF EXISTS + CREATE TRIGGER 조합이라 재실행해도 동일한 최종 정의로 수렴한다
-- (두 번째 실행은 방금 만든 트리거를 지우고 같은 정의로 다시 만들 뿐이라 무해하다). 캐노니컬 DDL
-- (docs/design/db/HajaCheck_script.sql)에도 같은 WHEN절을 반영해 baseline-on-existing 경로에서도
-- 재실행이 no-op으로 수렴한다.
drop trigger if exists trg_reports_set_updated_at on reports;

create trigger trg_reports_set_updated_at
    before update
    on reports
    for each row
    when (new.round_no is not distinct from old.round_no)
execute procedure set_updated_at();

comment on trigger trg_reports_set_updated_at on reports is 'reports 행 수정 시 updated_at을 현재 시각으로 갱신한다. 단, round_no만 바뀌는 UPDATE(#1702 syncDraftRoundNoToInspection의 시스템 재번호 재동기화)는 WHEN절로 제외한다 — round_no는 Report.java에서 updatable=false라 이 벌크 UPDATE가 round_no 변경의 유일한 경로이며, 시스템 재번호는 사용자의 편집이 아니므로 updated_at을 "최근 수정"처럼 보이게 하면 안 된다.';
