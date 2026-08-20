// jsdom 환경에서 msw(Node 내장 undici)로 실제 파일 업로드(FormData+File/Blob) 요청을 검증하는
// 테스트가 겪는 File "realm" 불일치를 해소하는 공용 테스트 유틸 (#1712).
//
// 배경: jsdom이 만드는 File은 jsdom 자체 구현체다. 이 값이 실제 요청 경로(XHR → msw/node)를
// 타고 나가면, msw는 Node 내장 undici로 요청을 다시 파싱하는데, undici의
// multipartFormDataParser가 파싱 도중 새로 만드는 File 값에 대해 `webidl.is.File()` 브랜드
// 체크를 한다. 이 체크는 "undici 자신의 File 생성자로 만들어졌는지"를 보므로, 파싱 시점의
// 전역 File이 jsdom 것이면 항상 실패해 unhandled exception으로 요청이 깨진다(Node 22+에서
// undici가 이 체크를 엄격화하며 드러남 — Node 20 번들 undici는 덕타이핑이라 통과했었다).
//
// 반대로 전역 File을 통째로(파일 로드 시점에 한 번만) Node 것으로 바꿔버리면 이번엔 "보내는"
// 쪽이 깨진다 — jsdom의 FormData.append()는 자신이 만들지 않은 File을 인식하지 못하고
// (webidl union 변환이 jsdom 내부 hidden symbol만 인정 — jsdom/lib/generated/idl/FormData.js)
// 파일이 아닌 일반 문자열 필드로 강등시켜 버린다(실측: 이슈 코멘트의 "파일이 필요합니다" 현상).
//
// 그래서 "생성 시점"엔 jsdom File이, "파싱 시점"엔 Node(undici) File이 필요하다 — 이 둘은
// 타이밍이 다르다(생성은 테스트 코드가 동기로 하고, 파싱은 msw가 요청을 가로챈 뒤 일어난다).
// 이 유틸은 msw 자체의 라이프사이클 이벤트(`request:start`)를 이용해, "이번에 막 가로챈
// 요청이 실제로 multipart/form-data일 때만" 전역 File을 Node 것으로 바꿔치기한다 — 그 전에
// 테스트 코드가 만든 File(들)은 이미 jsdom File로 인코딩이 끝난 뒤이므로 영향받지 않고,
// 이 요청과 무관한 이전/이후의 File 생성(같은 테스트 내 다른 요청 등)도 건드리지 않는다
// (content-type 필터로 범위를 요청 단위로 좁혔다 — 전역으로 계속 Node File을 유지하면
// 같은 테스트에서 그 다음에 만드는 jsdom 대상 File이 깨진다).
//
// 주의(트레이드오프): msw가 XHR 바디(jsdom FormData)로 Request를 구성하는 과정 자체가
// jsdom 메인테이너가 명시적으로 비지원이라 밝힌 조합에 걸려 있다(jsdom/jsdom#3800 —
// "jsdom's FormData is only supported with jsdom's XMLHttpRequest") — 이 유틸을 써도 서버로
// 전달되는 파일명은 실측상 항상 "blob"으로, 바이트 내용은 0으로 유실된다(이 레포에서 손댈
// 수 없는 라이브러리 내부 이슈). 그래서 이 유틸은 "요청이 크래시 없이 실제로 도달하고, 파일이
// 문자열로 강등되지 않은 파일 엔트리로 정확한 개수만큼 전달되는지(성공/실패 분기 포함)"를
// 검증하는 통합 테스트에만 쓴다. 참고로 이 레포는 바이트·파일명 내용을 검증하는 테스트를
// 애초에 갖고 있지 않다 — facilityMediaApi.handlers.ts도 `typeof entry !== 'string'`으로
// 개수만 판별한다. node 환경(jsdom을 안 거쳐 이 realm 문제가 없는 facilityMediaApi.test.ts)
// 으로 옮겨서 되찾은 것도 "크래시 없이 개수가 정확히 전달됨"이지, 바이트 검증이 아니다.
// 파일명이 올바르게 전달되는지는(realm 문제와 무관한 mock 단위 테스트로) reportApi.uploadPdf
// 호출부를 다루는 finalizeReportFlow.test.ts가 인자 검증으로 별도 담당한다.
import type { SetupServer } from 'msw/node';
import { File as NodeFile } from 'node:buffer';

/**
 * 이 test file의 msw `server`에 대해 realm 호환 리스너를 등록한다.
 * 반환된 함수를 `afterEach`에서 호출해 전역 File을 jsdom 것으로 원복해야 한다
 * (다음 테스트가 다시 jsdom File을 만들 수 있도록).
 */
export function installMswFileRealmCompat(server: SetupServer): () => void {
  const JsdomFile = globalThis.File;

  server.events.on('request:start', ({ request }) => {
    const contentType = request.headers.get('content-type') ?? '';
    if (contentType.startsWith('multipart/form-data')) {
      (globalThis as { File: typeof File }).File = NodeFile as unknown as typeof File;
    }
  });

  return () => {
    (globalThis as { File: typeof File }).File = JsdomFile;
  };
}
