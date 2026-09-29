# item-bank 아키텍처 — 문항 검색 화면(`search.php`)

## 분석 범위
- 대상: `legacy/item-bank-php/search.php` 화면 하나와 이 화면이 부르는 코드
  - `inc/db.php` · `inc/layout.php` · `vendor/simplelog/Log.php`
  - DB 객체: `db/mariadb/init/01-schema.sql` 의 `unit` · `tag` · `item` · `item_tag` · `v_item_public`
- 제외: `register.php` · `units.php` 의 본문, 과제 배포 테이블(`class` · `assignment` · `distribution` · `submission`)
  - 과제 배포 화면은 이 모듈에 없습니다. `legacy/item-bank-php` 전체에서 `distribut|assignment|배포|과제` 를 검색한 결과가 0건이었습니다.
- 실행 환경: `php:7.4-apache` 이미지(`legacy/item-bank-php/Dockerfile:1`)입니다. 소스는 `/var/www/html/` 에 복사되고(`Dockerfile:14`), `display_errors = Off` 입니다(`Dockerfile:9`).

## 1. 진입점

| 종류 | 위치 | 근거 |
|---|---|---|
| HTTP 요청 | `GET /search.php` — 파라미터 `q` · `unit` · `level` · `tag` · `sort` · `dir` · `page` | `search.php:5-12`(주석), 실제로 읽는 곳은 `search.php:53-80` |
| 화면 진입 링크 1 | 공통 메뉴 `'search' => array('search.php', '문항 검색')` | `inc/layout.php:10`, 출력은 `inc/layout.php:31-33` |
| 화면 진입 링크 2 | 홈 메뉴 `<a href="search.php">문항 검색</a>` | `index.php:9` |
| 자기 호출 1 | 검색 폼 `<form method="get" action="search.php" ...>` | `search.php:632` |
| 자기 호출 2 | 초기화 링크 `<a href="search.php">초기화</a>` | `search.php:639` |
| 자기 호출 3 | 열 머리글 정렬 링크 `'search.php' . ... 'sort=' ...` | `search.php:414-416` |
| 자기 호출 4 | 페이지 링크 `$pageQsPrefix = 'search.php' ... 'page='` | `search.php:430`, 출력은 `search.php:690-700` |
| 스크립트 최상위 실행부 | `render_header` → `db_connect` → `buildSearchQuery` → `renderSearchForm` → `runSearchQuery` → `renderResultTable` → `render_footer` | `search.php:709-731` |

- 배치 · 저장 프로시저 호출은 이 화면에 없습니다. SQL 은 모두 `mysqli` 로 직접 실행합니다.
- POST 처리는 없습니다. 폼은 GET 으로 자기 자신을 다시 부릅니다(`search.php:632`).

## 2. 의존 관계

### 파일 간 포함(require)
| 호출하는 쪽 | 호출되는 쪽 | 근거 |
|---|---|---|
| `search.php` | `inc/db.php`(`db_connect` · `h`) | `search.php:20` |
| `search.php` | `inc/layout.php`(`render_header` · `render_footer`) | `search.php:21` |
| `search.php` | `vendor/simplelog/Log.php`(`SimpleLog\Log`) | `search.php:22`, `search.php:24` |
| `inc/layout.php` | `inc/db.php` 의 `h()` | `inc/layout.php:17` 등. `layout.php` 는 `db.php` 를 require 하지 않으므로 호출하는 쪽이 먼저 `db.php` 를 포함해야 합니다(`search.php:20-21` 순서). |

> 계층 구분(컨트롤러 · 서비스 · DAO)은 없습니다. 요청 파싱 · 검증 · SQL 조립 · 실행 · HTML 출력이 `search.php` 한 파일의 함수 4개와 최상위 실행부에 모여 있습니다.

### 호출 순서
```
search.php (최상위, :709-731)
├─ render_header('문항 검색', 'search')        inc/layout.php:7      (:709)
├─ db_connect()                                inc/db.php:7          (:713)
│    └─ 실패(RuntimeException) → 오류 문구 출력 · render_footer · exit   (:714-719)
├─ buildSearchQuery($_GET, $conn)              search.php:42         (:722)
│    ├─ SELECT name, grade FROM unit WHERE code = ?        (:125)  unit 파라미터가 있을 때만
│    ├─ SELECT code, name, grade FROM unit ORDER BY ...    (:156)  단원 선택 목록
│    ├─ SELECT name FROM tag ORDER BY id ASC               (:178)  태그 선택 목록
│    ├─ h()  — 요약 · 링크 · 폼 HTML 이스케이프             inc/db.php:34
│    ├─ Log::debug(...)                                    (:103, :528)
│    └─ Log::error + throw RuntimeException (바인딩 개수 불일치)  (:536-539)
├─ renderSearchForm($built)                    search.php:629        (:723)
├─ runSearchQuery($built, $conn)               search.php:571        (:724)
│    ├─ COUNT(*) FROM v_item_public WHERE ...              (:577-597)
│    └─ SELECT ... FROM v_item_public WHERE ... ORDER BY ... LIMIT 20 OFFSET n  (:600-621)
├─ renderResultTable($built, $result)          search.php:647        (:725)
├─ catch RuntimeException → Log::error · 일반 오류 문구     (:726-729)
└─ render_footer()                             inc/layout.php:38     (:731)
```

### 참고 사항
- `buildSearchQuery` 가 SQL 조립과 화면용 HTML(요약 · 경고 · 폼 select · 정렬 링크 · 페이지 링크 접두어) 생성을 함께 합니다(`search.php:351-516`). 이관할 때 조회 조건과 표시를 나눠야 합니다.
- 폼은 결과 조회보다 먼저 출력됩니다(`search.php:723` → `:724`). 그래서 `runSearchQuery` 가 실패해도 폼은 화면에 남고, 그 아래에 "검색 중 오류가 발생했습니다." 가 나옵니다(`search.php:728`).
- 선택 목록(단원 · 태그)은 뷰가 아니라 `unit` · `tag` 테이블을 바로 읽습니다(`search.php:156`, `:178`). 결과는 `v_item_public` 뷰에서 읽습니다(`search.php:522`).
- `Log::setThreshold(Log::LEVEL_INFO)`(`search.php:27`)이므로 `Log::debug` 호출(`:103`, `:528`)은 기록되지 않습니다. `Log::write` 가 `$level < self::$threshold` 이면 반환하기 때문입니다(`vendor/simplelog/Log.php:45-47`). 로그는 `error_log` 로만 남습니다(`Log.php:52`).
- 스키마 주석은 "검색 화면 search.php 와 등록 화면이 모두 이 뷰를 기준으로 삼는다"(`db/mariadb/init/01-schema.sql:51`)라고 적혀 있습니다. 하지만 `register.php` · `units.php` 에서 `v_item_public` 을 검색하면 0건입니다. 이 문장은 search.php 에만 맞습니다(등록 화면은 범위 밖이라 본문은 읽지 않았습니다).
