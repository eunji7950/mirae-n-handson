# CROSS-CHECK: legacy/assignment-thymeleaf 비즈니스 규칙 추출

- 대상: `legacy/assignment-thymeleaf/src/main` 의 Java · Thymeleaf 코드 (docs/ 와 외부 라이브러리 폴더는 읽지 않음)
- 범위: 교사가 학급에 과제를 배포할 때의 조건, 마감 판정, 재배포 허용 · 차단, 제출 상태 전환
- 기준: 코드. 주석과 코드가 다르면 코드를 따른다.
- 경로는 별도 표기가 없으면 `legacy/assignment-thymeleaf/src/main/java/com/example/assign/` 기준이다. 약칭: `Svc` = `service/DistributionService.java`, `Ctl` = `web/DistributionController.java`.

## 0. 먼저 알아 둘 것

- **주석과 코드가 다른 곳**: `Svc:318` 주석은 "마감 후 7일 이내에만 허용"이지만 코드는 72시간(3일)이다 → CX-11 은 72시간으로 썼다.
- **시각 기준**: 기본값이 실제 시각이 아니라 고정 시각 `2026-09-15 00:00:00` 이다 → CX-08.
- **재배포가 실제로 바꾸는 것은 `distribution.redistributed` 플래그 하나뿐**이다. 제출 상태 "전환"은 화면 안내용 계산이며 저장되지 않는다 → CX-24.

## 1. 배포(생성) 조건

### CX-01 존재하지 않는 과제로는 배포할 수 없다
- 근거: `Ctl:54-57`, `Svc:49-51`
```java
if (a == null) {
    throw new IllegalArgumentException("존재하지 않는 과제입니다. (id=" + assignmentId + ")");
```

### CX-02 존재하지 않는 학급으로는 배포할 수 없다
- 근거: `Svc:52-55`
```java
ClassRow c = classDao.findById(classId);
if (c == null) {
    throw new IllegalArgumentException("존재하지 않는 학급입니다. (id=" + classId + ")");
```

### CX-03 삭제(`D`) 과제는 배포할 수 없다
- 근거: `Svc:56-58`
```java
if ("D".equals(a.getStatus())) {
    throw new IllegalStateException("삭제된 과제는 배포할 수 없습니다.");
```

### CX-04 같은 과제를 같은 학급에 두 번 배포할 수 없다 (다시 내려면 재배포를 쓴다)
- 근거: `Svc:59-61`, `dao/DistributionDao.java:46`
```java
if (distributionDao.countByAssignmentAndClass(assignmentId, classId) > 0) {
    throw new IllegalStateException("이미 이 학급에 배포된 과제입니다. 다시 내려면 배포 목록에서 재배포를 사용하세요.");
```
- DB 에 UNIQUE 제약이 없어서 코드에서 막는다(`DistributionDao:46` 주석).

### CX-05 마감이 지난 과제는 새로 배포할 수 없다. 연장(`X`) 상태만 예외이며, 새 배포에는 유예 기간이 없다
- 근거: `Ctl:60-61`
```java
if (a.getDueAt() != null && a.getDueAt().isBefore(now)) {
    if (!"X".equals(a.getStatus())) {
```
- 마감 시각이 null 이면 이 검사를 건너뛴다.
- 이 검사는 컨트롤러에만 있다. `Svc.distribute()` 는 마감을 검사하지 않는다.
- `isBefore(now)` 이므로 마감 시각 정각은 아직 "지난 것"이 아니다.

### CX-06 배포 폼의 과제 목록에는 진행중 · 연장 · 종료 과제만 나오고, 마감이 지난 항목은 연장이 아니면 선택 불가다
- 근거: `dao/AssignmentDao.java:36`, `resources/templates/distribution_form.html:16`
```java
+ " WHERE a.status IN ('O', 'X', 'C') "
th:disabled="${a.dueAt != null and a.dueAt.isBefore(now) and a.status != 'X'}"
```
- 검수중(`R`) 과제는 폼에서 빠지지만, 서비스에는 `R` 을 막는 코드가 없다(CX-03 은 `D` 만 막는다). 폼을 거치지 않은 요청은 `R` 과제도 통과한다.

