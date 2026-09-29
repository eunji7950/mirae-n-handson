---
name: convention-check
description: 변경된 코드가 CLAUDE.md 의 코딩 컨벤션 · 금지 사항을 지키는지 점검해 위반을 파일:줄번호로 보고한다. "컨벤션 점검", "규칙 점검", "커밋 전 점검", "리뷰 전에 확인", "PR 올리기 전에 봐 줘" 같은 요청이나, 커밋 · 리뷰 직전에 변경 사항을 검사할 때 쓴다.
argument-hint: "[경로 ...] (생략하면 git 변경 파일)"
allowed-tools:
  - Read
  - Grep
  - Glob
  - Bash(git diff *)
  - Bash(git status *)
---

# 컨벤션 점검

- 코드를 직접 고치지 않는다. 판정과 수정 방향만 보고한다.
- 근거 라인을 댈 수 없는 지적은 하지 않고 "확인 필요"로 남긴다.

## 1. 점검 대상 정하기

- 인자 `$ARGUMENTS` 가 있으면 그 경로(파일 또는 폴더)만 본다.
- 없으면 아래 두 명령의 결과를 합친다. 아직 add 하지 않은 새 파일(`??`)도 포함한다.
  - `git status --porcelain`
  - `git diff --name-only HEAD`
- 삭제된 파일은 내용을 보지 않는다. 다만 보호 파일이 삭제됐으면 항목 1로 판정한다.
- 대상이 없으면 "점검할 변경 없음"만 출력하고 끝낸다.

## 2. 점검 항목

범위가 api 면 `modern/api/src/main/**/*.java`(항목 6은 test 포함), web 이면 `modern/web/src/**/*.{ts,tsx}` 에만 적용한다.

1. [공통] 보호 파일 변경 — 대상에 `legacy/`, `characterization/__snapshots__/`, `package-lock.json`,
   `application.yml`, `.env*` 가 있으면 위반. `db/`, `build.gradle`, `package.json` 은 "확인 필요"(사전 승인 대상).
   줄번호 대신 `파일:-` 로 적는다.
2. [api] 생성자 주입만 — `@Autowired` 필드 주입이나 `lombok` import 가 있으면 위반.
3. [api] 컨트롤러 계층 — `*Controller.java` 에 Repository 필드 · 생성자 인자, `try {`, 엔티티 타입 반환이 있으면 위반.
4. [api] 시각은 `Clock` 으로 — 인자 없는 `LocalDateTime.now()`, `LocalDate.now()`, `System.currentTimeMillis()` 가 있으면 위반.
5. [api] 예외 · 로그 — 빈 `catch` 블록, `System.out`, `System.err`, `printStackTrace()` 가 있으면 위반.
6. [api] 형식 — 와일드카드 import(`import ….*;`)나 120자를 넘는 줄이 있으면 위반.
7. [web] 타입 — `any` 타입, `as unknown as`, `@ts-ignore`, 같은 줄에 사유가 없는 `eslint-disable` 이 있으면 위반.
8. [web] 컴포넌트 · 금지 API — `export default`, `React.FC`, `style={{`, `dangerouslySetInnerHTML`,
   `console.log`, `getByTestId`, `data-testid`, `src/api/client.ts` 밖의 `fetch(` 가 있으면 위반.

판정 방법:
- Grep 으로 후보 줄을 찾은 뒤 Read 로 앞뒤 문맥을 확인하고 판정한다. 주석 · 문자열 안의 일치는 위반이 아니다.
- 변경 파일 전체를 보되, 이번 변경과 무관한 기존 위반이면 수정 방향 칸에 "(기존 코드)"를 붙인다.

## 3. 출력 형식 (순서 고정)

### 판정 요약

| # | 항목 | 판정 |
|---|---|---|
| 1 | 보호 파일 변경 | 통과 / 위반 N건 / 확인 필요 / 해당 없음 |

8개 항목을 모두 적는다. 마지막 줄에 "위반 N건, 확인 필요 M건"을 적는다.

### 위반 목록

| 파일:줄번호 | 어긴 규칙 | 수정 방향 |
|---|---|---|

- 위반이 없으면 표 대신 "위반 없음"이라고 적는다.
- "확인 필요" 항목은 표 아래에 목록으로 따로 적고, 왜 판정하지 못했는지 한 줄로 쓴다.
