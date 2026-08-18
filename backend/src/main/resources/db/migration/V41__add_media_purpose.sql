-- Flyway V41 — media.purpose 컬럼 추가: 원본 촬영사진 vs 조치 후 사진 구분(#1641).
--
-- 배경: 조치 후 사진 업로드(DefectService.registerActionResult)가 원본 촬영사진 업로드와 동일한
-- POST /api/inspections/{id}/media + media 테이블을 그대로 재사용한다. 두 용도를 구분하는 값이 없어
-- 분석결과뷰어(GET /api/inspections/{id}/media, #803)가 조치 후 사진까지 "하자 0건 이미지"로 갤러리에
-- 노출한다. AI 재분석 대상 조회(InspectionAnalysisService)도 같은 이유로 조치 사진을 재분석 대상에
-- 끼워 넣을 수 있다.
--
-- purpose는 file_type(media_file_type)처럼 Postgres named enum으로 만들지 않는다 — named enum은 값
-- 추가마다 ALTER TYPE ... ADD VALUE가 트랜잭션 격리 제약(같은 트랜잭션 내 즉시 사용 불가)에 걸려
-- 후속 값 추가가 매번 별도 마이그레이션 커밋을 요구한다. VARCHAR + CHECK 화이트리스트로 단순하게 둔다
-- (Media.purpose는 Java에서 @Enumerated(EnumType.STRING)으로 매핑).
--
-- 멱등 가드(#544 P1 회귀 방지 패턴, V19/V32와 동일): add column if not exists / pg_constraint 존재 가드로
-- 캐노니컬 DDL이 이미 이 컬럼을 포함한 기존 DB(baseline-on-existing)에서도 no-op으로 안전하게 통과한다.
--
-- 운영 트래픽을 무기한 대기시키지 않는다(V7·V36·V38과 동일 방침) — CHECK 제약 추가는 기존 행 전체를
-- 검증하므로 짧게라도 락을 잡을 수 있다.
set local lock_timeout = '5s';

-- 1) purpose 컬럼 추가 — 기본값 INSPECTION_SOURCE(원본 촬영/분석 대상). 기존 행은 전부 이 값으로
--    채워진 뒤, 아래 백필 UPDATE가 조치 후 사진으로 확인된 행만 DEFECT_ACTION으로 전환한다.
alter table media
    add column if not exists purpose varchar(30) not null default 'INSPECTION_SOURCE';

-- 2) 값 화이트리스트 — 두 값만 허용(named enum 대신 CHECK로 강제).
do
$$
    begin
        if not exists (
            select 1 from pg_constraint
             where conname = 'chk_media_purpose'
               and conrelid = 'public.media'::regclass
        ) then
            alter table public.media
                add constraint chk_media_purpose
                    check (purpose in ('INSPECTION_SOURCE', 'DEFECT_ACTION'));
        end if;
    end
$$;

comment on column media.purpose is '미디어 용도(#1641) — INSPECTION_SOURCE(원본 촬영, 분석·분석결과뷰어 대상) / DEFECT_ACTION(조치 후 사진, 분석·분석결과뷰어 제외)';

-- 3) 백필 — 이미 조치 후 사진으로 연결된 것으로 확인된 media만 DEFECT_ACTION으로 전환한다.
--    ① defects.action_media_id(V12) — "최신 스냅샷" 단일 값 컬럼이 가리키는 media.
--    ② defect_action_logs.media_id(V32) — append-only 조치 등록 이력 전체가 가리키는 media
--       (action_media_id는 최신 제출만 반영하므로, 그 이전에 덮어써진 조치 사진은 로그에만 남아 있다).
--    두 집합의 합집합을 한 번의 UPDATE로 전환한다 — WHERE가 "아직 안 된 행"을 다시 걸러내지는 않지만
--    (DEFECT_ACTION → DEFECT_ACTION 재대입은 그 자체로 멱등) 대상 집합 자체가 항상 같은 쿼리로
--    재계산되므로 재실행해도 결과가 달라지지 않는다.
update media
   set purpose = 'DEFECT_ACTION'
 where id in (select action_media_id from defects where action_media_id is not null)
    or id in (select media_id from defect_action_logs);
