# legacy/assignment-thymeleaf 비즈니스 규칙 후보

> 선행 문서: `docs/assignment/ARCHITECTURE.md`, `docs/assignment/ERD.md`
> 경로 약어: `J/` = `legacy/assignment-thymeleaf/src/main/java/com/example/assign/`,
> `T/` = `legacy/assignment-thymeleaf/src/main/resources/templates/`, `S/` = `db/mariadb/init/01-schema.sql`
> 셸 확인: 저장소 루트에서 `J=legacy/assignment-thymeleaf/src/main/java/com/example/assign` 을 먼저 실행한 뒤 `sed -n` 명령을 붙여 넣는다.
> 범위: 신규 배포 조건 · 마감 판정 · 재배포 허용/차단 · 제출 상태 전환. 그 밖에 이 흐름 안에서 적용되는 입력 정리 · 데이터 검증 규칙도 함께 적었다.

작성 기준

- 규칙은 코드(조건문 · SQL · 템플릿 조건식)에 있는 것만 올렸다. 상식으로 채운 규칙은 없다.
- 확신도 **확실** = 조건문 · SQL 로 확인. **추정** = 이름 · 주석 · 메시지로 짐작.
- 주석과 코드가 다르면 코드를 기준으로 규칙을 쓰고 주석은 비고에 남겼다.
- 근거가 주석뿐인 내용은 규칙으로 올리지 않았다. 9절 "주석만 있음" 목록에 모았다.
- "도달 불가"는 SQL · 스키마 제약상 그 분기가 실행될 수 없다는 뜻이다. 규칙으로는 남기되 비고에 적었다.

---

## 1. 공통 — 현재 시각

### BR-01 현재 시각 고정
- 규칙: JVM 시스템 속성 `app.clock` 이 `system` 이 아니면, 모든 마감 · 배포 시각 판정의 "현재"를 `2026-09-15 00:00:00` 으로 고정한다.
- 근거: `J/AppClock.java:9`, `J/AppClock.java:15-19`
- 근거 코드:
  ```java
  private static final String FIXED = "2026-09-15 00:00:00";
  if (sys != null && sys.equals("system")) {
  return LocalDateTime.parse(FIXED, FMT);
  ```
- 확신도: 확실
- 비고:
  - 주석(`:8`)에는 "2024-03 QA 기간 중 임시로 고정. 운영 반영 전 LocalDateTime.now() 로 되돌릴 것 (아직 안 되돌림)"이라고 적혀 있다. 그런데 고정값은 2024-03 이 아니라 2026-09-15 다.
  - BR-03 · BR-22~BR-26 · BR-31 · BR-32 의 마감 판정과 BR-08 의 `distributed_at` 이 모두 이 값을 쓴다. 이관할 때 `Clock` 주입으로 바꿀지 정해야 한다.

---

## 2. 신규 배포 (`POST /distributions`)

### BR-02 과제 존재
- 규칙: 선택한 과제 id 가 `assignment` 에 없으면 배포를 거부한다.
- 근거: `J/web/DistributionController.java:53-56`, `J/service/DistributionService.java:48-51`
- 근거 코드:
  ```java
  AssignmentRow a = assignmentDao.findById(assignmentId);
  if (a == null) {
      throw new IllegalArgumentException("존재하지 않는 과제입니다. (id=" + assignmentId + ")");
  ```
- 확신도: 확실
- 비고: 같은 검사가 컨트롤러와 서비스 두 곳에 있다. 메시지가 다르다(컨트롤러는 id 를 붙이지 않는다). 컨트롤러가 먼저 걸러서 서비스 쪽 분기는 화면 경로에서는 실행되지 않는다.

### BR-03 마감이 지난 과제는 새 배포 불가 (연장 제외)
- 규칙: 과제 마감 시각이 현재보다 이전이고 과제 상태가 `X`(연장)가 아니면 새 배포를 거부한다.
- 근거: `J/web/DistributionController.java:60-64`, `T/distribution_form.html:16`
- 근거 코드:
  ```java
  if (a.getDueAt() != null && a.getDueAt().isBefore(now)) {
      if (!"X".equals(a.getStatus())) {
  ```
  ```html
  th:disabled="${a.dueAt != null and a.dueAt.isBefore(now) and a.status != 'X'}"
  ```
- 확신도: 확실
- 비고:
  - 서비스 `distribute()`(`J/service/DistributionService.java:47-68`)에는 이 검사가 없다. 컨트롤러와 폼에서만 막는다.
  - `isBefore` 는 엄격 비교다. 마감 시각과 현재가 같으면 배포할 수 있다.
  - 새 배포에는 유예 기간이 없다. 재배포의 72시간 유예(BR-25)와 다르다.
  - 상태 비교가 대소문자 · 공백을 그대로 비교한다. 재배포는 `trim().toUpperCase()` 한 뒤 비교한다(BR-16).
  - `dueAt == null` 이면 검사를 건너뛴다. 스키마는 `due_at NOT NULL` 이라(`S/:86`) 이 분기는 도달 불가다.
  - 주석(`:59`) "마감 지난 과제는 새 배포 불가. 연장(X) 상태만 예외"는 코드와 일치한다.

### BR-04 학급 존재
- 규칙: 선택한 학급 id 가 `class` 에 없으면 배포를 거부한다.
- 근거: `J/service/DistributionService.java:52-55`
- 근거 코드:
  ```java
  ClassRow c = classDao.findById(classId);
  if (c == null) {
      throw new IllegalArgumentException("존재하지 않는 학급입니다. (id=" + classId + ")");
  ```
- 확신도: 확실
- 비고: 없음

### BR-05 삭제된 과제는 배포 불가
- 규칙: 과제 상태가 `D`(삭제)이면 배포를 거부한다.
- 근거: `J/service/DistributionService.java:56-58`
- 근거 코드:
  ```java
  if ("D".equals(a.getStatus())) {
      throw new IllegalStateException("삭제된 과제는 배포할 수 없습니다.");
  ```
- 확신도: 확실
- 비고: 컨트롤러가 마감 검사(BR-03)를 먼저 한다. 그래서 `D` 이면서 마감이 지난 과제는 "마감이 지난 과제" 메시지로 거부된다.

### BR-06 배포 폼의 과제 후보
- 규칙: 새 배포 폼에는 상태가 `O` · `X` · `C` 인 과제만 후보로 보여 준다.
- 근거: `J/dao/AssignmentDao.java:34-36`, `J/web/DistributionController.java:42`
- 근거 코드:
  ```java
  + " WHERE a.status IN ('O', 'X', 'C') "
  ```
