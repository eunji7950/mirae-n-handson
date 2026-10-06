# RCA — b-deploy-5xx: 배포(v1.4.2) 후 재배포 API 5xx 증가

> 작성 범위(이번 회차): **1. 수집 범위 · 2. 타임라인 · 3. 가설과 검증(가설 표)** 까지.
> 로그 반증 · 코드 대조 · 판정(4단계)과 요약 · 원인 · 수정안 등 5단계 이후 절은 **작성하지 않음**(다음 회차).
> 이 문서의 모든 시각은 KST 다. 인용은 `파일:줄번호` 이고, 경로는 `incident-logs/b-deploy-5xx/` 기준이다.

## 1. 수집 범위

### 1-1. 파일별 개요

| 파일 | 줄 수 | 기록 기간(KST) | 시각 형식 | 타임존 판정 |
|---|---:|---|---|---|
| `app.log` | 2,938 (타임스탬프 줄 1,275 · 스택트레이스 등 1,663) | 2026-09-17 09:00:04 ~ 14:39:57 | ISO-8601, ms, `2026-09-17T09:00:04.878+09:00` | 명시(`+09:00`, 1,275줄 전부). 변환 불필요 |
| `nginx-access.log` | 20,365 | 2026-09-17 09:00:00 ~ 14:39:58 | combined, 초 단위, `[17/Sep/2026:09:00:00 +0900]` | 명시(`+0900`, 20,365줄 전부). 변환 불필요. 응답 **완료** 시각 기록, 응답 시간 필드 없음 |
| `deploy-history.md` | 39 | v1.3.9(09-01) ~ v1.4.2(09-17) | `2026-09-17 13:40:05` | 문서에 "시각은 모두 KST" 명시(`deploy-history.md:3`). 파이프라인 시계 기준이라 호스트 시계와의 차이는 미확인 |

- 확인 명령: `wc -l *`, `head -n 3` · `tail -n 2`, `grep -oE '(\+09:00|Z|[+-]..:..)$'` 로 타임존 표기 집계, `awk '{print $5}' nginx-access.log | sort | uniq -c`.
- 대상 호스트: `API-1`(배포 대상), `API-2`(대기, 평소 정지) — `deploy-history.md:3`. `app.log` 의 프로세스 시작 로그는 1회뿐이라 API-1 단일 호스트 로그로 본다.

### 1-2. 이상 신호 집계

상태 코드 전체(`awk '{print $9}' nginx-access.log | sort | uniq -c`):

| 상태 | 200 | 400 | 404 | 409 | 500 | 502 |
|---|---:|---:|---:|---:|---:|---:|
| 건수 | 19,278 | 29 | 969 | 35 | 46 | 8 |

5xx 경로별(`awk '$9>=500{print $6,$7}' | sed 's#/[0-9]+#/{id}#g' | uniq -c`):

| 경로 | 500 | 502 |
|---|---:|---:|
| `POST /api/distributions/{id}/redistribute` | 45 | 0 |
| `GET /api/distributions/{id}`(쿼리 포함) | 1 | 2 |
| 그 외 GET(`/api/units…` 4, `/api/items/{id}` 1, `/` 1) | 0 | 6 |

`app.log` 레벨 · 메시지 유형(`awk '/^2026-/{print $2}'`, 숫자 정규화 후 `uniq -c`):

| 레벨 | 건수 | 메시지 |
|---|---:|---|
| ERROR | 45 | `unhandled exception on /api/distributions/N/redistribute` |
| ERROR | 1 | `unhandled exception on /api/distributions/N` |
| WARN | 35 | `state conflict: 마감된 과제는 재배포할 수 없습니다` (= nginx 409 35건) |
| 예외 본문 | 46 + 46 | `JpaObjectRetrievalFailureException` / `Caused by: FetchNotFoundException: Entity Assignment with identifier value 9 does not exist` — identifier 값은 **`9` 한 가지뿐**(92줄) |

ERROR 46건을 발생 지점(스택의 `com.example` 첫 프레임)과 배포 시점으로 나눔:

