# 마이그레이션 검증 2 — `GET /search.php` → `GET /api/items/search`

- 검증 대상: `upstream/main...HEAD` (HEAD = `5b37527`, 26개 파일, +4151 / -1)
- 요청 범위: 레거시 엔드포인트 1개를 동작을 바꾸지 않고 modern 으로 이관.
- 기준 문서: `templates/approval-checklist.md` (v0)
- 1차 검증(`docs/verify/migration-review-1.md`) 이후 변경: `5b37527` 이 `ItemSearchService.java:61` 에 `filter(Objects::nonNull)` 을 넣고 1차 보고서를 추가했다.

판정: 반려

| 기준 | 결과 | 근거 |
|---|---|---|
| 테스트 통과 | 충족 | `cd modern/api && ./gradlew test --rerun` → 통과 56 / 실패 0 / 건너뜀 0. `cd characterization && TARGET_BASE_URL=http://localhost:8080 npm test` → 통과 27 / 실패 0 / 건너뜀 1(기존 `characterization/tests/example-units.test.js:21` 의 `skipIf(legacyOnly)`, 이번 변경과 무관). `modern/web` 은 변경 없음 → 해당 없음. 단 이슈 #2 참고 |
| 치명 이슈 0건 | 충족 | 치명 이슈 없음. 리터럴 비밀값 없음, 문자열 SQL 결합 없음(Specification 파라미터 바인딩), 데이터 삭제 · 덮어쓰기 코드 없음 |
| 요청 범위 이탈 없음 | **미충족** | 이슈 #1. 요청은 "엔드포인트 1개 이관"인데 이 범위에는 이관과 무관한 파일이 섞여 있다 |
| 컨벤션 준수 | 충족 | 위반 없음(제안 #3 만 있음). 이관 코드에서 `System.out` · `printStackTrace` · `@Autowired` · 인자 없는 `now()` · 빈 `catch` 가 없음을 grep 으로 확인. 컨트롤러는 서비스만 주입받고 `*Response` record 를 반환. 테스트 5개 클래스 모두 `@ActiveProfiles("test")` 가 있음 |

## 이슈 목록

| # | 심각도 | 파일:줄번호 | 근거 | 수정 방향 |
|---|---|---|---|---|
| 1 | 경고 | `CLAUDE.md:1`, `.claude/skills/convention-check/SKILL.md:1`, `.claude/skills/document-module/SKILL.md:1`, `docs/assignment/ARCHITECTURE.md:1` 외 3개, `docs/item-bank/ARCHITECTURE.md:1`, `docs/item-bank/ERD.md:1`, `characterization/tests/item-bank.test.js:1`, `characterization/__snapshots__/item-bank.test.js.snap:1` | `upstream/main` 이 `1bb4f2f` 에서 멈춰 있어 이전 커밋(`5999985` · `d271c05`)의 파일이 diff 에 그대로 들어온다. 이관 자체는 `f0b38b9` · `5b37527` 의 `modern/api` 소스 8 · 테스트 5 + 문서 일부다. `docs/assignment/` 는 다른 모듈이다. 체크리스트 3번 "모든 파일이 요청 문장으로 설명된다"를 이 범위로는 충족할 수 없다(1차에서도 같은 지적) | 앞선 커밋이 이미 승인되었다면 `git diff d271c05...HEAD` 로 범위를 좁혀 다시 판정한다. 아니라면 `upstream/main` 을 먼저 맞춘 뒤 검증한다 |
| 2 | 경고 | `modern/api/src/main/java/com/example/item/ItemSearchService.java:61` | `5b37527` 이 `filter(Objects::nonNull)` 분기를 추가했지만 이를 검증하는 테스트가 없다. `ItemSearchServiceTest.java` 에는 두 조회 사이에 행이 사라지는 경우를 다루는 케이스가 없다(해당 키워드로 grep 해 0건). 이 분기는 되돌려도 56개 테스트가 모두 통과한다. 레거시도 건수 · 행을 별개 쿼리로 읽으므로 같은 상황에서 행만 빠진다는 주석은 `legacy/item-bank-php/search.php:577`(건수) · `:603`(행)과 맞다 | `ItemSearchServiceTest` 에 `findWithDetailsByIdIn` 이 일부 id 만 돌려주는 케이스를 추가해 건수는 유지되고 행만 빠지는지 검증한다 |
| 3 | 제안 | `modern/api/src/test/java/com/example/item/ItemSearchControllerTest.java:1`, `ItemSearchCriteriaTest.java:1` | CLAUDE.md "테스트 데이터는 `ItemFixtures` 로 만든다" 와 달리 두 테스트에서 `ItemFixtures` 를 쓰지 않는다(1차 #3 과 같음, 미반영). Criteria 는 문자열 입력만 다뤄 영향은 작다 | 컨트롤러 테스트의 응답 데이터만이라도 `ItemFixtures` 로 만든다 |

## 확인 필요

1. 1차의 확인 필요 1번(서버 빌드 시점)은 해소했다. `localhost:8080` 의 `bootRun` 프로세스(pid 23956)는 12:51:28 에 시작했고, 서비스 파일 마지막 수정은 12:50:04 다. 현재 소스로 떠 있다.
2. 레거시 동작 보존 근거는 characterization 스냅샷 통과(27건)로 갈음했다. `LegacyInput` · `ItemSearchCriteria` 를 `search.php` 와 줄 단위로 대조하지는 않았다. 스냅샷에 없는 입력(예: 지수 표기 난이도, 전각 공백)은 단위 테스트에 의존한다.
3. 테스트가 "충분한가"(체크리스트 1번)는 검색 관련 5개 클래스 · 단위 테스트 56개 규모로만 판단했다. 경계 케이스 목록은 별도로 검토하지 않았다.
4. 레거시(`localhost:8081`)는 응답했지만 이번에는 레거시 대상 `npm test` 를 다시 돌리지 않았다. 스냅샷 파일은 변경하지 않았다.

## 실행한 명령

| 대상 | 명령 | 결과 |
|---|---|---|
| modern/api | `cd modern/api && ./gradlew test --rerun` | 통과 56 / 실패 0 / 건너뜀 0 |
| characterization(새 API) | `cd characterization && TARGET_BASE_URL=http://localhost:8080 npm test` | 통과 27 / 실패 0 / 건너뜀 1(기존 `example-units.test.js:21`) |
| modern/web | 실행하지 않음 | 변경 없음(해당 없음) |