- 확신도: 확실
- 비고:
  - `R`(검수중)은 폼 목록에서만 빠진다. 컨트롤러 · 서비스는 `R` 을 막지 않는다. 그래서 POST 를 직접 보내면 검수중 과제도 배포된다. 재배포는 `R` 을 막는다(BR-18).
  - `C`(종료)도 마감 전이면 새 배포가 된다. `C` 를 막는 조건은 어디에도 없다.

### BR-07 같은 과제 · 같은 학급 중복 배포 금지
- 규칙: 같은 (과제, 학급) 조합의 배포가 1건 이상 있으면 새 배포를 거부한다.
- 근거:
  ```bash
  sed -n '59,60p' $J/service/DistributionService.java
  sed -n '47,49p' $J/dao/DistributionDao.java
  ```
- 근거 코드:
  ```java
  if (distributionDao.countByAssignmentAndClass(assignmentId, classId) > 0) {
      throw new IllegalStateException("이미 이 학급에 배포된 과제입니다. 다시 내려면 배포 목록에서 재배포를 사용하세요.");
  ```
  ```java
  "SELECT COUNT(*) FROM distribution WHERE assignment_id = ? AND class_id = ?",
  ```
- 확신도: 확실
- 비고: DB 에 UNIQUE 제약이 없다(`S/:92-103`, 주석 `J/dao/DistributionDao.java:46`). 동시에 요청하면 중복 행이 생길 수 있다. 삭제(`D`) 과제의 배포도 COUNT 에 들어간다.

### BR-08 신규 배포 행의 값
- 규칙: 새 배포는 id 를 `MAX(id)+1` 로, `distributed_at` 을 현재 시각(BR-01)으로, `redistributed` 를 0 으로 저장한다. 저장 건수가 1 이 아니면 실패로 처리한다.
- 근거: `J/dao/DistributionDao.java:55-56`, `J/dao/DistributionDao.java:61`, `J/service/DistributionService.java:62-66`
- 근거 코드:
  ```java
  Long max = jdbc.queryForObject("SELECT COALESCE(MAX(id), 0) FROM distribution", Long.class);
  "INSERT INTO distribution (id, assignment_id, class_id, distributed_at, redistributed) VALUES (?, ?, ?, ?, 0)",
  if (n != 1) {
  ```
- 확신도: 확실
- 비고: `MAX(id)+1` 은 동시 요청에서 PK 충돌이 날 수 있다(ERD.md 4절 미확인 항목과 같음).

---

## 3. 재배포 입력 정리 (`POST /distributions/{id}/redistribute`)

### BR-09 작업자 ID 기본값 · 길이
- 규칙: 작업자 ID 가 비어 있으면(공백만 있는 경우 포함) `SYSTEM` 으로 기록한다. 20자를 넘으면 앞 20자로 자른다.
- 근거: `J/service/DistributionService.java:82-93`
- 근거 코드:
  ```java
  op = "SYSTEM";
  } else if (op.length() > 20) {
      op = op.substring(0, 20);
  ```
- 확신도: 확실
- 비고: 화면은 작업자를 `teacher-01` 로 고정해서 보낸다(`T/distributions.html:33`). 그래서 화면 경로에서는 이 분기가 실행되지 않는다.

### BR-10 작업자 ID 형식은 경고만
- 규칙: 작업자 ID 가 `SYSTEM` 이 아니고 `teacher-` · `admin-` 으로 시작하지 않으면 경고만 남기고 재배포는 계속한다.
- 근거: `J/service/DistributionService.java:94-96`
- 근거 코드:
  ```java
  if (!op.equals("SYSTEM") && !op.startsWith("teacher-") && !op.startsWith("admin-")) {
      warnings.add("작업자 ID 형식이 표준(teacher-, admin-)과 다릅니다: " + op);
  ```
- 확신도: 확실
- 비고: 없음

### BR-11 담당 교사가 아니어도 재배포 가능
- 규칙: 작업자가 학급 담당 교사도 아니고 `admin-` 도 아니면, 막지 않고 notes 에 기록만 한다.
- 근거: `J/service/DistributionService.java:259-261`
- 근거 코드:
  ```java
  if (!op.equals("SYSTEM") && teacherId.length() > 0 && !op.equals(teacherId) && !op.startsWith("admin-")) {
      notes.add("담당 교사(" + teacherId + ")가 아닌 작업자(" + op + ")의 재배포");
  ```
- 확신도: 확실
- 비고: 권한 검사는 없다. notes 는 `RedistributeResult` 에만 담기고 컨트롤러가 화면에 넘기지 않는다(`J/web/DistributionController.java:91-98`).

### BR-12 재배포 사유 정리
- 규칙: 사유의 줄바꿈 · 탭을 공백으로 바꾸고 연속 공백을 하나로 줄인다. 그 결과가 비면 `(사유 없음)` 으로 대체한다. 200자를 넘으면 앞 197자에 `...` 을 붙인다. `<` · `>` 는 `&lt;` · `&gt;` 로 바꾼다.
- 근거: `J/service/DistributionService.java:103-118`
- 근거 코드:
  ```java
  rsn = "(사유 없음)";
  rsn = rsn.substring(0, 197) + "...";
  rsn = rsn.replace("<", "&lt;").replace(">", "&gt;");
  ```
- 확신도: 확실
- 비고: 자른 다음에 치환한다. 그래서 치환 후 200자를 넘을 수 있다. Thymeleaf `th:text` 가 한 번 더 이스케이프하므로 화면에는 `&lt;` 가 그대로 글자로 보인다(`T/layout.html:29`).

### BR-13 사유 분류 (키워드 우선순위)
- 규칙: 사유에 `오류`·`장애` → 1(시스템 오류), `연장`·`추가` → 2(기간 연장), `정정`·`수정` → 3(문항 정정), `재시험`·`재평가` → 4(재평가) 순서로 먼저 걸리는 분류를 쓴다. `(사유 없음)` 이면 9, 나머지는 0(기타)이다.
- 근거: `J/service/DistributionService.java:119-130`
- 근거 코드:
  ```java
  if (rsn.contains("오류") || rsn.contains("장애")) {
      reasonCategory = 1;
  } else if (rsn.contains("연장") || rsn.contains("추가")) {
  ```
- 확신도: 확실
- 비고:
  - 로직에 영향을 주는 분류는 1(BR-26 재허용)과 4(BR-35 전원 재제출)뿐이다. 2 · 3 은 notes 문구만 바꾼다.
  - 사유 "연장"(분류 2)은 과제 상태 `X`(연장)와 무관하다. 마감 판정을 완화하지 않는다.
  - 순서 때문에 "재평가 중 오류" 같은 사유는 4 가 아니라 1 이 된다.
  - 사용자가 `(사유 없음)` 을 직접 입력해도 9 가 된다.