| 구간 | 대상 배포 id | 실패 프레임 | 건수 |
|---|---|---|---:|
| 배포 전(09:52 ~ 11:18) | 41 | `DistributionService.loadOrThrow:65` ← `redistribute:51` ← `DistributionController.redistribute:38` | 6 |
| 배포 전(10:31) | 41 | `DistributionService.loadOrThrow:65` ← `getDistribution:32` ← `DistributionController.getDistribution:26` | 1 |
| 배포 후(13:46 ~ 14:34) | 42 · 43 | `DistributionService.listByClass:38` ← `DistributionController.redistribute:40` | 39 |

재배포 요청(`POST …/redistribute`) 상태를 배포(재기동 13:41:48) 전후로 나눔:

| 구간 | 200 | 409 | 500 | 합계 | 분당 |
|---|---:|---:|---:|---:|---:|
| 배포 전 09:00 ~ 13:41:47 (약 281분) | 66 | 31 | 6 | 103 | 약 0.37 |
| 배포 후 13:41:48 ~ 14:39:58 (약 58분) | 6 | 4 | 39 | 49 | 약 0.84 |

배포 후 재배포 결과를 학급(`redistributed distribution N (assignment M, class K)` INFO)별로 나눔:

| 학급 | 배포 id | 배포 전 성공 INFO | 배포 후 성공 INFO | 배포 후 nginx 결과 |
|---|---|---:|---:|---|
| class 1 | 42 · 43 | 18 | 39 | 500 39건, 200 0건 |
| class 2 | 8 · 44 | 25 | 2 | 200 |
| class 3 | 5 · 7 | 23 | 4 | 200 |

- 배포 후 class 1 은 서비스 INFO("redistributed …")가 찍힌 뒤 같은 스레드에서 약 6ms 뒤 ERROR 가 찍힌다(예: `app.log:1274` → `app.log:1275`).
- 500 46건의 클라이언트는 전부 **교사단말A** 1대다(`awk '$9==500{print $1}' | uniq -c`). 교사단말B 는 재배포 27건 모두 200.
- 분 단위 5xx: 배포 전 09:52(3) · 10:31(1) · 11:17(1) · 11:18(2), 재기동 13:41(502 8), 배포 후 13:46 ~ 14:34 사이 19개 분에 1 ~ 4건씩(합 39).

### 1-3. 이상 구간과 여유

- **주 이상 구간**: 13:41:48(재기동 시작) ~ 14:34:45(마지막 500, `nginx-access.log:20084`).
- **앞 여유**: 09:00 ~ 13:41(배포 전). 배포 전에도 배포 id 41 대상 500 이 7건 있어 전 구간을 비교 기준으로 포함.
- **뒤 여유**: 14:34:45 ~ 14:39:58(로그 끝). 이 5분 동안 5xx 0건이나, 재배포 요청 자체가 409 1건뿐이라 "해소"로 볼 근거는 없다. 롤백 · 재기동 기록도 없음(`app.log` 의 기동 로그는 13:41:55 1회).

## 2. 타임라인

해석 없이 사건만 적는다. 발췌는 마스킹 후 80자 이내.

### 2-1. 이상 이전 (09:00 ~ 13:41:47)

| 시각(KST) | 출처 | 발췌 |
|---|---|---|
| 09:00:00 | `nginx-access.log:1` | `<IP> - - [17/Sep/2026:09:00:00 +0900] "GET /api/units/M5-3/items …" 200` |
| 09:00:04 | `app.log:1` | `INFO … GlobalExceptionHandler : not found: 문항이 없습니다: id=47` |
| 09:06:17 | `app.log:24` · `nginx-access.log:377` | `WARN … state conflict: 마감된 과제는 재배포할 수 없습니다: distributionId=3` / `POST …/3/redistribute … 409`. 이후 14:35 까지 35건(분당 0 ~ 2건) |
| 09:15:53 | `app.log:59` · `nginx-access.log:931` | `redistributed distribution 43 (assignment 6, class 1)` / `교사단말A … POST …/43/redistribute … 200 171` |
| 09:52:17 | `app.log:194` · `nginx-access.log:3150` | `ERROR … unhandled exception on /api/distributions/41/redistribute` / `… 500 176`. 09:52 에 3건 |
| 09:52:17 | `app.log:195` | `JpaObjectRetrievalFailureException: Entity …Assignment … value 9 does not exist` |
| 10:31:05 | `app.log:451` · `nginx-access.log:5563` | `ERROR … unhandled exception on /api/distributions/41` / `GET /api/distributions/41 … 500` |
| 11:17:40 ~ 11:18:02 | `app.log:655` ~ `app.log:733` · `nginx-access.log:8442` ~ `8468` | `ERROR … unhandled exception on /api/distributions/41/redistribute` 3건 |
| 13:38 | `deploy-history.md:21` | `승인: <EMAIL> (13:38)` |
| 13:40:05 | `deploy-history.md:7` | `v1.4.2 \| 2026-09-17 13:40:05 \| 2026-09-17 13:42:31 \| <EMAIL> \| 재배포 응답에…` **(선후 불확실: 파이프라인 시계)** |
| 13:41:20 | `app.log:1238` · `nginx-access.log:16829` | `redistributed distribution 43 (assignment 6, class 1) reason=학생A …` / `… 200 183` (배포 전 class 1 마지막 성공) |

