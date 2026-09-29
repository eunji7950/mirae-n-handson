# legacy/assignment-thymeleaf 데이터 흐름 · ERD

> 범위: `legacy/assignment-thymeleaf` 가 접근하는 MariaDB `itembank` 테이블.
> 선행 문서: `docs/assignment-thymeleaf/ARCHITECTURE.md`
> 경로 약어: `J/` = `legacy/assignment-thymeleaf/src/main/java/com/example/assign/`, `S/` = `db/mariadb/init/01-schema.sql`

- 이 모듈은 `JdbcTemplate` 문자열 SQL 로만 DB 에 접근한다. SQL 이 있는 곳은 `J/dao/` 의 DAO 3개뿐이다.
  - `J/service` · `J/web` · `J/model` 에서 `jdbc` · SQL 키워드를 찾지 못했다.
- 모듈 안에는 스키마 파일(`schema.sql` 등)이 없다. 스키마 근거는 공용 초기화 스크립트 `S/` 를 썼다.
  - 모듈이 같은 DB 를 쓴다는 근거: `S/:73`, `legacy/assignment-thymeleaf/src/main/resources/application.properties:2`

## 1. 테이블 목록

모듈 SQL 에 나오는 테이블 5개다. `item` · `tag` · `item_tag` · `v_item_public` 은 스키마에는 있지만(`S/:20`, `:35`, `:42`, `:52`) 이 모듈이 쓰지 않아서 뺐다.

| 테이블 | 주요 컬럼 (스키마 기준) | 모듈 SQL 이 쓰는 컬럼 | 근거: 스키마 | 근거: SQL |
|---|---|---|---|---|
| `assignment` 과제 | `id` INT PK, `title` VARCHAR(200), `unit_id` INT FK, `due_at` DATETIME, `status` CHAR(1) 기본 `'O'` | `id` · `title` · `unit_id` · `due_at` · `status` | `S/:82-90` | `J/dao/AssignmentDao.java:21-24`, `J/dao/DistributionDao.java:24`, `:28` |
| `unit` 단원 | `id` INT PK, `code` VARCHAR(16) UNIQUE, `name` VARCHAR(100), `grade` TINYINT | `id` · `code` · `name` (`grade` 는 쓰지 않음) | `S/:11-18` | `J/dao/AssignmentDao.java:22`, `:24` |
| `class` 학급 | `id` INT PK, `name` VARCHAR(50), `teacher_id` VARCHAR(20) | `id` · `name` · `teacher_id` | `S/:75-80` | `J/dao/ClassDao.java:21`, `:25`, `J/dao/DistributionDao.java:25`, `:29` |
| `distribution` 배포 | `id` INT PK, `assignment_id` INT FK, `class_id` INT FK, `distributed_at` DATETIME, `redistributed` TINYINT 기본 0 | 전부 | `S/:92-103` | `J/dao/DistributionDao.java:24-29`, `:49`, `:55`, `:61`, `:66` |
| `submission` 제출 | `id` INT PK, `distribution_id` INT FK, `student_id` VARCHAR(20)(`STU-1001` 형식), `submitted_at` DATETIME NOT NULL, `score` DECIMAL(5,1) NULL | 전부 | `S/:105-115` | `J/dao/DistributionDao.java:26`, `:71` |

### 1.1 스키마와 코드가 어긋나는 곳

