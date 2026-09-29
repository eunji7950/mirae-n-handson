# CLAUDE.md

Claude Code 심화 과정 실습 저장소. 도메인은 더미 에듀테크(문항 은행 · 과제 배포 · 성적 집계)다.
대상 코드는 `modern/api`(Spring Boot 3.3 · Java 21 · Gradle)와 `modern/web`(React 18 · TypeScript · Vite · Vitest)이다.

- 답변과 문서는 한국어로 쓴다. 코드 식별자는 영어로 쓴다.
- `legacy/` 는 분석 · 이관 대상이다. 허락 없이 수정하지 않는다.

## 1. 빌드 · 테스트 명령

```bash
# modern/api — 테스트는 H2 인메모리라 DB 컨테이너 없이 통과한다
cd modern/api && ./gradlew test
cd modern/api && ./gradlew test --tests 'com.example.item.ItemServiceTest'   # 단일 테스트 클래스
cd modern/api && ./gradlew build                                             # 테스트 포함 전체 빌드
cd modern/api && ./gradlew bootRun      # :8080. 먼저 docker compose --profile modern up -d 로 DB 를 띄운다

# modern/web
cd modern/web && npm ci                                          # 의존성 설치. npm install 은 쓰지 않는다
cd modern/web && npm run dev                                     # :5173
cd modern/web && npm run lint && npm run typecheck && npm test   # 커밋 전 필수
cd modern/web && npx vitest run src/components/ItemTable.test.tsx
cd modern/web && npm run build
```

- 작업을 마치면 바꾼 쪽의 테스트를 실행하고 통과 · 실패 수를 답변에 적는다. 실행하지 않았으면 "실행하지 않음"이라고 쓴다.
  - api: `./gradlew test`
  - web: `npm run lint && npm run typecheck && npm test`
- 하나라도 실패하면 "완료"라고 보고하지 않는다.
- 테스트가 실제 MariaDB 나 `localhost:8080` 에 붙게 만들지 않는다.

## 2. 코딩 컨벤션

### modern/api — 계층
- 패키지는 도메인 단위(`com.example.item`, `com.example.assignment`)다. 호출 방향은 Controller → Service → Repository 다.
- 컨트롤러는 서비스만 주입받는다. 컨트롤러에 Repository 를 주입하거나 SQL 문자열을 두지 않는다.
- 서비스는 자기 도메인 패키지의 Repository 만 주입받는다. 다른 도메인의 데이터가 필요하면 그 도메인의 서비스를 주입받는다.
- 컨트롤러는 엔티티를 반환하지 않는다. 응답은 `*Response` record 로 하고, 서비스가 트랜잭션 안에서 `from()` 으로 변환해 돌려준다.
- 의존성은 생성자 주입만 쓴다. main 코드에 `@Autowired` 필드 주입과 Lombok 을 쓰지 않는다. 테스트 코드는 예외다.
- 현재 시각은 주입받은 `Clock` 으로만 얻는다(`LocalDateTime.now(clock)`). main 코드에서 인자 없는 `LocalDateTime.now()` · `LocalDate.now()` · `System.currentTimeMillis()` 를 호출하지 않는다.

### modern/api — 예외 · 로깅
- 빈 `catch` 블록을 쓰지 않는다. 잡았으면 로그를 남기고 다시 던지거나 도메인 예외로 바꿔 던진다.
- 컨트롤러 메서드 안에 try/catch 를 두지 않는다. 예외 → HTTP 변환은 `common/GlobalExceptionHandler` 한 곳에서만 한다.
  - `NotFoundException` → 404
  - `IllegalArgumentException` · 검증 오류 → 400
  - `IllegalStateException` → 409
  - 나머지 → 500
- 로그는 SLF4J(`LoggerFactory.getLogger(<클래스>.class)`)로만 남긴다. `System.out` · `System.err` · `printStackTrace()` 를 쓰지 않는다.
- 로그에 학생 식별자(`STU-…`) · 이메일 · 토큰 값을 찍지 않는다.
- 로그 레벨은 정상 흐름 `INFO` 이하, 복구 가능한 실패 `WARN`, 요청을 실패시키는 예외 `ERROR` 다.

### modern/api — 테스트
- 새 public 서비스 메서드와 새 엔드포인트마다 테스트를 1개 이상 추가한다.
- 테스트 종류:
  - 서비스: Mockito 단위 테스트
  - 컨트롤러: `@WebMvcTest`
  - 리포지토리 쿼리: `@DataJpaTest`(H2)
