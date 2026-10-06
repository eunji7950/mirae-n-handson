# 마이그레이션 검증 3 — `GET /search.php` → `GET /api/items/search`

- 검증 대상: `git diff 1bb4f2f -- modern characterization`
  - `1bb4f2f` 는 `upstream/main` 과 HEAD 의 merge-base 다. HEAD 는 `b34513f` 다.
  - **작업 트리를 포함한다.** 커밋하지 않은 `ItemSearchCriteria.java` 수정, 그리고 이번 검증에서 tester 가 추가한 테스트 2개 파일이 들어 있다.
  - 범위는 사용자가 정했다.
- 요청 범위: 레거시 엔드포인트 1개를 동작을 바꾸지 않고 modern 으로 이관.
- 기준 문서: `templates/approval-checklist.md` (v1 · 2회차)
- 수행 방식:
  - 체크리스트 대조는 reviewer Sub-agent 가, 테스트 보강 · 실행은 tester Sub-agent 가 맡았다.
  - 최종 판정은 메인 세션이 했다.
  - 메인 세션은 직전에 경고 1(배열 파라미터)을 고친 "만든 쪽"이다. 사용자 결정에 따라 대조 · 실행은 Sub-agent 에 맡겼고, 판정만 메인 세션이 했다.
- 2차 이후 변경:
  - `ItemSearchCriteria.java`: `lastValue` 를 `phpValue` · `firstArrayElement` 로 바꿨다. `name[]` · `name[key]` 배열 파라미터를 레거시 `reset()` 처럼 첫 원소로 해석한다(`legacy/item-bank-php/search.php:51-80`). 커밋하지 않았다.
  - tester 가 테스트를 추가했다.
    - `ItemSearchCriteriaTest`: 9개
    - `ItemSearchServiceTest`: 1개

판정: 보류

| 기준 | 결과 | 근거 |
|---|---|---|
| 테스트 통과 | 충족 | 아래 1절 참고. 실패는 0건이다. 1장 27행의 분기 테스트도 이번에 추가되어 충족한다 |
| 치명 이슈 0건 | 충족 | 치명 이슈 없음. 2절 참고 |
| 요청 범위 이탈 없음 | 충족 | 경로 필터 안 15개 파일이 모두 item 패키지 이관 코드, 그 테스트, 이관 검증용 characterization 베이스라인이다. 53행은 3절 참고 |
| 컨벤션 준수 | 충족 | 반려 대상 0건. 4절 참고 |

보류 사유는 "확인 필요" 1번(인코딩하지 않은 `[` `]` 요청을 400 으로 거부)이 남았기 때문이다(체크리스트 11행). 확인 필요 1번이 결정되면, 경고 #1 · #2 에 조치 예정이나 보류 사유를 붙여 승인할 수 있다.

## 1. 테스트 실행 결과

| 명령 | 통과 | 실패 | 건너뜀 |
|---|---|---|---|
| `cd modern/api && ./gradlew test --rerun` | 66 | 0 | 0 |
| `cd characterization && npm test` (레거시 `:8081` 대상) | 28 | 0 | 0 |
| `cd characterization && TARGET_BASE_URL=http://localhost:8080 npm test` | 27 | 0 | 1 |
| `modern/web` | 해당 없음 (변경 없음) | | |

- api 수치는 메인 세션이 `build/test-results/test/*.xml` 을 직접 합산해 tester 보고와 맞췄다.
- 건너뛴 1건은 `characterization/tests/example-units.test.js:21` 이다.
  - `it.skipIf(Boolean(process.env.TARGET_BASE_URL))` 로 레거시 화면 전용이다. 2차와 같다.
  - 이 파일은 비교 범위 밖이다.
- 서버 기동 시각(29행):
  - `:8080` `bootRun` 은 2026-10-06 17:18:05 에 다시 기동했다.
  - `ItemSearchCriteria.java` 마지막 수정은 17:12:44 다. 소스 수정 이후에 기동했으므로 충족한다.
- 테스트 삭제 · skip · `@Disabled` 추가는 없다(30행). `git diff -- modern/api/src/test` 는 +88 / -0 이다.
- `characterization/` 의 테스트와 스냅샷은 베이스라인 커밋 `5999985` 이후 바뀌지 않았다(31행).
- 추가된 테스트:
  - `ItemSearchCriteriaTest`
    - `appendArrayUsesFirstElement` · `keyedArrayUsesOverwrittenValue`
    - `laterArrayOverridesScalar` · `laterScalarOverridesArray`
    - `nestedArrayBecomesArrayString`
    - `levelArrayUsesFirstElement` · `pageArrayUsesFirstElement`
    - `repeatedScalarUsesLastValue` · `noParamsUsesDefaults`
  - `ItemSearchServiceTest.skipsRowsDeletedBetweenQueries`: `ItemSearchService.java:61` 분기를 검증한다. 2차 #2 를 해소한다.