| 항목 | 스키마 | 코드 | 근거 |
|---|---|---|---|
| `assignment.status` 코드값 | 주석에 `O=진행 C=마감` 만 있다 | `O`(진행중) · `X`(연장) · `C`(종료) · `R`(검수중) · `D`(삭제)를 분기한다 | `S/:87`, `J/service/DistributionService.java:182-198`, `J/dao/AssignmentDao.java:29`, `:36` |
| `submission.submitted_at` NULL 허용 | `NOT NULL` | `IS NOT NULL` 로 세고, `null` 이면 "미제출"로 본다. 스키마상 `null` 이 없어 "미제출" 분기는 도달 불가다(BUSINESS-RULES.md BR-30) | `S/:109`, `J/dao/DistributionDao.java:26`, `J/service/DistributionService.java:404-415` |
| `assignment.due_at` NULL 허용 | `NOT NULL` | `dueAt == null` 이면 마감 규칙을 건너뛴다. 스키마상 도달 불가다(BR-03 · BR-22) | `S/:86`, `J/web/DistributionController.java:60`, `J/service/DistributionService.java:269`, `:310` |
| (`assignment_id`, `class_id`) 중복 방지 | UNIQUE 제약이 없다 | 코드가 COUNT 로 막는다고 주석에 적혀 있다 | `S/:92-103`, `J/dao/DistributionDao.java:46-49`, `J/service/DistributionService.java:59` |
| `distribution.id` 채번 | AUTO_INCREMENT 가 없다 | `MAX(id)+1` 로 정한다 | `S/:93`, `J/dao/DistributionDao.java:55-56` |

- `X` · `R` · `D` 값이 실제 데이터에 있는지는 미확인이다. 시드(`db/mariadb/init/02-seed.sql`)는 확인하지 않았다.
- `submitted_at` 이 `NULL` 인 행이 실제로 생길 수 있는지(다른 모듈이 스키마를 바꿨는지 등)도 미확인이다.

## 2. 테이블 관계

- **선언**: `S/` 에 `FOREIGN KEY` 로 선언된 관계.
- **추정**: FK 선언은 없고, 컬럼 이름만 보고 짐작한 관계. 모듈 코드에는 이 관계를 쓰는 JOIN 이 없다.
- 선언된 FK 4개는 모두 모듈 코드에서도 JOIN(또는 상관 하위 쿼리)으로 쓴다. 근거는 아래 표에 있다.

```mermaid
erDiagram
    unit ||--o{ assignment : "선언 fk_assignment_unit"
    assignment ||--o{ distribution : "선언 fk_distribution_assignment"
    class ||--o{ distribution : "선언 fk_distribution_class"
    distribution ||--o{ submission : "선언 fk_submission_distribution"

    unit {
        INT id PK
        VARCHAR code UK
        VARCHAR name
        TINYINT grade
    }
    assignment {
        INT id PK
        VARCHAR title
        INT unit_id FK
        DATETIME due_at
        CHAR status
    }
    class {
        INT id PK
        VARCHAR name
        VARCHAR teacher_id "참조 대상 미확인"
    }
    distribution {
        INT id PK
        INT assignment_id FK
        INT class_id FK
        DATETIME distributed_at
        TINYINT redistributed
    }
    submission {
        INT id PK
        INT distribution_id FK
        VARCHAR student_id "참조 대상 미확인"
        DATETIME submitted_at
        DECIMAL score
    }
```

| 관계 | 구분 | 스키마 근거 | 코드 JOIN 근거 |
|---|---|---|---|
| `unit` 1 — N `assignment` (`assignment.unit_id` → `unit.id`) | 선언 | `S/:89` | `J/dao/AssignmentDao.java:24` (`LEFT JOIN unit u ON u.id = a.unit_id`) |
| `assignment` 1 — N `distribution` (`distribution.assignment_id` → `assignment.id`) | 선언 | `S/:101` | `J/dao/DistributionDao.java:28` (`JOIN`), `J/dao/AssignmentDao.java:23` (하위 쿼리 `COUNT(DISTINCT d.class_id)`) |
| `class` 1 — N `distribution` (`distribution.class_id` → `class.id`) | 선언 | `S/:102` | `J/dao/DistributionDao.java:29` (`JOIN`) |
| `distribution` 1 — N `submission` (`submission.distribution_id` → `distribution.id`) | 선언 | `S/:114` | `J/dao/DistributionDao.java:26` (하위 쿼리 `COUNT(*)`), `:71` (`WHERE distribution_id = ?`) |
| `assignment` N — M `class` (`distribution` 을 거침) | 추정 | FK 2개의 조합 | `countByAssignmentAndClass` 가 (과제, 학급) 쌍을 1건으로 다룬다: `J/dao/DistributionDao.java:46-49` |
| `class.teacher_id` → 교사 테이블 | 미확인 | MariaDB 스키마에 교사 테이블이 없다 | JOIN 없음. 값만 읽는다: `J/dao/ClassDao.java:21`, `:38` |
| `submission.student_id` → 학생 테이블 | 미확인 | MariaDB 스키마에 학생 테이블이 없다 | JOIN 없음. 형식(`STU-1001`)만 코드에서 검사한다: `J/service/DistributionService.java:377` 부근 |