### CX-07 새 배포는 배포 시각 = 현재 시각, 재배포 플래그 = 0 으로 저장된다
- 근거: `Svc:63`, `dao/DistributionDao.java:61`
```java
int n = distributionDao.insert(id, assignmentId, classId, AppClock.now());
"INSERT INTO distribution (id, assignment_id, class_id, distributed_at, redistributed) VALUES (?, ?, ?, ?, 0)"
```

## 2. 마감 판정

### CX-08 "현재 시각"은 기본적으로 `2026-09-15 00:00:00` 고정값이다. JVM 옵션 `-Dapp.clock=system` 일 때만 시스템 시각을 쓴다
- 근거: `AppClock.java:9`, `AppClock.java:15-19`
```java
private static final String FIXED = "2026-09-15 00:00:00";
if (sys != null && sys.equals("system")) {
    return LocalDateTime.now().withNano(0);
```
- 주석은 "운영 반영 전 되돌릴 것 (아직 안 되돌림)"이라고 한다. 모든 마감 판정(CX-05, CX-09~CX-13)이 이 값을 기준으로 한다.

### CX-09 마감 시각이 없는 과제에는 마감 규칙을 적용하지 않는다 (재배포 허용)
- 근거: `Svc:269-273`, `Svc:310-311`
```java
if (due == null) {
    dueBucket = -1;
    warnings.add("과제에 마감 시각이 없습니다. 마감 규칙을 적용하지 않습니다.");
```

### CX-10 재배포 시 마감 전 · 마감 시각 정각은 "마감 전 구간"(bucket 0~2)이다
- 근거: `Svc:278-289`
```java
} else if (minutesAfterDue < 0) {
    dueBucket = 2;
} else if (minutesAfterDue == 0) {
    dueBucket = 2;
```
- 마감 72시간 초과 전 → 0, 24~72시간 전 → 1, 24시간 이내 및 정각 → 2.

### CX-11 마감 후 72시간(3일) 미만은 유예 기간(bucket 3), 72시간 이상은 유예 종료(bucket 4 이상)다
- 근거: `Svc:290-296`, `Svc:318-319`
```java
} else if (hoursAfterDue < 72) {
    dueBucket = 3;
    dueText = "마감 후 " + hoursAfterDue + "시간 경과 (유예 기간)";
```
- `hoursAfterDue` 는 `Duration.toHours()` 로 내림한 값이다(`Svc:276`). 72시간 0분~59분도 72로 계산되어 유예 종료다.
- 주석(`Svc:318`)의 "7일"은 코드와 다르다. 코드는 72시간이다. 7일(`24 * 7`)은 bucket 4/5 의 문구 구분에만 쓰인다(`Svc:293`).

## 3. 재배포가 허용되는 경우와 막히는 경우

판정 순서는 배포 조회 → 과제 조회 → 과제 상태 → 마감 판정 → 재배포 허용 판정 → 플래그 갱신이다. 먼저 걸린 조건에서 끝난다.

### CX-12 존재하지 않는 배포 · 배포에 연결된 과제가 없으면 재배포할 수 없다 (E101 · E102)
- 근거: `Svc:154-159`, `Svc:165-171`
```java
result.setCode("E101");
result.setMessage("존재하지 않는 배포입니다. (id=" + distributionId + ")");
```

### CX-13 삭제(`D`) 과제는 재배포할 수 없다 (E103)
- 근거: `Svc:198-204`
```java
case "D":
    result.setCode("E103");
    result.setMessage("삭제된 과제는 재배포할 수 없습니다. [" + asg.getTitle() + "]");
```

### CX-14 검수중(`R`) 과제는 마감과 관계없이 재배포할 수 없다 (E104)
- 근거: `Svc:194-197`, `Svc:215-221`
```java
if (underReview) {
    result.setCode("E104");
    result.setMessage("검수중인 과제는 재배포할 수 없습니다. 검수 완료 후 다시 시도하세요. [" + asg.getTitle() + "]");
```

### CX-15 과제 상태가 비어 있거나 알 수 없는 코드이면 진행중으로 간주하고 계속 진행한다 (경고만)
- 근거: `Svc:176`, `Svc:205-212`
```java
case "":
    warnings.add("과제 상태 코드가 비어 있습니다. 진행중으로 간주합니다.");
default:
    warnings.add("알 수 없는 과제 상태 코드: " + st + " (진행중으로 간주)");
```
- 상태는 `trim().toUpperCase()` 후 비교한다(소문자 `x` 도 연장).