- 레거시 실측 대조:
  - tester 는 레거시 원본을 열지 못했다. 그래서 배열 테스트의 기대값은 메인 세션이 넘긴 표에서 가져왔다.
  - 메인 세션이 `:8081` 과 `:8080` 에 같은 입력을 보내 결과 건수를 비교했다.
  - `q[]=X&q[]=Y`, `q[x]=X&q[x]=Y`, `q=Y&q[]=X`, `q[]=X&q=Y`, `level[]=5`, `page[]=2` 는 모두 같은 건수가 나왔다.

## 2. 치명 이슈 대조 (reviewer)

- 비밀값 · `.env*` · 운영 호스트: 없음.
- 데이터 손실: 없음. 조회 전용 `@Transactional(readOnly = true)` 이고 `db/` 변경도 없다.
- 비공개 문항 노출: 없음.
  - `ItemSearchSpecifications.java:32-34` 가 항상 `status = ACTIVE` 를 AND 로 붙인다.
  - 행을 다시 조회할 때도 그 결과의 id 만 쓴다.
- CORS · `dangerouslySetInnerHTML`: `config/` 와 `modern/web` 에 변경이 없다.
- 로그 개인정보: 없음. `ItemSearchService.java:44` 는 page · rows · total 만 DEBUG 로 남긴다.
- SQL 문자열 조립: 없음. `ItemSearchSpecifications.java:38` 은 `cb.like` 파라미터로 바인딩한다.
- 트랜잭션 안의 외부 HTTP · 전체 루프, Hikari 설정: 없음. 페이지 단위 조회이고 `application.yml` 변경도 없다.
- `legacy/` 수정: 없음.

## 3. 요청 범위 (53행)

- 비교 범위는 `1bb4f2f`(뒤처진 `upstream/main`)에서 시작하므로 베이스라인 커밋 `5999985` 의 characterization 2개 파일이 들어온다.
- 그래도 충족으로 판단했다. 근거는 두 가지다.
  - `1bb4f2f` 이후 승인된 커밋이 없다. 1 · 2차가 모두 반려였으므로 "마지막으로 승인된 커밋" 은 `1bb4f2f` 로 본다.
  - 경로 필터(`modern` · `characterization`) 덕분에 1 · 2차에서 지적된 다른 모듈 문서와 Skill 이 빠졌다. 남은 `characterization` 2개는 허용 목록의 "그 변경을 검증하는 테스트 파일"이다.
- 범위 끝이 HEAD 가 아니라 작업 트리인 것은 사용자 결정이다. 커밋할 때는 `ItemSearchCriteria.java` 와 테스트 2개 파일을 함께 넣어야 이 판정과 맞는다.

## 4. 컨벤션 (reviewer, CLAUDE.md 2장 대조)

- 컨트롤러는 서비스만 주입받고 `ItemSearchResponse` record 를 반환한다(`ItemSearchController.java:13-27`). try/catch 가 없다.
- 다음이 하나도 없다:
  - 빈 catch · `System.out` · `printStackTrace`
  - 인자 없는 `now()`
  - `@Autowired` · Lombok
  - 와일드카드 import
- 새 리터럴 `"Array"` 는 상수 `PHP_ARRAY_TO_STRING` 으로 뺐다.
- 추가 테스트 10개에 모두 `@ActiveProfiles("test")` 와 한국어 `@DisplayName` 이 있다.
- `convention-check` Skill 은 실행하지 않았다. 체크리스트 64행의 근거는 reviewer 가 CLAUDE.md 와 직접 대조한 결과로 대신한다.

## 이슈 목록

| # | 심각도 | 파일:줄번호 | 근거 | 수정 방향 |
|---|---|---|---|---|
| 1 | 경고 | `modern/api/src/main/java/com/example/item/ItemSearchCriteria.java:94`, `:127`, `:130` | 배열 파라미터 해석이 레거시와 다른 입력이 javadoc 에 적힌 한계 말고도 더 있다. 레거시 `:8081` 과 새 API `:8080` 에 보내 실측한 결과(X = 13건 걸리는 키워드, Y = 0건 키워드)는 표 아래에 적었다 | javadoc 에 재현하지 않는 경우를 모두 적고, 표현을 "순서 정보가 없어"에서 "설계상 재현하지 않음"으로 고친다(원문 쿼리 `getQueryString()` 을 쓰면 재현할 수 있다). 아니면 `:127` · `:130` 의 키 해석을 PHP 규칙에 맞추고 위 입력을 테스트로 고정한다 |
| 2 | 경고 | `ItemSearchCriteria.java:91`, `:103` | "키는 처음 나온 순서로 들어 있다"는 전제는 Tomcat · MockMvc 의 파라미터 맵 구현(LinkedHashMap)에 기댄다. Servlet 명세는 `getParameterMap()` 의 순서를 보장하지 않는다. 스칼라 키와 배열 키 중 어느 쪽이 이기는지가 이 순서로 정해진다 | 컨테이너 의존이라는 점을 주석에 적고, 순서를 고정하는 `@WebMvcTest` 케이스를 하나 둔다 |
| 3 | 제안 | `modern/api/src/test/java/com/example/item/ItemSearchControllerTest.java:1`, `ItemSearchCriteriaTest.java:1` | 2차 #3 이 아직 해소되지 않았다. CLAUDE.md 의 "테스트 데이터는 `ItemFixtures` 로 만든다" 와 다르다. Criteria 테스트는 엔티티가 필요 없어 영향이 작다 | 컨트롤러 응답 데이터부터 `ItemFixtures` 로 바꾼다 |