- `distribution` 을 읽는 쿼리는 `assignment` · `class` 와 INNER `JOIN` 이다(`J/dao/DistributionDao.java:28-29`). 과제나 학급 행이 없는 배포는 `findById` 에서 조회되지 않아 재배포가 E101 로 끝난다. 그래서 재배포의 E102 · "학급 없음 경고" 분기는 도달 불가다(BUSINESS-RULES.md BR-14 · BR-15 · BR-21).
- `assignment` ↔ `unit` 은 코드에서 `LEFT JOIN` 이다. FK 와 `NOT NULL` 이 있으니 결과는 INNER JOIN 과 같다고 보이지만, 왜 `LEFT` 로 썼는지는 미확인이다.
- MS-SQL 스키마(`db/mssql/init/01-schema.sql`)에는 `dbo.student` · `dbo.class` 가 있다. 하지만 DB 가 달라서 위 두 미확인 관계의 근거로 쓰지 않았다.

## 3. 읽기 · 쓰기 위치

"호출하는 곳"은 DAO 메서드를 부르는 서비스 · 컨트롤러 함수다.

### 3.1 `assignment`

| 종류 | DAO 함수 | SQL 근거 | 호출하는 곳 |
|---|---|---|---|
| SELECT | `AssignmentDao.findAllForList()` — `status <> 'D'` | `J/dao/AssignmentDao.java:27-31` | `AssignmentController.list()` `J/web/AssignmentController.java:28` |
| SELECT | `AssignmentDao.findAllForSelect()` — `status IN ('O','X','C')` | `J/dao/AssignmentDao.java:34-38` | `DistributionController.form()` `J/web/DistributionController.java:42` |
| SELECT | `AssignmentDao.findById(long)` | `J/dao/AssignmentDao.java:41-48` | `DistributionController.create()` `:53`, `DistributionService.distribute()` `J/service/DistributionService.java:48`, `DistributionService.redistribute()` `:164` |
| SELECT (JOIN) | `DistributionDao.findAllForList()` · `findById(long)` — `a.title` · `a.status` · `a.due_at` | `J/dao/DistributionDao.java:24`, `:28`, `:33` | 3.4 참고 |
| INSERT · UPDATE · DELETE | 없음 | - | - |

### 3.2 `unit`

| 종류 | DAO 함수 | SQL 근거 | 호출하는 곳 |
|---|---|---|---|
| SELECT (JOIN) | `AssignmentDao` 의 3개 조회 모두(`BASE_SELECT`) | `J/dao/AssignmentDao.java:22`, `:24` | 3.1 과 같음 |
| INSERT · UPDATE · DELETE | 없음 | - | - |

### 3.3 `class`

| 종류 | DAO 함수 | SQL 근거 | 호출하는 곳 |
|---|---|---|---|
| SELECT | `ClassDao.findAll()` | `J/dao/ClassDao.java:20-21` | `DistributionController.form()` `J/web/DistributionController.java:43` |
| SELECT | `ClassDao.findById(long)` | `J/dao/ClassDao.java:24-25` | `DistributionService.distribute()` `J/service/DistributionService.java:52`, `DistributionService.redistribute()` `:239` |
| SELECT (JOIN) | `DistributionDao.findAllForList()` · `findById(long)` — `c.name` | `J/dao/DistributionDao.java:25`, `:29` | 3.4 참고 |
| INSERT · UPDATE · DELETE | 없음 | - | - |

### 3.4 `distribution`