### 2-2. 이상 구간 (13:41:48 ~ 14:34:45)

| 시각(KST) | 출처 | 발췌 |
|---|---|---|
| 13:41:48.214 | `app.log:1240` | `INFO [tomcat-shutdown] … GracefulShutdown : Commencing graceful shutdown.` |
| 13:41:50 | `nginx-access.log:16862` ~ `16864` | `호스트A … "GET / …" 200 40 "-" "kube-probe/1.29"` 등 200 (재기동 전 마지막 정상 응답) |
| 13:41:50.144 | `app.log:1241` | `INFO … GracefulShutdown : Graceful shutdown complete` **(선후 불확실: nginx 초 단위 · 완료 시각 기록)** |
| 13:41:51 | `nginx-access.log:16865` | `<IP> … "GET /api/units/M5-2/items?student=학생B …" 502 157`. 13:41:59 까지 502 8건(`16865` ~ `16872`) |
| 13:41:55.902 | `app.log:1245` | `INFO [main] … Starting ItemBankApplication v1.4.2 using Java 21.0.4` |
| 13:41:55.906 | `app.log:1246` | `INFO [main] … No active profile set, falling back to 1 default profile: "default"` |
| 13:42:00.304 | `app.log:1264` | `INFO [main] … Started ItemBankApplication in 4.921 seconds` |
| 13:42:01 | `nginx-access.log:16873` | `<IP> … "GET /api/units HTTP/1.1" 200 604` (재기동 후 첫 정상 응답) |
| 13:42:10 | `app.log:1266` · `nginx-access.log:16884` | `redistributed distribution 7 (assignment 5, class 3)` / `교사단말B … POST …/7/redistribute … 200 802` |
| 13:42:31 | `deploy-history.md:7` | `배포 완료 2026-09-17 13:42:31` **(선후 불확실: 13:42:10 요청이 이미 v1.4.2 에서 처리됨)** |
| 13:46:38.107 | `app.log:1274` | `INFO … redistributed distribution 42 (assignment 4, class 1) reason=보강 수업` |
| 13:46:38.113 | `app.log:1275` · `nginx-access.log:17155` | `ERROR … unhandled exception on /api/distributions/42/redistribute` / `교사단말A … 500 176` |
| 13:46:38 | `app.log:1276` | `JpaObjectRetrievalFailureException: Entity …Assignment … value 9 does not exist` |
| 13:46:38 | `app.log:1286` · `app.log:1294` | `at …DistributionService.listByClass(DistributionService.java:38)` ← `DistributionController.java:40` |
| 13:46:58 ~ 13:47:08 | `app.log:1313` · `app.log:1351` · `nginx-access.log:17170` · `17181` | 같은 메시지 반복(배포 id 42). 이후 14:34 까지 39건, 분당 1 ~ 4건 |
| 13:50:00 | `app.log:1393` · `nginx-access.log:17353` | `redistributed distribution 8 (assignment 6, class 2)` / `교사단말A … POST …/8/redistribute … 200 908` |
| 13:50:12 | `nginx-access.log:17365` | `교사단말A … "POST /api/distributions/43/redistribute HTTP/1.1" 500 176` (배포 id 43 첫 500) |
| 13:56:43 | `app.log:1754` · `nginx-access.log:17734` | `redistributed distribution 44 (assignment 5, class 2)` / `… 200 640` |
| 13:59:30 | `nginx-access.log:17867` | `교사단말A … "POST /api/distributions/3/redistribute HTTP/1.1" 409 168` |
| 14:34:45 | `nginx-access.log:20084` | `교사단말A … "POST /api/distributions/43/redistribute HTTP/1.1" 500 176` (마지막 500) |

