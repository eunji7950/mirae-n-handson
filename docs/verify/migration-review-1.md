# 마이그레이션 검증 1 — `GET /search.php` → `GET /api/items/search`

- 검증 대상: `upstream/main...HEAD` (25개 파일, +4110 / -1)
- 요청 범위: 레거시 엔드포인트 1개를 동작을 바꾸지 않고 modern 으로 이관.
- 기준 문서: `templates/approval-checklist.md` (v0)

판정: 반려

| 기준 | 결과 | 근거 |
|---|---|---|
| 테스트 통과 | 충족 | `cd modern/api && ./gradlew test --rerun` → 통과 56 / 실패 0 / 건너뜀 0. `cd characterization && TARGET_BASE_URL=http://localhost:8080 npm test` → 통과 27 / 실패 0 / 건너뜀 1(기존 `example-units.test.js:21` 의 `skipIf(legacyOnly)`, 이번 변경과 무관). 단 아래 "확인 필요" 1번 참고 |
| 치명 이슈 0건 | 충족 | 치명 이슈 없음. 리터럴 비밀값 · 문자열 SQL 결합 · 데이터 삭제 코드 없음(Specification 은 파라미터 바인딩) |
| 요청 범위 이탈 없음 | **미충족** | 이슈 #1. 요청 문장은 "엔드포인트 1개 이관"인데 대상 범위에 요청과 무관한 파일 11개가 섞여 있다 |
| 컨벤션 준수 | 충족 | 위반 없음(제안 #2 · #3만 있음). 계층 · 생성자 주입 · `*Response` record · `@ActiveProfiles("test")` · 와일드카드 import 없음 · `Clock` 위반 없음 확인 |

## 이슈 목록

| # | 심각도 | 파일:줄번호 | 근거 | 수정 방향 |
|---|---|---|---|---|
| 1 | 경고 | `CLAUDE.md:1`, `.claude/skills/convention-check/SKILL.md:1`, `.claude/skills/document-module/SKILL.md:1`, `docs/assignment/*`(4개), `docs/item-bank/ARCHITECTURE.md · ERD.md`, `characterization/tests/item-bank.test.js:1`, `characterization/__snapshots__/item-bank.test.js.snap` | `upstream/main` 이 `1bb4f2f` 로 뒤처져 있어 이전 커밋(`5999985`, `d271c05`)의 파일이 diff 에 포함된다. 이관 커밋 `f0b38b9` 만 보면 14개 파일이다(`modern/api` 소스 8 · 테스트 5 + `docs/item-bank/BUSINESS-RULES.md` +9줄). 체크리스트 3번 "모든 파일이 요청 문장으로 설명된다"를 이 범위로는 만족하지 못한다. 특히 `docs/assignment/` 는 다른 모듈이다 | 이전 작업이 이미 승인되었다면 `git diff d271c05...HEAD` 로 다시 검증한다. 아니라면 `upstream/main` 을 먼저 맞춘다 |
| 2 | 제안 | `modern/api/src/main/java/com/example/item/ItemSearchService.java:56` | `byId::get` 은 id 목록 조회와 상세 조회 사이에 행이 사라지면 `null` 을 반환해 `ItemSearchRowResponse.from` 에서 NPE 가 난다. 읽기 전용 트랜잭션이라 가능성은 낮다 | `filter(Objects::nonNull)` 로 거르거나, 없으면 `IllegalStateException` 으로 바꿔 던진다 |
| 3 | 제안 | `modern/api/src/test/java/com/example/item/ItemSearchControllerTest.java:1`, `ItemSearchCriteriaTest.java:1` | CLAUDE.md "테스트 데이터는 `ItemFixtures` 로 만든다" 와 달리 두 테스트에서 `ItemFixtures` 를 쓰지 않는다(Criteria 는 문자열 입력만 다루므로 영향은 작다) | 컨트롤러 테스트의 응답 데이터만이라도 `ItemFixtures` 로 만든다 |

## 확인 필요

1. `localhost:8080` 에서 돌고 있는 서버가 현재 `HEAD` 로 빌드한 것인지 확인하지 못했다. `/api/items/search` 는 200 으로 응답했고 characterization 도 통과했지만, 서버를 직접 띄운 것이 아니라 이미 떠 있던 프로세스를 썼다. `./gradlew bootRun` 으로 재기동한 뒤 한 번 더 돌려 확인해야 한다.
2. 레거시 동작 보존 근거: `LegacyInput` · `ItemSearchCriteria` 가 주석으로 BR-02 ~ BR-09, BR-11 을 따른다고 적었다. `legacy/` 의 `search.php` 와 줄 단위로 대조하지는 않았다(characterization 스냅샷 통과로 갈음).
3. 테스트가 "충분한가"(체크리스트 1번)는 개수(검색 관련 5개 클래스)로만 보았고, 경계 케이스 목록은 별도로 검토하지 않았다.

## 실행한 명령

| 대상 | 명령 | 결과 |
|---|---|---|
| modern/api | `./gradlew test --rerun` | 통과 56 / 실패 0 |
| characterization(새 API) | `TARGET_BASE_URL=http://localhost:8080 npm test` | 통과 27 / 실패 0 / 건너뜀 1 |
| modern/web | 실행하지 않음 | 변경 없음(해당 없음) |