### CX-16 종료(`C`) 상태 자체는 재배포를 막지 않는다. 마감 규칙(CX-17~CX-19)에 따라 판정한다
- 근거: `Svc:222-225`, `Svc:326-328`
```java
if (closedByTeacher && !extended) {
    // 종료 처리된 과제라도 마감 후 3일 안이면 아래 마감 판정에 맡긴다
if (allowed && closedByTeacher && dueBucket >= 3) {
```

### CX-17 마감 전 · 정각(bucket ≤ 2)이거나 마감 시각이 없으면 재배포를 허용한다
- 근거: `Svc:310-313`
```java
if (dueBucket == -1) {
    allowed = true;
} else if (dueBucket <= 2) {
    allowed = true;
```

### CX-18 연장(`X`) 상태는 마감이 얼마나 지났든 재배포를 허용한다
- 근거: `Svc:314-316`
```java
} else if (extended) {
    allowed = true;
    notes.add("상태 '연장' → 마감 경과에도 재배포 허용");
```

### CX-19 연장이 아니면서 마감 후 72시간 이상 지난 과제는 재배포할 수 없다 (E105)
- 근거: `Svc:317-322`, `Svc:341`
```java
if (hoursAfterDue >= 72) {
    allowed = false;
    denyReason = "마감 후 유예 기간이 지났습니다. (" + dueText + ") 과제 상태를 '연장'으로 바꾼 뒤 다시 시도하세요.";
```
- 에러 코드는 이미 재배포된 건이면 E106, 아니면 E105 다(`Svc:341`).

### CX-20 이미 재배포된 건(`redistributed = 1`)은 과제가 연장 상태이거나 사유가 시스템 오류일 때만 다시 재배포할 수 있다 (E106)
- 근거: `Svc:329-338`
```java
if (allowed && dist.getRedistributed() == 1) {
    if (extended) {
    } else if (reasonCategory == 1) {
```
- 이 검사는 마감 판정(CX-17~CX-19)을 통과한 뒤에 적용된다. 그래서 시스템 오류 사유여도 연장이 아니고 유예(72시간)가 지났으면 CX-19 에서 먼저 막힌다.

### CX-21 재배포 사유는 키워드로 분류하며, 앞선 분류가 우선한다 (오류·장애 → 연장·추가 → 정정·수정 → 재시험·재평가)
- 근거: `Svc:119-130`
```java
if (rsn.contains("오류") || rsn.contains("장애")) {
    reasonCategory = 1;
} else if (rsn.contains("연장") || rsn.contains("추가")) {
```
- 예: "연장 재시험" 은 분류 2(기간 연장)이고 4(재평가)가 아니다. 분류 1 만 CX-20 의 예외가 된다.

### CX-22 사유 · 작업자 · 학급 정보의 결함은 재배포를 막지 않는다 (기본값 대체 또는 경고)
- 근거: `Svc:87-89`, `Svc:108-110`, `Svc:242-245`
```java
op = "SYSTEM";
rsn = "(사유 없음)";
warnings.add("배포 대상 학급이 존재하지 않습니다. 재배포는 진행하지만 알림은 발송되지 않습니다.");
```
- 빈 작업자 → `SYSTEM`, 빈 사유 → `(사유 없음)`. 작업자 ID 형식 불일치, 담당 교사 불일치는 경고 또는 노트만 남긴다(`Svc:94-96`, `Svc:259-261`).

### CX-23 플래그 갱신이 0건이면 재배포는 실패한다 (E107). 성공하면 `redistributed = 1` 이 된다
- 근거: `Svc:620-626`, `dao/DistributionDao.java:65-66`
```java
int updated = distributionDao.markRedistributed(distributionId);
if (updated == 0) {
    result.setCode("E107");
```

### CX-24 화면의 재배포 버튼은 "아직 재배포 안 함 또는 연장 상태"일 때만 보인다. 서비스보다 좁다
- 근거: `resources/templates/distributions.html:30-32`
```html
<!-- 재배포 버튼: 아직 재배포 안 했거나, 연장 상태인 과제만 -->
th:if="${d.redistributed == 0 or d.assignmentStatus == 'X'}">
```
- 서비스는 시스템 오류 사유의 두 번째 재배포(CX-20)를 허용하지만, 이미 재배포된 건의 버튼이 숨겨져 화면에서는 낼 수 없다. 버튼은 마감 여부도 보지 않는다(마감 판정은 서비스에서 한다).