### 2-3. 이후 (14:34:46 ~ 14:39:58)

| 시각(KST) | 출처 | 발췌 |
|---|---|---|
| 14:35:26 | `app.log:2925` · `nginx-access.log:20119` | `WARN … state conflict: 마감된 과제는 재배포할 수 없습니다` / `POST …/2/redistribute … 409` |
| 14:39:57 | `app.log:2938` | `INFO … GlobalExceptionHandler : no handler for /favicon.ico` (로그 끝) |
| 14:39:58 | `nginx-access.log:20365` | `<IP> … "GET /api/units HTTP/1.1" 200 613` (로그 끝) |

## 3. 가설과 검증

### 3-1. 증상과 원인 구분

- **증상 S1**: 배포 후 `POST /api/distributions/{42,43}/redistribute` 가 500(39건, 13:46 ~ 14:34). 500 의 직접 예외는 `FetchNotFoundException: Assignment 9 does not exist` 다. 예외 자체는 증상이고, "왜 배포 후 42 · 43 요청에서 과제 9를 읽게 됐는가"가 원인 질문이다.
- **증상 S2**: 재기동 중 502 8건(13:41:51 ~ 13:41:59). S1 과 시간 · 경로 · 상태 코드가 다르므로 별도로 다룬다.
- **배경 신호**: 배포 **전**에도 배포 id 41 대상 요청 7건이 같은 예외(identifier 9)로 500(09:52 ~ 11:18). 첫 발생 시각이 배포보다 약 3시간 49분 앞선다.

### 3-2. 가설 표