---

## 4. 재배포 허용 · 차단

### BR-14 배포 존재
- 규칙: 배포 id 가 없으면 `E101` 로 거부한다.
- 근거: `J/service/DistributionService.java:153-157`
- 근거 코드:
  ```java
  if (dist == null) {
      result.setOk(false);
      result.setCode("E101");
  ```
- 확신도: 확실
- 비고: `findById` 는 `assignment` · `class` 와 INNER JOIN 한다(`J/dao/DistributionDao.java:28-29`). 그래서 과제나 학급 행이 없는 배포도 여기서 `E101` 이 된다.

### BR-15 배포에 연결된 과제 존재
- 규칙: 배포에 연결된 과제가 없으면 `E102` 로 거부한다.
- 근거: `J/service/DistributionService.java:164-167`
- 근거 코드:
  ```java
  AssignmentRow asg = assignmentDao.findById(dist.getAssignmentId());
  if (asg == null) {
      result.setCode("E102");
  ```
- 확신도: 확실
- 비고: 도달 불가다. BR-14 의 INNER JOIN 과 FK(`S/:101`) 때문에 과제가 없으면 이미 `E101` 이 된다.

### BR-16 과제 상태 정규화
- 규칙: 재배포에서는 과제 상태를 `trim().toUpperCase()` 한 뒤 판정한다.
- 근거: `J/service/DistributionService.java:172-176`
- 근거 코드:
  ```java
  st = st.trim().toUpperCase();
  ```
- 확신도: 확실
- 비고:
  - 정규화는 재배포(`redistribute`)에만 있다. 신규 배포 컨트롤러(`J/web/DistributionController.java:61`), 서비스 `distribute()` 의 삭제 검사(`J/service/DistributionService.java:56`, `"D".equals(a.getStatus())`), 템플릿(`T/distribution_form.html:16`, `T/distributions.html:32`)은 원래 값을 그대로 비교한다. 소문자 `x` 는 재배포에서는 연장이고 신규 배포에서는 연장이 아니다.
  - (추정, DB 를 실행해 확인하지 않음) SQL 조건(`J/dao/AssignmentDao.java:29`, `:36`, `J/dao/DistributionDao.java:33`)은 테이블 collation 이 `utf8mb4_unicode_ci`(`S/:90`)라 대소문자를 구분하지 않을 가능성이 있다. 그러면 소문자 `x` 는 폼 목록(`IN ('O','X','C')`)에는 나오지만 자바 · 템플릿 비교에서는 연장이 아니다.

### BR-17 삭제된 과제는 재배포 불가
- 규칙: 과제 상태가 `D` 이면 `E103` 으로 거부한다.
- 근거: `J/service/DistributionService.java:198-204`
- 근거 코드:
  ```java
  case "D":
      statusLabel = "삭제";
      result.setOk(false);
  ```
- 확신도: 확실
- 비고: 배포 목록이 `D` 과제의 배포를 숨긴다(BR-45). 그래서 화면에서는 버튼이 보이지 않는다.

### BR-18 검수중 과제는 재배포 불가
- 규칙: 과제 상태가 `R`(검수중)이면 `E104` 로 거부한다.
- 근거: `J/service/DistributionService.java:194-196`, `J/service/DistributionService.java:215-218`
- 근거 코드:
  ```java
  if (underReview) {
      result.setOk(false);
      result.setCode("E104");
  ```
- 확신도: 확실
- 비고: 신규 배포는 서버에서 `R` 을 막지 않는다(BR-06).

### BR-19 상태 코드가 비었거나 모르는 값이면 진행중으로 간주
- 규칙: 과제 상태가 비어 있거나 `O`·`X`·`C`·`R`·`D` 가 아니면 경고를 남기고 `O`(진행중)와 같이 처리한다.
- 근거: `J/service/DistributionService.java:205-212`
- 근거 코드:
  ```java
  warnings.add("과제 상태 코드가 비어 있습니다. 진행중으로 간주합니다.");
  warnings.add("알 수 없는 과제 상태 코드: " + st + " (진행중으로 간주)");
  ```
- 확신도: 확실
- 비고: 없음

### BR-20 종료(`C`) 과제도 마감 판정만 통과하면 재배포 가능
- 규칙: 과제 상태가 `C`(종료)여도 막지 않고 `O` 와 같은 마감 판정(BR-22~BR-25)을 적용한다.
- 근거: `J/service/DistributionService.java:190-193`, `J/service/DistributionService.java:222-225`, `J/service/DistributionService.java:326-328`
- 근거 코드:
  ```java
  case "C":
      statusLabel = "종료";
      closedByTeacher = true;
  ```
  ```java
  if (allowed && closedByTeacher && dueBucket >= 3) {
      notes.add("종료 과제이지만 유예 기간 내 → 허용");
  ```
- 확신도: 확실
- 비고:
  - `closedByTeacher` 는 notes 문구에만 쓰이고 허용 판정에는 쓰이지 않는다.
  - `:222` 의 `closedByTeacher && !extended` 에서 `!extended` 는 항상 참이다. 한 상태값이 `C` 와 `X` 를 동시에 가질 수 없기 때문이다.
  - 주석(`:223`) "종료 처리된 과제라도 마감 후 3일 안이면 아래 마감 판정에 맡긴다"는 코드(72시간)와 일치한다.
  - 스키마 주석은 `C=마감`(`S/:87`)이고 코드 라벨은 "종료"다.

### BR-21 학급이 없어도 재배포 진행
- 규칙: 배포 대상 학급이 없으면 경고를 남기고 재배포를 계속한다.
- 근거: `J/service/DistributionService.java:242-245`
- 근거 코드:
  ```java
  if (cls == null) {
      className = "(삭제된 학급 #" + dist.getClassId() + ")";
      warnings.add("배포 대상 학급이 존재하지 않습니다. 재배포는 진행하지만 알림은 발송되지 않습니다.");
  ```
- 확신도: 확실
- 비고: 도달 불가다. BR-14 의 INNER JOIN `class` 때문에 학급이 없으면 이미 `E101` 이 된다. 경고 문구의 "알림"은 모듈 어디에도 발송 코드가 없다(9절).

