-- Flyway V10 — 시설물 등록 필드 확장: 초기 등급·담당자·메모(#628 / HAJA-347).
-- (dev에 먼저 머지된 V8__grant_admin_to_company_owners.sql·V9__add_facilities_next_inspection_due_at_index.sql
-- 와 번호가 충돌해 V10으로 재번호했다.)
--
-- Figma 시설물 등록 모달에는 있으나 기존 facilities 테이블/엔티티에는 없던 4개 필드 중, DB DDL 소유자
-- (Polalise) 회신 없이 진행 가능한 3개 필드만 우선 반영한다. 대표 사진(최대 4장, facility_photos 테이블)은
-- 신규 테이블 설계라 Polalise 검토 필요 — 별도 후속 마이그레이션으로 분리(#632).
--
-- 초기 등급(initial_grade): 대시보드 "하자 등급 분포"(GradeDistributionResponse)는 점검 이력
-- (defects.grade, defect_grade_type)에서 산출되는 계산값이라 이 컬럼과는 완전히 다른 개념이다.
-- 컬럼명을 initial_grade로 명확히 구분하는 것에 더해, defect_grade_type을 그대로 재사용하지 않고
-- 전용 enum facility_initial_grade_type을 신설해 두 개념을 DB 스키마 레벨에서도 분리한다 — 라벨
-- 집합이 우연히 A~E로 같을 뿐, 한쪽 등급 체계가 바뀌어도 다른 쪽에 영향을 주지 않아야 한다.
--
-- 담당자(assignee_user_id): inspections.assigned_inspector_id와 동일한 FK 패턴(nullable, users
-- 참조)이다. 배정 가능 여부(활성 사용자·INSPECTOR/ADMIN 역할·요청자와 동일 회사·양쪽 유효 멤버십)는
-- inspections와 동일하게 AuthService.validateAssignableInspector로 애플리케이션에서 검증한다
-- (HAJA-25). inspections.assigned_inspector_id와 달리 이 컬럼은 nullable(시설물 등록 시 담당자 미배정
-- 허용)이고, 점검 회차 생성만큼 강한 정합성 보장이 필요한 상태 전이도 아니므로 inspections가 가진
-- DB 트리거(check_inspection_assigned_inspector_company) 수준의 DB 레벨 방어는 이번 범위에 포함하지
-- 않는다 — 애플리케이션 계층 검증(AuthService)이 유일한 방어선이다.
--
-- 메모(memo): 자유 텍스트라 다른 자유 서술 컬럼(예: bot_scenarios.response_text)과 동일하게 text로 둔다.

do $$
begin
    if not exists (
        select 1
          from pg_type t
          join pg_namespace n on n.oid = t.typnamespace
         where n.nspname = 'public'
           and t.typname = 'facility_initial_grade_type'
    ) then
        create type public.facility_initial_grade_type as enum ('A', 'B', 'C', 'D', 'E');
    end if;
end
$$;

comment on type public.facility_initial_grade_type is
    '시설물 등록 시 입력하는 초기 등급(A~E) — defect_grade_type(점검 이력 기반 하자 위험도)과는 별개의 독립 개념';

alter table facilities
    add column if not exists initial_grade facility_initial_grade_type,
    add column if not exists assignee_user_id bigint
        constraint fk_facilities_assignee references users,
    add column if not exists memo text;

comment on column facilities.initial_grade is
    '시설물 등록 시 입력하는 초기 등급(A~E, nullable). 대시보드 하자 등급 분포(defects.grade 기반 계산값)와 혼동하지 말 것';

comment on column facilities.assignee_user_id is
    '시설물 담당자로 배정된 사용자 식별자(nullable). 배정 가능 여부는 AuthService.validateAssignableInspector로 애플리케이션에서 검증(활성 사용자·INSPECTOR/ADMIN 역할·요청자와 동일 회사·양쪽 유효 멤버십)';

comment on column facilities.memo is '시설물 등록 메모(자유 텍스트, nullable)';

create index if not exists idx_facilities_assignee
    on facilities (assignee_user_id);