- 테스트 클래스에는 `@ActiveProfiles("test")` 를 붙이고, 테스트 데이터는 `ItemFixtures` 로 만든다.
- 테스트 메서드 이름은 동작이 드러나는 camelCase(예: `rejectsOtherOrigins`)로 짓고, `@DisplayName` 에 한국어 설명을 붙인다.

### modern/api — 이름 · 형식
- 클래스는 `PascalCase`, 메서드 · 필드는 `camelCase`, 상수는 `UPPER_SNAKE_CASE` 다. 약어도 첫 글자만 대문자로 쓴다(`ItemDto`).
- 매직 넘버와 상태 코드 리터럴은 이름 붙은 상수로 뺀다(예: `ASSIGNMENT_CLOSED = "C"`).
- 들여쓰기는 4칸, 한 줄은 120자 이내로 쓴다. 와일드카드 import 를 쓰지 않는다.

### modern/web — 컴포넌트 · 스타일
- 함수 컴포넌트만 쓴다. 클래스 컴포넌트 · `React.FC` · `defaultProps` 를 쓰지 않는다.
- 컴포넌트는 `src/components/<이름>.tsx` 한 파일에 하나만 export 한다.
- props 타입은 같은 파일에 `interface <이름>Props` 로 선언한다.
- 컴포넌트는 named export 로만 내보낸다(`export default` 금지).
- 스타일은 `src/styles.css` 의 BEM 클래스(`block__element--modifier`)로 쓴다. JSX 에 `style={{…}}` 인라인 스타일을 쓰지 않는다.
- 이름 규칙:
  - 컴포넌트 · 타입은 `PascalCase`, 훅은 `use` + `camelCase` 다. 파일명은 export 하는 이름과 같게 짓는다.
  - 이벤트 prop 은 `on<동작>`, 핸들러 함수는 `handle<동작>` 이다.

### modern/web — 데이터 · API
- `fetch` 는 `src/api/client.ts` 의 `getJson()` 안에서만 호출한다. 컴포넌트와 훅에서 `fetch` 를 직접 부르지 않는다.
- 응답 타입은 `src/api/types.ts` 에 두고, 필드명은 백엔드 JSON 필드명을 그대로 쓴다.
- 조회 훅은 `src/hooks/` 에 두고 `useApiQuery(key, load)` 를 감싸서 만든다.
- 오류는 `ApiError` 로 받는다. 컴포넌트에서 HTTP 상태 코드를 직접 비교하지 않는다.
- 조회 결과를 그리는 화면은 로딩 · 오류 · 빈 결과를 모두 표시한다. 로딩 · 오류는 `AsyncSection` 으로 분기한다.
- 파생 값은 상태에 저장하지 않고 렌더 중에 계산한다. `useEffect` 안에서 한 상태를 보고 다른 상태를 set 하지 않는다.

### modern/web — 타입 · 테스트
- `any` · `as unknown as` · `@ts-ignore` 를 쓰지 않는다. 타입 import 는 `import type` 으로 쓴다. 미사용 변수는 `_` 접두어만 허용한다.
- `eslint-disable` 주석에는 같은 줄에 사유를 적는다.
- 컴포넌트마다 `<이름>.test.tsx` 를 둔다. 테스트에는 렌더 확인 1개와 사용자 상호작용(클릭 · 입력) 1개 이상을 넣는다.
- 요소는 `getByRole` · `getByLabelText` · `getByText` 로 찾는다. `getByTestId` 와 `data-testid` 를 쓰지 않는다. 스냅샷 테스트를 만들지 않는다.
- 네트워크 응답은 `src/test/mockFetch.ts` 로 등록한다.

## 3. 금지 사항