## 4. 제출 상태 전환

### CX-25 제출 한 건은 (제출 시각, 점수) 유무로 네 상태 중 하나로 분류된다
- 근거: `Svc:407-422`
```java
if (at == null && sc == null) {            // 미제출
} else if (at == null && sc != null) {     // 채점완료(제출시각없음), 경고
} else if (at != null && sc == null) {     // 채점전
} else {                                   // 채점완료
```
- 제출 시각 없이 점수만 있으면 채점완료로 세고 경고를 남긴다(`Svc:411-415`).

### CX-26 제출 시각이 마감 시각보다 엄격히 늦으면 지연 제출이다. 마감 시각이 없으면 지연은 없다
- 근거: `Svc:428-430`
```java
if (due != null && at.isAfter(due)) {
    late++;
    state = state + "/지연";
```
- 지연은 상태를 바꾸지 않고 표시만 덧붙인다.

### CX-27 재배포는 제출 데이터를 바꾸지 않는다. DB 에 쓰는 것은 재배포 플래그뿐이다
- 근거: `Svc:483-486`, `Svc:620`
```java
// 재배포는 기존 제출을 지우지 않는다. 미제출자만 '재제출 대상' 으로 계산해 화면에 알려 준다.
int updated = distributionDao.markRedistributed(distributionId);
```
- `DistributionDao` 의 `submission` 접근은 `findSubmissions` 조회뿐이다(`DistributionDao:69-73`). 아래 CX-28~CX-31 은 화면에 보여 줄 재제출 대상 수 · 안내 문구의 계산 규칙이다.

### CX-28 기본 재제출 대상은 미제출자이고, 기존 제출(미제출 제외)은 유지 건수로 센다
- 근거: `Svc:485-486`
```java
int kept = total - notSubmitted;
int reopen = notSubmitted;
```

### CX-29 과제가 연장(`X`) 상태이면 채점 전 제출자도 재제출 대상에 넣는다
- 근거: `Svc:487-491`
```java
if (extended) {
    reopen = notSubmitted + submittedNoScore;
```

### CX-30 사유가 재시험 · 재평가(분류 4)이면 채점 완료자까지 전원이 재제출 대상이다. 기존 점수는 유지한다
- 근거: `Svc:492-496`
```java
if (reasonCategory == 4) {
    reopen = total;
```
- CX-29 보다 나중에 실행되어 덮어쓴다. 분류 4 는 CX-21 의 우선순위상 오류 · 장애 · 연장 · 추가 · 정정 · 수정 키워드가 없을 때만 된다.

### CX-31 채점 완료 제출은 분류 4 가 아니면 그대로 유지한다
- 근거: `Svc:497-499`, `Svc:604-612`
```java
if (graded > 0 && reasonCategory != 4) {
    warnings.add("채점 완료 제출 " + graded + "건은 그대로 유지됩니다. 점수 변경이 필요하면 성적 모듈에서 처리하세요.");
```
- 채점 전 제출자는 연장이면 "재제출 가능", 아니면 "유지"로 안내한다(`Svc:593-603`).

### CX-32 목록의 "제출 수"는 제출 시각이 있는 건만 세고, 재배포 계산의 "제출"은 점수만 있는 건도 포함한다
- 근거: `dao/DistributionDao.java:26`, `Svc:636-638`
```java
"(SELECT COUNT(*) FROM submission s WHERE s.distribution_id = d.id AND s.submitted_at IS NOT NULL) AS submitted_cnt "
// 제출 건수 집계는 submitted_at 기준, 여기 계산은 score 포함 → 다를 수 있음
```
- 제출 시각 없이 점수만 있는 이상 데이터가 있으면 두 값이 달라지며, 코드는 그 차이를 노트로만 남긴다.

## 5. 다루지 않은 것

- 점수 등급(A~F) · 통계 문구 · 학생 ID 형식 경고(`Svc:377-398`, `Svc:457-478`, `Svc:516-586`)는 배포 · 마감 · 재배포 판정 · 제출 상태 전환에 영향을 주지 않는 안내용이라 규칙으로 쓰지 않았다.
- 목록 정렬 · 제외 조건(`AssignmentDao`, `DistributionDao`)은 이번 범위 밖이라 뺐다.
- `legacy/` 아래 파일은 수정하지 않았다.