### BR-22 마감 시각이 없으면 마감 규칙 미적용
- 규칙: 과제에 마감 시각이 없으면 경고를 남기고 마감 규칙 없이 재배포를 허용한다.
- 근거: `J/service/DistributionService.java:269-273`, `J/service/DistributionService.java:310-311`
- 근거 코드:
  ```java
  if (due == null) {
  if (dueBucket == -1) {
      allowed = true;
  ```
- 확신도: 확실
- 비고: 도달 불가다. `due_at NOT NULL`(`S/:86`).

### BR-23 마감 전 · 정각이면 재배포 허용
- 규칙: 현재가 마감 이전이거나 마감과 같은 분이면 재배포를 허용한다.
- 근거: `J/service/DistributionService.java:278-289`, `J/service/DistributionService.java:312-313`
- 근거 코드:
  ```java
  } else if (minutesAfterDue == 0) {
      dueBucket = 2;
  } else if (dueBucket <= 2) {
  ```
- 확신도: 확실
- 비고: `Duration.toMinutes()` 는 0 쪽으로 절삭한다(`J/service/DistributionService.java:275-277`). 그래서 마감 전후 59초 이내(마감 59초 전 ~ 마감 후 59초)가 모두 0분이 되어 bucket 2 "마감 시각 정각"으로 판정된다. 신규 배포(BR-03)는 초 단위 `isBefore` 로 판정한다.

### BR-24 연장(`X`) 과제는 마감 경과와 무관하게 허용
- 규칙: 과제 상태가 `X` 이면 마감 후 경과 시간과 상관없이 재배포를 허용한다.
- 근거: `J/service/DistributionService.java:314-316`
- 근거 코드:
  ```java
  } else if (extended) {
      allowed = true;
      notes.add("상태 '연장' → 마감 경과에도 재배포 허용");
  ```
- 확신도: 확실
- 비고: 없음

### BR-25 마감 후 72시간이 지나면 재배포 불가
- 규칙: 과제 상태가 `X` 가 아니고 마감 후 72시간 이상 지났으면 재배포를 거부한다(코드는 BR-27).
- 근거: `J/service/DistributionService.java:318-321`
- 근거 코드:
  ```java
  // 재배포는 마감 후 7일 이내에만 허용한다 (교무 지침 §4.2)
  if (hoursAfterDue >= 72) {
      allowed = false;
  ```
- 확신도: 확실
- 비고:
  - **주석과 코드 불일치**: 주석은 "7일 이내", 코드는 72시간(3일)이다. 규칙은 코드 기준으로 적었다. 마감 구간 표(BR-29)의 문구도 72시간부터 "유예 종료"로 표시해 코드 쪽과 맞는다.
  - `toHours()` 가 절삭하므로 71시간 59분 경과는 허용된다.
  - "교무 지침 §4.2" 원문은 저장소에 없다.
  - 거부 메시지가 "과제 상태를 '연장'으로 바꾼 뒤 다시 시도하세요"라고 안내한다. 하지만 이 모듈에는 과제 상태를 바꾸는 코드가 없다(ERD.md 3.1).

### BR-26 두 번째 재배포는 연장 상태 또는 시스템 오류 사유일 때만
- 규칙: 이미 재배포된 배포(`redistributed = 1`)는 과제 상태가 `X` 이거나 사유 분류가 1(시스템 오류)일 때만 다시 재배포할 수 있다. 그 밖에는 거부한다.
- 근거: `J/service/DistributionService.java:329-337`, `T/distributions.html:32`
- 근거 코드:
  ```java
  if (allowed && dist.getRedistributed() == 1) {
      if (extended) {
      } else if (reasonCategory == 1) {
  ```
  ```html
  th:if="${d.redistributed == 0 or d.assignmentStatus == 'X'}">
  ```
- 확신도: 확실
- 비고:
  - **같은 규칙이 다르게 구현됨**: 템플릿은 `redistributed == 0` 이거나 `X` 일 때만 버튼을 보여 준다. 그래서 "시스템 오류 사유로 두 번째 재배포" 경로는 화면에서 쓸 수 없다. 서버는 허용한다.
  - 이 검사는 `allowed` 가 참일 때만 한다. 그래서 BR-25 로 이미 거부된 경우에는 실행되지 않는다.
  - 템플릿 주석(`T/distributions.html:30`) "아직 재배포 안 했거나, 연장 상태인 과제만"은 템플릿 조건과 일치한다.
  - 버튼 조건(`T/distributions.html:32`)은 마감 여부를 보지 않는다. 마감 판정(BR-23~BR-25)은 서버에서만 한다. 그래서 `redistributed == 0` 인 배포는 마감 후 72시간이 지나도 버튼이 보이고, 눌러야 E105 로 거부된다.

### BR-27 거부 코드는 재배포 이력으로 정한다
- 규칙: 재배포를 거부하면, 이미 재배포된 배포는 `E106`, 아니면 `E105` 를 코드로 돌려준다.
- 근거: `J/service/DistributionService.java:339-342`
- 근거 코드:
  ```java
  result.setCode(dist.getRedistributed() == 1 ? "E106" : "E105");
  ```
- 확신도: 확실
- 비고: 코드는 거부 사유와 무관하게 정해진다. 이미 재배포된 배포가 72시간 유예를 넘겨 거부되면 메시지는 유예 경과(BR-25)인데 코드는 `E106` 이다.

### BR-28 재배포 거부는 예외가 아니라 결과로 돌려준다
- 규칙: 재배포 거부(`E101`~`E107`)는 예외를 던지지 않고 `ok=false` 결과로 돌려준다. 컨트롤러는 이를 error flash 로 보여 준다.
- 근거: `J/service/DistributionService.java:155-159`, `J/web/DistributionController.java:91-98`
- 근거 코드:
  ```java
  if (r.isOk()) {
  } else {
      ra.addFlashAttribute("error", r.getMessage());
  ```
- 확신도: 확실
- 비고: `@Transactional`(`:71`) 메서드에서 예외 없이 반환하므로 롤백되지 않는다. 다만 거부 경로에서는 쓰기가 없어서 영향은 없다.

### BR-29 마감 구간(bucket) 분류
- 규칙: 현재 − 마감 시각으로 구간을 나눈다(아래 표). 재배포 허용에는 구간 ≤ 2 인지와 72시간 경계(BR-25)만 쓰인다.
- 근거: `J/service/DistributionService.java:275-302`
- 근거 코드:
  ```java
  if (minutesAfterDue < -72 * 60) {
  } else if (hoursAfterDue < 72) {
  } else if (hoursAfterDue < 24 * 7) {
  ```