| # | 축 | 가설 | 근거 줄 | 반증 조건(이게 보이면 틀림) | 확인 방법(실행할 명령) |
|---|---|---|---|---|---|
| H1 | 코드(v1.4.2 변경) | v1.4.2 에서 `redistribute` 가 재배포 후 `listByClass(classId)` 로 **학급 전체 배포 이력**을 읽게 됐고, 그 이력에 존재하지 않는 과제를 참조하는 행이 섞인 class 1 에서만 재배포가 500 이 된다. 재배포 자체(INFO)는 성공한 뒤 이력 조회에서 실패한다 | `deploy-history.md:15` · `16`, `app.log:1274` → `1275`(6ms 차), `app.log:1286` · `1294`(`listByClass:38` ← `Controller:40`), 배포 전 class 1 성공 `nginx-access.log:931` · `16829` | ① 배포 후 class 1(42 · 43) 재배포 200 이 1건이라도 있음 ② 배포 후 500 스택에 `listByClass` 가 없는 건이 있음 ③ class 2 · 3 재배포도 같은 예외로 500 ④ 500 직전 같은 스레드의 `redistributed …` INFO 가 없는 건이 있음 | `awk '$7~/(42\|43)\/redistribute/ && substr($4,14,8)>="13:41:48" && $9!=500' nginx-access.log` · `grep -c 'listByClass(DistributionService.java:38)' app.log` 와 배포 후 ERROR 수 비교 · `grep -B1 -nE 'ERROR.*redistribute' app.log \| grep -c redistributed` · 4단계에서 `modern/api/.../DistributionController.java` · `DistributionService.java` 를 줄 단위 대조 |
| H2 | DB · 데이터 정합성 | `distribution` 41(class 1)이 존재하지 않는 `assignment` 9 를 참조하는 고아 행이며, 배포 이전부터 있던 데이터 문제다. FK 부재 또는 과제 삭제 시 배포가 남은 것 | `app.log:194` · `195`(09:52, 배포 전), `app.log:451`(단건 조회도 실패), identifier 값이 전부 `9`(92줄), 배포 전 41 요청 7건 모두 500(`nginx-access.log:3150` ~ `8468`) | ① 배포 전 41 요청 중 200 이 있음 ② identifier 가 9 가 아닌 예외가 있음 ③ DB 에 `assignment.id=9` 가 존재 ④ 42 · 43 의 이력에 41 이 포함되지 않음(class 1 이 아님) | `awk '$7~/distributions\/41/{print $9}' nginx-access.log \| sort \| uniq -c` · `grep -oE 'identifier value `[0-9]+' app.log \| sort \| uniq -c` · 로컬/스테이징 DB(운영 DB 직접 접속 금지, 필요 시 권한자에게 요청)에서 `SELECT d.id, d.class_id, d.assignment_id FROM distribution d LEFT JOIN assignment a ON a.id = d.assignment_id WHERE a.id IS NULL;` |
| H3 | 인프라 · 배포 설정 | 새 인스턴스가 활성 프로필 없이 `default` 로 기동해(`app.log:1246`) 배포 전과 다른 DB · 설정을 바라보고, 그 DB 에 과제 9 가 없어서 실패한다 | `app.log:1245` · `1246`, 500 증가가 재기동 직후 시작(`app.log:1275`) | ① 배포 **전**에도 같은 예외(identifier 9)가 있음 ② 배포 후 class 2 · 3 재배포가 정상(200) ③ 배포 전 기동 로그도 `default` 프로필 ④ 배포 후 DB 접속 대상(HikariCP `Added connection` 대상)이 배포 전과 같음 | `grep -nE 'identifier value `9' app.log \| awk -F: '$1<1240' \| wc -l` · `grep -nE 'profile\|HikariPool.*Added connection' app.log` · 배포 전 인스턴스 기동 로그(이번 묶음에 없음)와 운영 배포 매니페스트의 `SPRING_PROFILES_ACTIVE` 확인 |
| H4 | 트래픽 · 사용자 행동 | 배포 후 특정 교사(교사단말A)가 실패한 재배포를 반복 클릭해 500 건수가 부풀었고, 동시 요청 · 부하가 실패를 유발했다 | 500 46건이 전부 교사단말A, 배포 후 재배포 분당 0.84건(배포 전 0.37건), 같은 배포 id 에 10 ~ 20초 간격 3연속 요청(`nginx-access.log:17155` · `17170` · `17181`) | ① 배포 후 class 1 첫 요청(13:46:38)부터 실패 — 반복 이전 단건도 실패 ② 500 발생 분과 전체 요청량(분당 total) 상관 없음 ③ 교사단말A 의 class 2 · 3 재배포는 같은 시간대에 성공(`nginx-access.log:17353`) | `awk '{t=substr($4,14,5); n[t]++; if($9==500)e[t]++} END{for(k in n) print k,n[k],e[k]+0}' nginx-access.log \| sort` 로 분당 요청량 대 500 비교 · `awk '$1=="<교사단말A>" && $7~/redistribute/{print substr($4,14,8),$7,$9}' nginx-access.log` |
| H5 | 인프라 · 배포 방식 (S2 전용) | 단일 호스트 재기동(무중단 아님)으로 13:41:51 ~ 13:41:59 사이 업스트림이 없어 502 가 났다 | `deploy-history.md:18`, `app.log:1241`(종료 완료 13:41:50) · `app.log:1263`(Tomcat 기동 13:42:00), `nginx-access.log:16865` ~ `16872` | ① 502 가 종료 완료 ~ Tomcat 기동 사이 밖에서도 발생 ② 이 구간에 200 응답이 섞임 | `awk '$9==502{print NR, substr($4,14,8)}' nginx-access.log` · `awk 'substr($4,14,8)>="13:41:50" && substr($4,14,8)<="13:42:01"{print NR, substr($4,14,8), $9}' nginx-access.log` |

> 반증 시도(로그 · 코드 대조)와 채택 · 기각 판정은 4단계 범위라 이번 회차에서 하지 않았다.

마스킹 적용: IP 21건 · 이메일 2건 · 학생 식별자 2건 · 토큰/세션/키 0건 · 비밀번호/접속 문자열 0건