이슈 #1 실측 결과:

| 입력 | 레거시 | 새 API |
|---|---|---|
| `q[]=Y&q[0]=X` | 13 | 0 |
| `q[a][b=X` (안쪽 `]` 없음) | 13 | 0 |
| `q[ ]=Y&q[ ]=X` | 0 | 13 |
| ` q=X` (키 앞 공백) | 13 | 20 (조건 무시) |

경고 조치(승인 시 필수 기록):
- #1: 조치 예정 또는 보류 사유를 적는다. 정상 화면에서는 나오지 않는 입력이고, characterization 케이스에도 없다.
- #2: 조치 예정 또는 보류 사유를 적는다.

2차 이슈 처리 현황:
- #1(범위): 3절에서 정리했다.
- #2(`:61` 분기 테스트): tester 가 해소했다.
- #3(`ItemFixtures`): 해소하지 않았다. 이번 이슈 #3 으로 이어진다.

## 확인 필요

1. **인코딩하지 않은 `[` `]` 요청을 새 API 가 400 으로 거부한다.**
   - 실측 결과:

     | 요청 | 레거시 `:8081` | 새 API `:8080` |
     |---|---|---|
     | `curl -g '...?q[]=a&q[]=b'` (인코딩 안 함) | 200 | 400 |
     | `%5B%5D` 로 인코딩한 요청 | 200 (1절 실측 대조) | 200 |

   - Tomcat 이 쿼리의 `[` `]` 를 받아들이지 않기 때문으로 보인다. 설정 근거 파일이 없어 줄번호를 댈 수 없으므로 여기에 둔다.
   - 그래서 인코딩하지 않은 배열 파라미터는 이번 수정 코드까지 오지 못한다. 레거시와 상태 코드가 다르다.
   - 맞추려면 `server.tomcat.relaxed-query-chars` 같은 서버 설정을 바꿔야 한다. 체크리스트 60행에 따라 `application.yml` 변경이 요청 범위에 들어가는지 결정이 필요하다. 받아들이지 않기로 하면 그 결정을 기록한다.
2. `q[a]=y&q[a][b]=x` 는 실측으로 판별하지 못했다. 레거시 · 새 API 모두 0건이었는데, 두 후보값(`"Array"`, `y`)이 모두 0건이라 어느 쪽인지 구분되지 않는다.
3. 미추적 `.claude/agents/` · `.claude/settings.json` 은 경로 필터 밖이라 대조하지 않았다. 이관 커밋에 들어가지 않게 해야 한다.

## 의심 동작 (레거시 버그로 보이지만 이관에서 유지됨. 고친 곳 없음)

1. 난이도를 비우면 `level < 5` 라 난이도 5 가 빠진다. 주석은 "1~5 모두 포함"이라고 되어 있다.
   - 레거시: `legacy/item-bank-php/search.php:213-216`
   - modern: `ItemSearchSpecifications.java:52-55`
2. 키워드의 `%` · `_` 를 이스케이프하지 않아 와일드카드로 동작한다.
   - 레거시: `search.php:94-97`
   - modern: `ItemSearchSpecifications.java:36-41`
3. 배열의 첫 원소가 다시 배열이면 문자열 `"Array"` 로 검색한다.
   - 레거시: `search.php:55`
   - modern: `ItemSearchCriteria.java:127-128`
4. 1~5 밖의 난이도를 `(int)` 로 바꿔 그대로 비교한다(`3abc` → 3, `-1` → -1).
   - 레거시: `search.php:223-229`
5. 페이지 정규식 `$` 가 끝의 개행 하나를 허용한다.
   - 레거시: `search.php:337`
   - modern: `ItemSearchCriteria.java:31`
6. 마지막 페이지를 넘으면 건수는 그대로이고 행만 빈다.
   - 레거시: `search.php:349`, `:655-660`
7. DB · 쿼리 오류 때 레거시는 200 HTML 을, 새 API 는 500 을 돌려준다.
   - 레거시: `search.php:712-719`, `:726-729`
   - 직전 리뷰에서 넘어온 항목이고 아직 결정하지 않았다.