- 확신도: 확실
- 비고:

  | bucket | 조건 | 표시 문구 |
  |---|---|---|
  | -1 | 마감 없음 | 마감 없음 |
  | 0 | 마감 72시간 초과 전 | 마감 전 (N일 이상 남음) |
  | 1 | 마감 24~72시간 전 | 마감 임박 |
  | 2 | 마감 24시간 이내 전 ~ 정각 | 마감 당일 / 마감 시각 정각 |
  | 3 | 마감 후 0~72시간 미만 | 유예 기간 |
  | 4 | 마감 후 72시간~7일 미만 | 유예 종료 |
  | 5 | 마감 후 7일~30일 미만 | N일 경과 |
  | 6 | 마감 후 30일 이상 | N개월 이상 경과 |

  - bucket 2 의 라벨은 "마감 당일"이다. 하지만 조건은 달력의 날이 아니라 "24시간 이내"다.
  - 이 판정 결과는 notes 에만 담기고 화면으로 넘어가지 않는다.

---

## 5. 제출 상태

### BR-30 제출 상태 판정
- 규칙: 제출 1건의 상태는 `submitted_at` · `score` 의 NULL 여부로 정한다.
  - 둘 다 NULL → 미제출
  - 시각 NULL · 점수 있음 → 채점완료(제출시각없음), 경고
  - 시각 있음 · 점수 NULL → 채점전
  - 둘 다 있음 → 채점완료
- 근거: `J/service/DistributionService.java:407-422`
- 근거 코드:
  ```java
  if (at == null && sc == null) {
  } else if (at == null && sc != null) {
  } else if (at != null && sc == null) {
  ```
- 확신도: 확실
- 비고:
  - 스키마는 `submitted_at NOT NULL`(`S/:109`)이다. 그래서 "미제출"과 "채점완료(제출시각없음)"는 스키마상 생길 수 없다. "미제출" 학생은 `submission` 행이 없는 학생일 텐데, 이 모듈은 학급의 학생 명단을 읽지 않는다.
  - 상태는 DB 컬럼이 아니다. 재배포 때 계산만 한다.
  - 주석(`:412`) "이상 데이터 : 채점된 것으로 본다"는 코드와 일치한다.

### BR-31 지연 제출
- 규칙: 제출 시각이 마감 시각보다 뒤이면 지연 제출로 센다.
- 근거: `J/service/DistributionService.java:428-433`
- 근거 코드:
  ```java
  if (due != null && at.isAfter(due)) {
      late++;
      state = state + "/지연";
  ```
- 확신도: 확실
- 비고: 마감과 같은 시각이면 정상이다. 지연 여부는 재제출 대상 계산에 영향을 주지 않는다. 제출이 모두 마감 이후면 경고를 추가한다(`:572-573`).

### BR-32 미래 시각 제출 경고
- 규칙: 제출 시각이 현재(BR-01)보다 뒤이면 경고를 남긴다.
- 근거: `J/service/DistributionService.java:424-426`
- 근거 코드:
  ```java
  if (at.isAfter(now)) {
      futureSubmit++;
  ```
- 확신도: 확실
- 비고: 현재 시각이 고정(BR-01)이라 2026-09-15 이후 제출은 모두 "미래"로 잡힌다.

### BR-33 화면의 제출 수는 `submitted_at` 기준
- 규칙: 배포 목록의 제출 수는 `submitted_at IS NOT NULL` 인 제출만 센다.
- 근거: `J/dao/DistributionDao.java:26`
- 근거 코드:
  ```java
  + " (SELECT COUNT(*) FROM submission s WHERE s.distribution_id = d.id AND s.submitted_at IS NOT NULL) AS submitted_cnt "
  ```
- 확신도: 확실
- 비고: **같은 값이 다르게 계산됨**: 재배포의 유지 건수(`kept = total - notSubmitted`, `J/service/DistributionService.java:485`)는 "시각 NULL · 점수 있음" 행도 셈한다. 코드가 차이를 notes 로 남긴다(`:636-638`).

### BR-34 재배포는 제출 데이터를 바꾸지 않는다
- 규칙: 재배포는 `submission` 행을 추가 · 수정 · 삭제하지 않는다.
- 근거: `J/dao/DistributionDao.java:69-72`(모듈의 유일한 `submission` 접근이 SELECT), `J/service/DistributionService.java:484`
- 근거 코드:
  ```java
  "SELECT id, distribution_id, student_id, submitted_at, score FROM submission WHERE distribution_id = ? ORDER BY id ASC",
  ```
- 확신도: 확실
- 비고: 모듈 안에 `submission` 을 쓰는 SQL 이 없다는 것으로 확인했다(ERD.md 3.5). 주석 `:484` "기존 제출을 지우지 않는다"와 일치한다.

### BR-35 재제출 대상 인원
- 규칙: 재제출 대상 수를 다음과 같이 계산한다.
  - 기본은 미제출 수다.
  - 과제 상태가 `X` 면 채점전 수를 더한다.
  - 사유 분류가 4(재평가)면 제출 전체 수로 덮어쓴다.
- 근거: `J/service/DistributionService.java:486-496`
- 근거 코드:
  ```java
  int reopen = notSubmitted;
  reopen = notSubmitted + submittedNoScore;
  reopen = total;
  ```
- 확신도: 확실
- 비고:
  - 결과 메시지의 숫자로만 쓰인다(`:648`). 학생별로 다시 낼 수 있게 여는 코드는 없다. "재제출 가능" 상태를 저장하는 곳도 없다.
  - 재평가 분류가 연장보다 뒤에 있어서 우선한다.
  - 스키마가 `submitted_at NOT NULL`(`S/:109`)이고 이 모듈은 학급 학생 명단을 읽지 않으므로, `notSubmitted` 는 스키마상 항상 0 이다(BR-30 비고). 그래서 기본 재제출 대상(`reopen = notSubmitted`, `J/service/DistributionService.java:486`)은 0 이고, `X` 일 때만 채점전 수가, 분류 4 일 때만 전체 수가 결과 숫자에 반영된다. 유지 건수(BR-36)도 항상 전체 제출 수다.
  - **메시지와 코드 불일치**: 제출이 0건이면 notes 에 "학급 전원이 재제출 대상"을 남긴다(`:509-511`). 하지만 `reopen` 은 0 이고 메시지도 "재제출 대상 0명"이다.
  - 주석(`:493`) "기존 점수는 그대로 유지"는 점수를 바꾸는 코드가 없다는 점에서 일치한다.

### BR-36 유지되는 기존 제출 수
- 규칙: 유지 건수는 전체 제출 수에서 미제출 수를 뺀 값이다.
- 근거: `J/service/DistributionService.java:485`, `J/service/DistributionService.java:512`
- 근거 코드:
  ```java
  int kept = total - notSubmitted;
  result.setKeptSubmissions(kept);
  ```