- `legacy/` 를 허락 없이 수정하지 않는다.
- DB 스키마 · 시드(`db/`)를 바꾸기 전에 먼저 묻는다. 대상은 테이블 · 컬럼 · 인덱스 변경과 마이그레이션 파일 추가다.
- 의존성 추가 · 업그레이드(`build.gradle` 의 `dependencies`, `package.json`)는 먼저 묻는다. 이유와 대안을 함께 적는다.
- `package-lock.json` 을 손으로 고치거나 지우지 않는다.
- `application.yml` 의 DB 접속 정보와 HikariCP 설정(`maximum-pool-size: 5`, `connection-timeout: 3000`)을 바꾸지 않는다.
- 실제 비밀값(운영 계정 · 토큰 · 키)을 코드나 설정에 리터럴로 넣지 않는다. 실습용 더미 값(`app-pass`)은 예외다.
- 운영 DB 호스트(`prod-db` 등)에 접속하는 명령이나 설정을 만들지 않는다.
- `.env*` 파일을 만들거나 읽지 않는다.
- `@Transactional` 메서드 안에서 외부 HTTP 호출이나 전체 테이블을 도는 루프를 실행하지 않는다.
- `WebConfig` 의 CORS 허용 범위(`http://localhost:5173` · GET)를 넓히지 않는다.
- `dangerouslySetInnerHTML` 을 쓰지 않는다. `console.log` 를 커밋에 남기지 않는다.
- `characterization/__snapshots__/` 를 손으로 고치지 않는다. 새 API 대상 테스트가 실패하면 이관 코드를 고친다.
- 요청받지 않은 파일을 고치지 않는다. 포맷 · import 정리도 요청 범위 안의 파일에서만 한다.

## 4. 아키텍처 안내

```
modern/api/src/main/java/com/example/
├── item/        문항 · 단원 · 태그 (ItemController · ItemService · ItemRepository · Item · Unit · Tag)
├── assignment/  학급 · 과제 · 배포 · 제출 · 리포트 (Distribution* · Report* · Submission*)
├── common/      GlobalExceptionHandler · ErrorResponse · NotFoundException · ClockConfig
└── config/      WebConfig (CORS)
modern/api/src/test/resources/application-test.yml   테스트 프로필 = H2 (MariaDB 모드)
modern/web/src/
├── api/         client.ts(getJson · ApiError) · items.ts(호출 함수) · types.ts(응답 타입)
├── hooks/       useApiQuery · useUnits · useUnitItems · useItem
├── components/  화면 컴포넌트와 같은 이름의 *.test.tsx
└── test/        mockFetch.ts · fixtures.ts
```

- 기존 엔드포인트:
  - `GET /api/units`, `GET /api/units/{code}/items`, `GET /api/items/{id}`
  - `GET /api/distributions/{id}`, `POST /api/distributions/{id}/redistribute`, `GET /api/classes/{id}/report`
- 새 엔드포인트는 `/api/<도메인 복수형>` 아래에 둔다.
- 공개 문항만 노출한다(`status = 'A'`).
- `open-in-view: false` 다. 연관 엔티티는 `@EntityGraph` 로 함께 가져온다.
- 데이터 흐름: 컴포넌트 → `src/hooks` 훅 → `src/api` 호출 함수 → `getJson()` → `modern/api`.
  - 기본 주소는 `VITE_API_BASE`(기본 `http://localhost:8080`)다.
  - `VITE_API_BASE=""` 로 두면 Vite 프록시를 쓴다.
- key 가 바뀌거나 컴포넌트가 언마운트되면 `useApiQuery` 가 `AbortController` 로 이전 요청을 취소한다.
- 도메인 용어:
  - 문항 `item` · 단원 `unit` · 난이도 `level`(1~5) · 태그 `tag`
  - 학급 `class` · 과제 `assignment` · 배포 `distribution` · 제출 `submission`
  - 학생 식별자 `STU-<숫자>`
- MariaDB `itembank` 하나를 `php` · `thymeleaf` · `modern` 프로필이 같이 쓴다. 성적 집계 로직은 MS-SQL 저장 프로시저(`db/mssql/init/`)에 있다.
- 레거시 규칙을 옮길 때는 근거를 `파일:줄번호` 로 답변에 적는다.
- Hook 은 `bash "$CLAUDE_PROJECT_DIR"/scripts/hook-node.sh <스크립트>` 로 실행한다. node 를 못 찾으면 명령을 막는다(fail-closed).

## 5. 완료 기준

- 이관 · 리팩토링 작업은 `characterization` 의 `npm test` 가 전부 통과하기 전에는 "완료"라고 보고하지 않는다.
- 테스트가 실패하면 실패한 케이스와 차이를 그대로 보고한다. 요약해서 "거의 됐다"고 말하지 않는다.
- 테스트를 통과시키려고 `characterization/` 의 테스트 코드나 스냅샷 파일을 고치지 않는다. 스냅샷을 바꿔야 한다고 판단되면 멈추고 묻는다.
- 레거시 동작이 버그로 보여도 이관 중에는 고치지 않는다. "의심 동작" 목록으로 따로 보고한다.