| 종류 | DAO 함수 | SQL 근거 | 호출하는 곳 |
|---|---|---|---|
| SELECT | `DistributionDao.findAllForList()` — 과제 `status <> 'D'` | `J/dao/DistributionDao.java:31-35` | `DistributionService.listAll()` `J/service/DistributionService.java:39` ← `DistributionController.list()` `J/web/DistributionController.java:34` |
| SELECT | `DistributionDao.findById(long)` | `J/dao/DistributionDao.java:38-39` | `DistributionService.get()` `:43`(호출처 없음), `DistributionService.redistribute()` `:153`, `:631`(갱신 뒤 재조회) |
| SELECT | `DistributionDao.countByAssignmentAndClass(long, long)` | `J/dao/DistributionDao.java:47-49` | `DistributionService.distribute()` `:59` |
| SELECT | `DistributionDao.nextId()` — `MAX(id)` | `J/dao/DistributionDao.java:54-55` | `DistributionService.distribute()` `:62` |
| SELECT (하위 쿼리) | `AssignmentDao` `BASE_SELECT` 의 `class_cnt` | `J/dao/AssignmentDao.java:23` | 3.1 과 같음 |
| **INSERT** | `DistributionDao.insert(...)` — `redistributed = 0` 고정 | `J/dao/DistributionDao.java:59-61` | `DistributionService.distribute()` `:63` (`@Transactional` `:46`) ← `DistributionController.create()` `:67` |
| **UPDATE** | `DistributionDao.markRedistributed(long)` — `redistributed = 1` | `J/dao/DistributionDao.java:65-66` | `DistributionService.redistribute()` `:620` (`@Transactional` `:71`) ← `DistributionController.redistribute()` `:86` |
| DELETE | 없음 | - | - |

### 3.5 `submission`

| 종류 | DAO 함수 | SQL 근거 | 호출하는 곳 |
|---|---|---|---|
| SELECT | `DistributionDao.findSubmissions(long)` | `J/dao/DistributionDao.java:69-71` | `DistributionService.redistribute()` `J/service/DistributionService.java:348` |
| SELECT (하위 쿼리) | `DistributionDao` `BASE_SELECT` 의 `submitted_cnt` | `J/dao/DistributionDao.java:26` | 3.4 의 `findAllForList` · `findById` 와 같음 |
| INSERT · UPDATE · DELETE | 없음 | - | - |

- 재배포는 기존 제출을 지우지 않는다고 주석에 적혀 있다(`J/service/DistributionService.java:484`). 실제로 `submission` 에 쓰는 SQL 도 모듈에 없다.

### 3.6 요약

- 이 모듈이 쓰는(INSERT · UPDATE) 테이블은 `distribution` 하나다. 쓰는 곳도 두 군데뿐이다: `distribute` 의 INSERT, `redistribute` 의 UPDATE.
- 나머지 4개 테이블은 읽기만 한다. 그 데이터를 누가 쓰는지는 이 모듈 밖이라 미확인이다.

## 4. 미확인 목록

- `class.teacher_id` 가 가리키는 대상. MariaDB 에 교사 테이블이 없다.
- `submission.student_id` 가 가리키는 대상. MariaDB 에 학생 테이블이 없다.
- `assignment.status` 의 `X` · `R` · `D` 가 실제 데이터에 있는지, 그리고 스키마 주석이 왜 `O` · `C` 만 적었는지.
- `submission.submitted_at` 이 `NULL` 인 데이터가 실제로 있는지. 스키마는 `NOT NULL` 이다.
- `assignment` · `unit` · `class` · `submission` 에 쓰는 주체(다른 레거시 모듈 · 수작업 · 배치). 이 모듈에는 쓰기 SQL 이 없다.
- `DistributionService.redistribute` 에서 DAO 호출 줄(`:153`, `:164`, `:239`, `:348`, `:620`, `:631`) 말고 다른 데이터 접근이 있는지. `jdbc` · SQL 키워드 검색으로는 나오지 않았다.
- `nextId()` + `insert()` 를 동시에 실행할 때 id 가 충돌하는지. 트랜잭션 격리 수준 설정은 확인하지 않았다.