- 확신도: 확실
- 비고: 사유 분류와 상관없이 채점완료가 있으면 "그대로 유지" 경고를 추가한다. 사유 분류가 4 이면 추가하지 않는다(`:497-499`).

### BR-37 상태 전환 요약 (문구만)
- 규칙: 재배포 결과 notes 에 다음 전환을 문구로 적는다.
  - 미제출 → 재제출 대상
  - 채점전 → 과제 상태 `X` 면 재제출 가능, 아니면 유지
  - 채점완료 → 사유 분류 4 면 재제출 가능(점수 유지), 아니면 유지
- 근거: `J/service/DistributionService.java:590-613`
- 근거 코드:
  ```java
  if (extended && submittedNoScore > 0) {
  if (reasonCategory == 4) {
      transition.append("채점완료 ").append(graded).append("명 → 재제출 가능(점수 유지)");
  ```
- 확신도: 확실
- 비고: 전환은 DB 에 반영되지 않는다. notes 는 화면에 나오지 않는다(BR-11 비고). "미제출 → 재제출 대상" 문구(`J/service/DistributionService.java:590-592`)는 `notSubmitted > 0` 일 때만 나오는데, 스키마상 도달 불가다(BR-35 비고).

---

## 6. 재배포 반영 · 결과

### BR-38 재배포 플래그 갱신
- 규칙: 허용된 재배포는 `distribution.redistributed` 를 1 로 바꾼다. 갱신 건수가 0 이면 `E107` 로 실패를 돌려준다.
- 근거: `J/service/DistributionService.java:620-624`, `J/dao/DistributionDao.java:66`
- 근거 코드:
  ```java
  return jdbc.update("UPDATE distribution SET redistributed = 1 WHERE id = ?", id);
  if (updated == 0) {
      result.setCode("E107");
  ```
- 확신도: 확실
- 비고:
  - 플래그는 0/1 이다. 재배포 횟수를 세지 않는다. 두 번째 재배포 뒤에도 1 이다.
  - `distributed_at` 은 갱신하지 않는다. 재배포 시각 · 사유 · 작업자를 저장하는 곳이 없다. 이 값들은 `System.out` 감사 로그(`:693`)에만 남는다.

### BR-39 성공 결과 코드
- 규칙: 성공하면 경고 0건은 `OK`, 1~2건은 `OK-W`, 3건 이상은 `OK-W<건수>` 로 한다.
- 근거: `J/service/DistributionService.java:685-691`
- 근거 코드:
  ```java
  } else if (warnings.size() <= 2) {
      result.setCode("OK-W");
  } else {
  ```
- 확신도: 확실
- 비고: 성공이어도 경고는 flash `warnings` 로 화면에 나온다(`J/web/DistributionController.java:93-95`).

---

## 7. 데이터 검증 (경고만, 차단 없음)

### BR-40 학생 ID 형식
- 규칙: 학생 ID 가 `STU-` 로 시작하는 8자이고 뒤 4자가 모두 숫자가 아니면 형식 오류로 세고 경고한다.
- 근거: `J/service/DistributionService.java:381-397`, `J/service/DistributionService.java:506-507`
- 근거 코드:
  ```java
  } else if (!sid.startsWith("STU-")) {
  } else if (sid.length() != 8) {
  ```
- 확신도: 확실
- 비고: 길이가 8 로 고정이다. 그래서 `STU-10001` 같은 5자리 번호는 형식 오류가 된다. 스키마 주석은 `STU-1001 형식`(`S/:108`)만 적었다.

### BR-41 같은 학생 중복 제출
- 규칙: 한 배포에 같은 학생 ID 의 제출이 2건 이상이면 중복 건수를 세어 경고한다.
- 근거: `J/service/DistributionService.java:399-403`, `J/service/DistributionService.java:503-504`
- 근거 코드:
  ```java
  if (seenStudents.contains(sid)) {
      dupStudent++;
  ```
- 확신도: 확실
- 비고: 중복 행도 미제출 · 채점 집계에 각각 들어간다.

### BR-42 점수 범위
- 규칙: 점수가 0 미만이거나 100 초과이면 경고한다. 그래도 합계 · 최저 · 최고 · 등급에는 포함한다.
- 근거: `J/service/DistributionService.java:443-447`
- 근거 코드:
  ```java
  if (sc.compareTo(BigDecimal.ZERO) < 0 || sc.compareTo(new BigDecimal("100")) > 0) {
      scoreOutOfRange++;
  ```
- 확신도: 확실
- 비고: 없음

### BR-43 등급 구간
- 규칙: 점수 90 이상 A, 80 이상 B, 70 이상 C, 60 이상 D, 그 밖은 F 로 나눈다.
- 근거: `J/service/DistributionService.java:458-473`
- 근거 코드:
  ```java
  if (sc.compareTo(new BigDecimal("90")) >= 0) {
  } else if (sc.compareTo(new BigDecimal("60")) >= 0) {
  ```
- 확신도: 확실
- 비고: 등급은 notes 와 요약 문구에만 쓰인다. 저장하지 않는다. 성적 집계 저장 프로시저(`db/mssql/init/`)의 등급 기준과 같은지는 확인하지 않았다.

### BR-44 통계 기반 경고
- 규칙: 재배포할 때 다음 조건이면 경고를 남긴다. 차단하지는 않는다.
  - 제출률 0% 또는 50% 미만
  - 채점자 평균 40점 미만
  - F 가 채점자의 절반 초과
- 근거: `J/service/DistributionService.java:523-535`, `J/service/DistributionService.java:554-561`
- 근거 코드:
  ```java
  int rate = total == 0 ? 0 : (int) Math.round(submittedAny * 100.0 / total);
  if (avg.compareTo(new BigDecimal("40")) < 0) {
  if (bandF > graded / 2) {
  ```
- 확신도: 확실
- 비고:
  - 제출률 분모는 `submission` 행 수다. 학급 인원이 아니다. `submitted_at NOT NULL` 이면 사실상 항상 100% 다(BR-30 비고).
  - `bandF > graded / 2` 는 정수 나눗셈이다. 경고 문구는 "절반 이상"인데 조건은 "절반 초과"에 가깝다. 예를 들어 graded=4, F=2 면 경고하지 않는다.
  - 평균 95점 초과 note 는 채점자 5명 이상일 때만(`:556`), "전원 같은 등급" note 는 3명 이상일 때만(`:583`) 남긴다.

### BR-45 삭제된 과제는 목록에서 숨김
- 규칙: 과제 목록과 배포 목록은 과제 상태가 `D` 인 행을 보여 주지 않는다.
- 근거: `J/dao/AssignmentDao.java:29`, `J/dao/DistributionDao.java:33`
- 근거 코드:
  ```java
  + " WHERE a.status <> 'D' "
  ```
- 확신도: 확실
- 비고: 과제 목록에서는 마감이 지났고 `X` 가 아닌 과제를 회색(`past`)으로 표시한다(`T/assignments.html:21`). 상태 라벨에 `D` 매핑이 없는 것은 이 필터 때문으로 보인다(추정).

### BR-46 담당 교사 ID 형식
- 규칙: 학급 담당 교사 ID 가 비었거나, `teacher-` 로 시작하지 않거나, 길이가 10 이 아니면 경고한다.
- 근거: `J/service/DistributionService.java:252-257`
- 근거 코드:
  ```java
  } else if (!teacherId.startsWith("teacher-")) {
  } else if (teacherId.length() != 10) {
  ```
- 확신도: 확실
- 비고: 길이 10 은 `teacher-` + 2자리(`teacher-01`) 형식으로 보인다(추정). 작업자 ID 검사(BR-10)에는 길이 조건이 없다.

---

## 8. 매직 넘버 · 코드값

| 값 | 위치 | 추정 의미 |
|---|---|---|
| `"2026-09-15 00:00:00"` | `J/AppClock.java:9` | QA 용 고정 현재 시각 (BR-01) |
| `"system"` | `J/AppClock.java:16` | 실제 시계를 쓰게 하는 `app.clock` 값 |
| `'O'` | `J/service/DistributionService.java:182`, `J/dao/AssignmentDao.java:36`, `S/:87` | 과제 상태: 진행중 |
| `'X'` | `J/web/DistributionController.java:61`, `J/service/DistributionService.java:185`, `T/distribution_form.html:16`, `T/distributions.html:32` | 과제 상태: 연장 |
| `'C'` | `J/service/DistributionService.java:190`, `J/dao/AssignmentDao.java:36` | 과제 상태: 종료 (스키마 주석은 "마감") |
| `'R'` | `J/service/DistributionService.java:194` | 과제 상태: 검수중 |
| `'D'` | `J/service/DistributionService.java:56`, `:198`, `J/dao/AssignmentDao.java:29`, `J/dao/DistributionDao.java:33` | 과제 상태: 삭제 |
| `0` / `1` (`redistributed`) | `J/dao/DistributionDao.java:61`, `:66`, `J/service/DistributionService.java:329` | 재배포 안 함 / 함 |
| `"SYSTEM"` | `J/service/DistributionService.java:88` | 작업자 미지정 기본값 |
| `"teacher-"`, `"admin-"` | `J/service/DistributionService.java:94`, `:254`, `:259` | 작업자 · 교사 ID 접두어 |
| `"teacher-01"` | `T/distributions.html:33` | 화면이 보내는 고정 작업자 ID |
| `20` | `J/service/DistributionService.java:90-91` | 작업자 ID 최대 길이 (`teacher_id VARCHAR(20)` 과 같은 값) |
| `200`, `197` | `J/service/DistributionService.java:111-112` | 사유 최대 길이, `...` 3자를 뺀 절삭 길이 |
| `1`, `2`, `3`, `4`, `9`, `0` | `J/service/DistributionService.java:119-130` | 사유 분류: 시스템 오류 · 기간 연장 · 문항 정정 · 재평가 · 없음 · 기타 |
| `40` (제목) | `J/service/DistributionService.java:234-235` | 결과 메시지용 과제 제목 최대 길이 |
| `10` | `J/service/DistributionService.java:256` | 담당 교사 ID 표준 길이 (`teacher-NN`) |
| `-72 * 60`, `-24 * 60` | `J/service/DistributionService.java:278`, `:281` | 마감 3일 전 · 1일 전 (분) |
| `72` | `J/service/DistributionService.java:290`, `:319` | 재배포 유예 시간 (주석은 7일) |
| `24 * 7` | `J/service/DistributionService.java:293` | 마감 후 7일 (문구 구간) |
| `24 * 30`, `/ 24 / 30` | `J/service/DistributionService.java:296`, `:301` | 마감 후 30일 · 월 환산 (문구 구간) |
| `-1` ~ `6` (`dueBucket`) | `J/service/DistributionService.java:271-300` | 마감 구간 (BR-29) |
| `"STU-"`, `8`, `4` | `J/service/DistributionService.java:381`, `:383`, `:386` | 학생 ID 접두어 · 전체 길이 · 숫자 시작 위치 |
| `0`, `100` (점수) | `J/service/DistributionService.java:443`, `:454` | 점수 범위 · 만점 |
| `90`, `80`, `70`, `60` | `J/service/DistributionService.java:458-467` | 등급 A~D 하한 |
| `100`, `80`, `50` (제출률) | `J/service/DistributionService.java:525-529` | 제출률 구간 (%) |
| `40`, `95` (평균) | `J/service/DistributionService.java:554`, `:556` | 문항 오류 의심 · 고득점 기준 평균 |
| `5`, `3` (채점자 수) | `J/service/DistributionService.java:556`, `:583` | 평균 95 초과 · 전원 동일 등급 note 최소 인원 |
| `30` | `J/service/DistributionService.java:672` | 요약에 표시할 학생 최대 수 |
| `2` (경고 수) | `J/service/DistributionService.java:687` | `OK-W` 와 `OK-W<n>` 경계 |
| `E101`~`E107` | `J/service/DistributionService.java:156`, `:167`, `:201`, `:217`, `:341`, `:623` | 재배포 실패 코드: 배포 없음 · 과제 없음 · 삭제 · 검수중 · 유예 경과 · 재배포 이력 · 플래그 갱신 실패 |

---

## 9. 주석만 있음 · 주석과 코드 불일치

| 위치 | 주석 · 문구 | 코드 | 처리 |
|---|---|---|---|
| `J/service/DistributionService.java:318` | "재배포는 마감 후 7일 이내에만 허용한다 (교무 지침 §4.2)" | `hoursAfterDue >= 72` 이면 거부(3일) | 불일치. 규칙은 코드 기준(BR-25). 교무 지침 원문은 저장소에 없음 |
| `J/AppClock.java:8` | "2024-03 QA 기간 중 임시로 고정 … (아직 안 되돌림)" | 고정값이 `2026-09-15` | 날짜 불일치(BR-01) |
| `J/service/DistributionService.java:245` | "알림은 발송되지 않습니다" | 모듈에 알림 발송 코드가 없다 | 주석만 있음. 알림 규칙은 올리지 않음 |
| `J/service/DistributionService.java:70` | "v1.4.2 : 재배포 조건 정리 + 제출 유지 정책 반영 (기존 v1.3 코드 그대로 두고 아래에 덧붙임)" | 메서드 안에서 v1.3 코드와 v1.4.2 코드를 구분할 표시가 없다 | 주석만 있음 |
| `J/service/DistributionService.java:321` | "과제 상태를 '연장'으로 바꾼 뒤 다시 시도하세요" | 모듈에 과제 상태를 바꾸는 코드가 없다 | 주석만 있음(메시지). 상태 변경 주체는 미확인 |
| `J/service/DistributionService.java:510` | "제출 데이터 없음 → 학급 전원이 재제출 대상" | `reopen` 은 0 | 불일치(BR-35) |
| `J/service/DistributionService.java:560` | "채점자 절반 이상이 F" | `bandF > graded / 2`(초과) | 경계 불일치(BR-44) |
| `S/:87` | "O=진행 C=마감" | `O`·`X`·`C`·`R`·`D` 를 분기한다 | 스키마 주석이 코드보다 좁다(ERD.md 1.1) |

## 10. 같은 규칙이 여러 곳에 다르게 구현된 곳

| 규칙 | 구현 A | 구현 B | 차이 |
|---|---|---|---|
| 마감 경과 판정 | 신규 배포: `dueAt.isBefore(now)` 초 단위, 유예 없음 (`J/web/DistributionController.java:60`) | 재배포: 분 · 시간 절삭, 72시간 유예 (`J/service/DistributionService.java:275-321`) | 경계 정밀도 · 유예 유무 |
| 지연 제출 | `at.isAfter(due)` (`J/service/DistributionService.java:428`) | - | 마감 정각 제출은 정상 |
| 연장 상태 비교 | 원래 값 `"X".equals` (`J/web/DistributionController.java:61`, 템플릿) | `trim().toUpperCase()` (`J/service/DistributionService.java:176`) | 소문자 · 공백 처리 |
| 두 번째 재배포 허용 | 서버: `X` 또는 사유 분류 1 (`J/service/DistributionService.java:329-337`) | 화면: `redistributed == 0 or X` 일 때만 버튼 (`T/distributions.html:32`) | 시스템 오류 경로가 화면에 없음 |
| 검수중(`R`) 차단 | 재배포: `E104` (`J/service/DistributionService.java:215`) | 신규 배포: 폼 목록에서만 제외 (`J/dao/AssignmentDao.java:36`) | 신규 배포 서버 검사 없음 |
| 마감 지난 과제 신규 배포 차단 | 컨트롤러 (`J/web/DistributionController.java:60-64`) | 서비스 `distribute` 에는 없음 | 서비스 직접 호출 시 우회 |
| 과제 존재 검사 | 컨트롤러 (`J/web/DistributionController.java:53-56`) | 서비스 (`J/service/DistributionService.java:48-51`) | 중복, 메시지 다름 |
| 제출 건수 | 목록: `submitted_at IS NOT NULL` (`J/dao/DistributionDao.java:26`) | 재배포: `total - notSubmitted` (`J/service/DistributionService.java:485`) | 시각 NULL · 점수 있음 행 처리 |

## 11. 미확인

- 과제 상태(`assignment.status`)를 `X` · `C` · `R` · `D` 로 바꾸는 주체. 이 모듈에는 쓰기 SQL 이 없다.
- `submission` 행을 만드는 주체와 "미제출" 학생의 실제 표현 방식(행 없음 vs NULL 행).
- 교무 지침 §4.2 원문, 그리고 7일 · 72시간 중 어느 쪽이 의도인지.
- `AppClock` 고정 시각이 운영에서도 쓰이는지(`-Dapp.clock=system` 을 주는지). `Dockerfile` 은 확인하지 않았다.
- 등급 구간(BR-43)이 MS-SQL 성적 집계 프로시저와 같은지.

## 12. 검증 이력

교차 검증: `docs/assignment/CROSS-CHECK.md`(같은 모듈을 독립 분석한 문서)와 이 문서를 비교해 불일치 6건을 코드 · 스키마로 확인했다. 확인은 모델이 했고 사용자 판정이 아니다. 확인일 2026-09-29.

- BR-23 (수정): CROSS-CHECK CX-10 은 "정각"만 적었다. 이 문서의 "같은 분" 판정이 맞았다(`J/service/DistributionService.java:275-289`). 마감 59초 전까지 같은 구간이라는 점을 비고에 보탰다.
- BR-16 (수정): 정규화가 재배포에만 있다는 점이 맞았다(CX-15 는 범위 구분 없음). 서비스 `distribute()` 의 삭제 검사도 원래 값 비교라는 점(`:56`)과 SQL collation 영향(추정)을 비고에 보탰다.
- BR-26 (수정): CROSS-CHECK CX-24 에만 있던 "재배포 버튼은 마감 여부를 보지 않는다"(`T/distributions.html:32`)를 코드로 확인해 비고에 추가했다.
- BR-35 (수정): BR-30 의 스키마 도달 불가 판정이 맞아서 그 결과를 반영했다. `notSubmitted` 가 스키마상 항상 0 이라 기본 재제출 대상도 0 이다.
- BR-37 (수정): 위와 같은 이유로 "미제출 → 재제출 대상" 문구가 도달 불가임을 비고에 적었다.
- BR-15 · BR-21 (변경 없음): E102 와 "학급 없음 경고"는 도달 불가가 맞았다. `JOIN` 이 INNER 이고(`J/dao/DistributionDao.java:28-29`) FK 가 있다(`S/:101-102`). CX-12 · CX-22 는 이 JOIN 을 반영하지 않았다.
- BR-22 (변경 없음): 마감 시각 null 분기는 도달 불가가 맞았다(`due_at NOT NULL`, `S/:86`). CX-09 · CX-05 는 스키마를 반영하지 않았다.
- BR-30 (변경 없음): "미제출"과 "채점완료(제출시각없음)"는 도달 불가가 맞았다(`submitted_at NOT NULL`, `S/:109`). CX-25 는 스키마를 반영하지 않았다.
- 한쪽에만 있던 규칙(BR-09, BR-12, BR-28, BR-32, BR-39~BR-46 등)은 코드 원문(`J/service/DistributionService.java`)과 대조했고 고칠 곳이 없었다. CROSS-CHECK 가 범위 밖으로 뺀 항목이다.
