# RCA: a-connection-pool

## 요약

- **무엇이**: 2026-09-16 **14:40:52 ~ 14:51:47 KST(약 11분)** 동안 api 서버 API-1 이 커넥션 풀을 얻지 못했다. 사용자는 5xx 186건(500 49 · 502 82 · 504 55)을 받았다. 첫 이상 신호는 14:37:52 다.
- **직접 원인**: 학급 리포트 요청(`GET /api/classes/{id}/report`)이 1건당 커넥션을 15 ~ 21초 쥐었다. 5개 세션이 리포트를 반복 요청하면서 풀 5개가 모두 리포트에 점유됐다.
- **배경 원인**: `ReportService.buildClassReport()` 가 DB 조회를 마친 뒤에도 `@Transactional` 안에서 CPU 서명 루프 `sign()`(SHA-256 20만 회)을 돌려, DB 를 쓰지 않는 동안에도 커넥션을 놓지 않는다.
- **확신 수준: 중간**. 로그 · 코드 · 설정이 서로 맞는다. 다만 평소 10초 안에 끝나던 리포트가 왜 19초로 늘었는지(CPU 경합 추정)와 운영 설정 값은 직접 확인하지 못했다.

대상 로그: `incident-logs/a-connection-pool/`

## 1. 수집 범위

> 이 절은 분석 범위만 정한다. 원인은 추측하지 않는다.
> 파일은 통째로 읽지 않고 `wc` · `head` · `tail` · `grep -c` · `grep -n` 과 분 단위 집계(`cut` · `awk` · `uniq -c`)로만 봤다.

### 1.1 파일별 기록 기간 · 형식

| 파일 | 줄 수 | 기록 기간(원본 시각) | 기록 기간(KST) | 시각 형식 | 타임존 |
|---|---:|---|---|---|---|
| `app.log` | 5,371 (타임스탬프가 있는 줄 1,927, 나머지 3,444줄은 스택트레이스) | 2026-09-16T06:43:56.753+09:00 ~ 15:29:51.760+09:00 | 06:43:56 ~ 15:29:51 | ISO-8601, 밀리초, 오프셋 포함 (`2026-09-16T14:37:52.199+09:00`) | KST (+09:00 명시) |
| `nginx-access.log` | 21,086 | [16/Sep/2026:06:43:56 +0900] ~ [16/Sep/2026:15:29:59 +0900] | 06:43:56 ~ 15:29:59 | nginx combined (`[16/Sep/2026:14:41:06 +0900]`), 초 단위 | KST (+0900 명시) |
| `nginx-error.log` | 168 | 2026/09/16 09:12:03 ~ 14:51:47 | 09:12:03 ~ 14:51:47 | `YYYY/MM/DD HH:MM:SS`, 초 단위 | **표기 없음 → KST로 판정** (아래 근거 참고) |
| `mariadb-slow.log` | 21 (쿼리 3건) | `# Time: 260916 0:14:09` ~ `260916 5:37:52` | 09:14:09 ~ 14:37:52 | `# Time: YYMMDD H:MM:SS`(쿼리 종료 시각) + `SET timestamp=<epoch>`(쿼리 시작 시각) | **UTC** (아래 근거 참고) |

**타임존 판정 근거**

- `nginx-error.log`: `nginx-error.log:6` 의 `14:41:06 upstream timed out ... GET /api/classes/3/report` 가 `nginx-access.log:18933` 의 `[16/Sep/2026:14:41:06 +0900] "GET /api/classes/3/report" 504` 와 같은 초 · 같은 요청이다. 다른 예로 14:41:26 · 14:41:51 의 504 도 짝이 맞는다. 그래서 오프셋 없이 KST로 기록된 것으로 본다.
- `mariadb-slow.log`: `SET timestamp=1789537062` 는 UTC 기준 `2026-09-16 05:37:42` 다. 여기에 `Query_time: 9.81` 를 더하면 `# Time: 260916 5:37:52` 와 맞는다. 그래서 `# Time` 은 UTC 의 쿼리 종료 시각이다. 나머지 2건(`0:14:09`, `2:02:42`)도 epoch + Query_time 으로 같은 결과가 나온다.

**KST 변환 규칙**

| 파일 | 변환 |
|---|---|
| `app.log`, `nginx-access.log` | 변환하지 않는다(이미 +09:00) |
| `nginx-error.log` | 변환하지 않는다(KST). 다른 로그와 맞출 때는 날짜 구분자 `/` 만 맞춘다 |
| `mariadb-slow.log` | `# Time` 에 **+9시간** 을 더한다(`260916 5:37:52` → 2026-09-16 14:37:52 KST). 쿼리 시작 시각은 `SET timestamp` epoch 를 `TZ=Asia/Seoul date -d @<epoch>` 로 바꿔 얻는다 |

slow log 3건을 KST로 바꾸면 다음과 같다.

| 시작(KST) | 종료(KST) | Query_time | Lock_time | Rows_examined | 줄 |
|---|---|---:|---:|---:|---|
| 09:14:07 | 09:14:09 | 2.32s | 0.00s | 41,822 | `mariadb-slow.log:1` |
| 11:02:40 | 11:02:42 | 2.04s | 0.91s | 5 | `mariadb-slow.log:8` |
| 14:37:42 | 14:37:52 | 9.81s | 0.00s | 1,284,310 | `mariadb-slow.log:15` |

### 1.2 이상 신호의 분 단위 집계 (KST)

**집계 기준**

- `app.log`:
  - 타임스탬프 줄 중 레벨이 `WARN` · `ERROR` 인 것만 센다. 스택트레이스 줄은 제외한다.
  - 하루 내내 산발적으로 나오는 `state conflict`(409, 20건, 08:41~14:52 사이 분당 0~1건)는 평소 수준으로 보고 따로 뺐다.
- `nginx-access.log`: 상태 코드 5xx(500 · 502 · 504)를 센다. 400 · 404 · 409 (합계 1,295건)는 하루 내내 분포해 평소 수준으로 본다.
- `nginx-error.log`: `[error]` · `[warn]` 을 센다. `[info]` 3건은 제외한다.
- 타임아웃은 다음 두 가지다.
  - `app.log`: `Connection is not available, request timed out after 3000ms`
  - `nginx-error.log`: `upstream timed out (110)`

**평소 수준(06:43 ~ 14:36)**

| 신호 | 평소 수준 |
|---|---|
| app WARN/ERROR(409 제외) | 0건 |
| access 5xx | 0건 |
| nginx-error `[error]` | 0건 |
| nginx-error `[warn]` | 2건뿐이다(09:12:03, 13:05:51, `upstream response is buffered to a temporary file`) |
| 전체 요청량 | 분당 27 ~ 66건, 중앙값 43건(09:00~14:36 기준) |

**14:36 ~ 14:53 분 단위 집계**

| 분(KST) | app 커넥션 누수 경고 | app ERROR | app 풀 타임아웃 | access 500 | access 502 | access 504 | nginx upstream 타임아웃 | nginx upstream 비활성/거부¹ | 전체 요청 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 14:36 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 41 |
| 14:37 | 1 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 50 |
| 14:38 | 2 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 41 |
| 14:39 | 3 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 42 |
| 14:40 | 14 | 2 | 1 | 1 | 0 | 0 | 0 | 0 | 42 |
| 14:41 | 13 | 12 | 6 | 6 | 0 | 3 | 3 | 0 | 42 |
| 14:42 | 10 | 4 | 2 | 2 | 13 | 5 | 5 | 15 | 41 |
| 14:43 | 14 | 20 | 10 | 10 | 2 | 5 | 5 | 3 | 58 |
| 14:44 | 11 | 4 | 2 | 2 | 5 | 6 | 6 | 9 | 34 |
| 14:45 | 9 | 16 | 8 | 8 | 14 | 5 | 5 | 17 | 39 |
| 14:46 | 12 | 8 | 4 | 4 | 16 | 6 | 6 | 19 | 51 |
| 14:47 | 9 | 10 | 5 | 5 | 6 | 3 | 3 | 7 | 38 |
| 14:48 | 10 | 8 | 4 | 4 | 6 | 5 | 5 | 10 | 42 |
| 14:49 | 12 | 14 | 7 | 7 | 8 | 8 | 8 | 12 | 42 |
| 14:50 | 11 | 0 | 0 | 0 | 11 | 7 | 7 | 15 | 34 |
| 14:51 | 3 | 0 | 0 | 0 | 1 | 2 | 2 | 1 | 46 |
| 14:52 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 47 |
| 14:53 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 37 |
| **합계** | **134** | **98** | **49** | **49** | **82** | **55** | **55** | **108** | |

¹ `no live upstreams`(68) + `upstream server temporarily disabled`(26) + `connect() failed (111: Connection refused)`(14)

- app ERROR 98건의 구성은 다음과 같다.
  - `SqlExceptionHelper ... request timed out` 49건
  - `GlobalExceptionHandler unhandled exception` 49건
  - 같은 요청이 두 줄씩 남겨, app 풀 타임아웃 49건과 access 500 49건이 분마다 일치한다.
- 14:52 이후 15:29 까지 모든 신호가 0건이다(10분 단위 집계로 확인).

**이상 구간 제안 (KST)**

| 구분 | 시각 | 근거 |
|---|---|---|
| 시작 | **14:37:52** | 첫 이상 신호다. `app.log:1243` `ProxyLeakTask` 누수 경고 |
| 사용자 영향 시작 | **14:40:52** | 첫 5xx 다. `app.log:1522` ERROR, `nginx-access.log:18918` 500 |
| 사용자 영향 끝 | **14:51:47** | 마지막 5xx 다. `nginx-access.log:19381` 504, `nginx-error.log:168` upstream timed out |
| 끝(모든 신호) | **14:51:47** | 마지막 누수 경고는 `app.log:5251`(14:51:37)다. 14:52 부터 0건이다 |

참고할 사실(해석하지 않음):

- `application.yml` 의 `leak-detection-threshold` 는 `10000`ms 다. 운영 설정과 같은지는 확인하지 않았다. 같다면 첫 누수 경고(14:37:52)의 커넥션은 그보다 약 10초 전인 14:37:42 무렵에 빌린 것이다. slow log 3번째 쿼리의 시작 시각(14:37:42 KST)과 시각이 겹친다.

### 1.3 수집 구간과 여유

| | 시각(KST) | 여유 | 이유 |
|---|---|---|---|
| 수집 시작 | **14:07:00** | 이상 시작(14:37:52) 앞으로 약 **30분** | 누수 경고는 커넥션을 빌린 뒤 약 10초 지나야 찍힌다. 그래서 실제 시작은 로그보다 앞선다. 서서히 쌓이는 변화(요청량 · 느린 쿼리)를 평소 수준과 비교할 기준 구간도 필요하다 |
| 수집 끝 | **15:02:00** | 이상 끝(14:51:47) 뒤로 약 **10분** | nginx upstream 비활성 해제와 정상 응답 회복을 확인하는 데 충분하다. 14:52 이후 신호가 0으로 바로 떨어져 길게 둘 필요가 없다 |

- 파일별로 적용할 범위는 다음과 같다.
  - `app.log` · `nginx-access.log` · `nginx-error.log`: KST 14:07:00 ~ 15:02:00
  - `mariadb-slow.log`: **UTC 05:07:00 ~ 06:02:00** (`# Time: 260916 5:07:00` ~ `260916 6:02:00`). 구간 안에 있는 것은 3번째 쿼리 1건이다
- 비교용 참고: 구간 밖의 slow log 2건(09:14 · 11:02 KST)과 nginx `[warn]` 2건(09:12 · 13:05)은 평소 수준 비교용으로만 남긴다.

## 2. 타임라인

> 대상 구간은 KST 2026-09-16 14:07:00 ~ 15:02:00 이다. 1.3 에서 정한 구간이고, slow log 는 UTC 05:07:00 ~ 06:02:00 에 해당한다.
> 모든 시각은 KST 로 적는다. slow log 는 1.1 의 규칙대로 +9시간 했다.
> 로그에 있는 사건만 적고, 원인 해석은 쓰지 않는다.

**표기 규칙**

- 출처의 파일 이름은 줄여 쓴다: `app` = `app.log`, `acc` = `nginx-access.log`, `err` = `nginx-error.log`, `slow` = `mariadb-slow.log`
- 같은 메시지가 반복되면 첫 발생 줄 하나를 적고, 반복 횟수를 "이후 N건/분" 으로 묶는다.
- 원문 발췌는 80자 이내로 자르고, 생략한 부분은 `…` 로 표시한다. 학생 식별자는 등장 순서대로 `학생A`, `학생B` … 로 바꿨다.
- **(선후 불확실)**: 서로 다른 파일의 사건이 몇 초 안에 몰려 있어 순서를 단정할 수 없는 곳이다. 이유는 셋이다.
  - access · error 로그와 slow log 의 `# Time` 은 초 단위까지만 남는다.
  - nginx access 로그는 응답이 끝난 시각을 남긴다.
  - 호스트마다 시계가 다를 수 있다.

### 2.1 이상 신호 이전 (14:07 ~ 14:37:51)

| 시각 | 출처 | 사건 요약 | 원문 발췌 |
|---|---|---|---|
| 14:07:00 ~ 14:36:59 | acc:17405 ~ (구간 전체) | 전체 요청량은 분당 32 ~ 56건(중앙값 45)이다. 5xx · nginx `[error]` · app ERROR 는 0건이다 | `"GET /api/units/M5-1/items HTTP/1.1" 200 1748` |
| 14:10:05 ~ 14:18:13 | acc (14:10 ~ 14:18), app:1148 ~ 1175 | `/api/classes/{id}/report` 요청이 5건이고 모두 200 이다. 10분 단위 평소 수준(0 ~ 3건)보다 조금 많다. app 에도 리포트 생성이 5건 있다 | `built class report for class 3: 3 distributions, 10 submissions` |
| 14:11:38 | acc:17605 · app:1154 | 재배포 409 (state conflict). 하루 20건이 드문드문 나오는 메시지다. 이후 구간 안에 14:14:03 · 14:33:45 · 14:42:42 · 14:50:22 · 14:51:52 · 14:52:09 에 1건씩 더 있다 | `state conflict: 마감된 과제는 재배포할 수 없습니다: distributionId=3` |
| 14:19:38 | acc:17977 · app:1182 | 배포 7 재배포 성공(200) | `redistributed distribution 7 (assignment 5, class 3) reason=학생A 결석으로 재배포` |
| 14:37:42 | slow:20 (`SET timestamp=1789537062`) | **느린 쿼리 시작**. 이 구간의 유일한 slow log 항목이다. 호스트 API-1, `submission` 테이블, `distribution_id=5` 조건이다 | `select s1_0.id,s1_0.distribution_id,s1_0.score,… where s1_0.distribution_id=5` |

### 2.2 이상 구간 (14:37:52 ~ 14:51:49)

| 시각 | 출처 | 사건 요약 | 원문 발췌 |
|---|---|---|---|
| 14:37:52 | slow:15 · slow:18 | **느린 쿼리 종료**. 걸린 시간 9.81초, 읽은 행 1,284,310 이다. 평소 slow log 항목(09:14 · 11:02)은 2초대였다 | `# Query_time: 9.812417  Lock_time: 0.000071  Rows_sent: 4  Rows_examined: 1284310` |
| 14:37:52.199 **(선후 불확실)** | app:1243 | **첫 커넥션 누수 경고**. 스레드 exec-6, 스택은 `ReportController.classReport` 다. slow:15 와 같은 초에 찍혔다. 이후 경고는 분당 1 · 2 · 3건(14:37 ~ 14:39), 9 ~ 14건/분(14:40 ~ 14:50), 3건(14:51)이다. 모두 134건이고, 스택은 134건 모두 `ReportController.classReport` 다 | `Connection leak detection triggered for org.mariadb.jdbc.Connection@6e8f14d3 …` |
| 14:38:01 **(선후 불확실)** | acc:18801 | 구간 안 첫 리포트 요청. 200 이다 | `"GET /api/classes/3/report HTTP/1.1" 200 266` |
| 14:38:01.900 **(선후 불확실)** | app:1258 | 리포트 생성. 스레드 exec-6 이다 | `built class report for class 3: 3 distributions, 10 submissions` |
| 14:38:01.902 | app:1259 | 경고했던 커넥션이 반환됐다는 기록. 이후 모두 134건이고, 마지막은 14:51:49 다 | `Previously reported leaked connection org.mariadb.jdbc.Connection@6e8f14d3 …` |
| 14:38 ~ 14:51 | acc (해당 분) | **리포트 요청 증가**. 분당 3 · 3건(14:38 · 14:39), 10건(14:40), 12 ~ 19건(14:41 ~ 14:49), 10 · 5건(14:50 · 14:51)이다. 교사단말A · 교사단말B · 교사단말C · 교사단말D 네 곳에서 왔다. 전체 요청량은 분당 34 ~ 58건으로 이전과 비슷하다 | `교사단말B … "GET /api/classes/3/report HTTP/1.1" 200 266` |
| 14:38:29 **(선후 불확실)** | acc:18817 · app:1278 | 배포 5 재배포 성공(200). `reason=null` 은 하루 29건 중 9건이라 드물지 않다 | `redistributed distribution 5 (assignment 3, class 3) reason=null` |
| 14:38:51 ~ 14:40:12 | app:1280 · 1331 · 1351 · 1370 | 누수 경고에 나오는 커넥션 객체가 차례로 늘었다. @2ac19f07(14:38:51), @71d4b8e2(14:39:50), @3f6c2a91(14:40:04), @5b0e9e0c(14:40:12) 순이다. 14:40:12 이후 서로 다른 객체 5개가 경고에 나온다 | `Connection leak detection triggered for org.mariadb.jdbc.Connection@5b0e9e0c …` |
| 14:40:52.319 **(선후 불확실)** | app:1522 · 1521 · 1523 | **첫 커넥션 풀 타임아웃**. 한 요청마다 WARN(SQL Error) 1줄, ERROR 2줄이 같이 찍힌다. 이후 1 ~ 10건/분(14:40 ~ 14:49), 모두 49건이다 | `itembank-pool - Connection is not available, request timed out after 3000ms` |
| 14:40:52 **(선후 불확실)** | acc:18918 | **첫 5xx (500)**. app:1523 과 같은 경로다. 이후 500 은 1 ~ 10건/분, 모두 49건이고 마지막은 14:49:51 이다 | `"GET /api/units/M6-2/items HTTP/1.1" 500 162` |
| 14:41:06 | err:6 · acc:18933 | **첫 upstream 타임아웃 / 504**. upstream 은 API-1 이다. 이후 2 ~ 8건/분, 모두 55건이다. 504 는 55건 모두 `/api/classes/{id}/report` 다 | `upstream timed out (110: Connection timed out) while reading response header` |
| 14:42:01 | err:10 | **API-1 이 upstream 에서 임시 제외됨**. 이후 모두 26건이고, 마지막은 14:50:52(err:158)다 | `[warn] … upstream server temporarily disabled while reading response header` |
| 14:42:01 | err:11 | **API-2 연결 거부**. 이 구간 전에는 API-2 에 대한 오류가 없다. 이후 모두 14건이고, 마지막은 14:50:52(err:159)다 | `connect() failed (111: Connection refused) while connecting to upstream` |
| 14:42:01 | acc:18970 | **첫 502**. 이후 1 ~ 16건/분, 모두 82건이고 마지막은 14:51:02 다 | `"GET /api/classes/2/report HTTP/1.1" 502 157` |
| 14:42:04 | err:12 | **no live upstreams**. 이후 1 ~ 14건/분, 모두 68건이고 마지막은 14:51:02(err:166)다 | `no live upstreams while connecting to upstream` |
| 14:42:59.940 | app:2295 | **Tomcat 스레드 exec-12 가 처음 등장**. 14:42 이전 app.log 에는 exec-1 ~ exec-10 만 있다. 이후 exec-11 ~ 14 가 모두 26줄 나온다 | `[io-8080-exec-12] … no handler for /favicon.ico` |
| 14:44:19 | acc:19078 · app:2929 | 배포 8 재배포 성공(200) | `redistributed distribution 8 (assignment 6, class 2) reason=보강 수업 후 재배포` |
| 14:45:06.991 | app:3249 | 풀 대기 수 최대(waiting=7). 첫 타임아웃(14:40:52)은 waiting=4, 14:45:05 는 waiting=6 이었다 | `request timed out after 3000ms (total=5, active=5, idle=0, waiting=7)` |
| 14:49:51.553 | app:4981 · acc:19305 | **마지막 풀 타임아웃 / 마지막 500** | `unhandled exception on /api/units/M5-3/items` |
| 14:50:52 | err:158 · err:159 | 마지막 temporarily disabled(API-1)와 마지막 Connection refused(API-2) | `connect() failed (111: Connection refused) while connecting to upstream` |
| 14:51:02 | err:166 · acc:19347 | 마지막 no live upstreams 와 마지막 502 | `no live upstreams while connecting to upstream` |
| 14:51:37.674 | app:5251 | **마지막 커넥션 누수 경고** | `Connection leak detection triggered for org.mariadb.jdbc.Connection@6e8f14d3 …` |
| 14:51:47 | err:168 · acc:19381 | **마지막 upstream 타임아웃 / 마지막 504 = 마지막 5xx** | `"GET /api/classes/3/report HTTP/1.1" 504 167` |
| 14:51:49.148 **(선후 불확실)** | app:5267 | 마지막 누수 커넥션 반환 기록. acc:19381(14:51:47)과 2초 차이다 | `Previously reported leaked connection org.mariadb.jdbc.Connection@6e8f14d3 …` |

### 2.3 이상 신호 이후 (14:52 ~ 15:02)

| 시각 | 출처 | 사건 요약 | 원문 발췌 |
|---|---|---|---|
| 14:52:00 ~ 15:01:59 | acc:~19805 | 5xx · nginx `[error]`/`[warn]` · app WARN/ERROR 가 0건이다. 14:52:09 의 409 1건(평소 수준)은 예외다. 전체 요청량은 분당 35 ~ 56건이다 | `"GET /api/items/13 HTTP/1.1" 200 689` |
| 14:59:15 | acc:19701 | 이상 구간 이후 첫 리포트 요청. 200 이다. 이후 15:01 까지 2건 더 있고 모두 200 이다 | `"GET /api/classes/3/report HTTP/1.1" 200 257` |

### 2.4 선후 불확실 지점 정리

| 시각 | 겹치는 사건 | 단정할 수 없는 이유 |
|---|---|---|
| 14:37:52 | slow:15 쿼리 종료 ↔ app:1243 첫 누수 경고 | slow log 는 초 단위이고 DB 호스트의 시계다 |
| 14:38:01 | acc:18801 ↔ app:1258 · 1259 | access 로그는 응답 완료 시각을 초 단위로 남기고, nginx 와 앱의 시계가 다르다 |
| 14:38:29 | acc:18817 ↔ app:1278 | 위와 같다 |
| 14:40:52 | app:1522 ↔ acc:18918 | 위와 같다 |
| 14:51:47 ~ 14:51:49 | acc:19381 · err:168 ↔ app:5267 | 2초 차이이고, 파일마다 시계가 다르다 |

## 3. 가설과 검증

> 이 절은 가설과 그 검증 방법만 적는다. 어느 가설이 맞는지는 아직 판정하지 않는다.
> 근거 줄번호는 이 문서(`docs/rca/a-connection-pool.md`)의 2절 타임라인 줄이다.
> 확인 명령은 저장소 루트에서 실행한다. 운영 DB(`prod-db` 등)에는 직접 붙지 않는다. DB 확인은 로컬 `docker compose --profile modern` 의 `mariadb` 에서 하고, 운영 통계가 필요하면 DBA 에게 요청한다.

### 3.1 증상과 원인을 구분한다

가장 많이 나온 메시지는 다음 셋이다. 셋 다 **증상**이고, 원인 후보로 쓰지 않는다.

- `Connection leak detection triggered`(134건): 커넥션을 10초 넘게 쥐고 있었다는 관측이다. 134건 모두 나중에 `returned to the pool (unleaked)` 가 찍혀서(`docs/rca/a-connection-pool.md:161`), 반환되지 않은 진짜 누수와는 구분해야 한다.
- `Connection is not available, request timed out after 3000ms`(49건) · 500(49건): 풀 5개가 모두 사용 중이었다는 관측이다(`docs/rca/a-connection-pool.md:174`).
- 504(55건) · 502(82건) · `no live upstreams`(68건): nginx 가 upstream 응답을 받지 못했다는 관측이다.

아래 가설은 **왜 커넥션이 오래 잡혔고 왜 동시에 모자랐는가**를 설명하려는 것이다.

### 3.2 가설 표

| # | 계층 | 1. 가설 | 2. 지지 근거 | 3. 반증 조건 (이게 확인되면 틀렸다) | 4. 확인 방법 | 5. 판정 | 6. 판정 이유 (판단 불가면 필요한 로그) |
|---|---|---|---|---|---|---|---|
| H1 | 애플리케이션 코드 | `ReportService.buildClassReport()` 가 `@Transactional` 안에서 DB 조회를 마친 뒤에도 CPU 를 오래 쓰는 `sign()`(SHA-256 200,000회)을 돌려 요청마다 커넥션을 오래 쥐고 있고, 리포트 요청이 몇 건만 겹쳐도 풀 5개가 바닥난다 | `docs/rca/a-connection-pool.md:158` 누수 경고 134건의 스택이 모두 `ReportController.classReport` 다. `:160` · `:161` 리포트 생성 로그(14:38:01.900) 직후 2ms 만에 커넥션이 반환된다. `:167` 504 55건이 모두 `/api/classes/{id}/report` 다. `:174` `total=5, active=5, idle=0` | ① 로컬에서 `sign()` 을 이상 구간과 비슷한 payload(학생 10명 내외)로 돌렸을 때 1회에 1초도 걸리지 않고, 동시 5 ~ 10건으로 돌려도 10초에 못 미친다. ② 누수 경고 134건 각각의 대여 시점(경고 시각 − 10초)부터 반환 시점까지 동안 해당 스레드가 DB 쿼리 대기 중이었다(쿼리 로그 · slow log 와 시간이 겹친다). ③ 같은 코드가 돈 14:10 ~ 14:18 리포트 5건(`:148`)도 동시 실행이 이상 구간과 비슷했다 | `sed -n 38,93p modern/api/src/main/java/com/example/assignment/ReportService.java` 로 트랜잭션 범위와 `sign()` 위치를 본다. `ReportService.sign()` 을 로컬에서 단독 · 동시 실행해 시간을 잰다(`ReportServiceTest` 에 임시 측정 코드를 넣거나 `jshell`). 스레드별 대여 ~ 반환 간격은 `grep -nE 'leak detection triggered\|returned to the pool\|built class report' incident-logs/a-connection-pool/app.log \| cut -c1-160` 로 뽑아 스레드 이름(`exec-N`)끼리 짝지어 본다 | **유지** | 반증 ② · ③ 이 성립하지 않았다. 누수 커넥션 134건의 보유 시간이 15.5 ~ 21.5초(평균 19.0)인데, 리포트 1건의 쿼리 5개는 slow log 기준 각각 2초 미만이라 합쳐도 10초를 못 넘는다. 남는 5.5초 이상은 트랜잭션 안의 쿼리 밖 구간이고, 134건 모두 리포트 생성 로그 직후 0.1초 안에 반환됐다(3.4 H1). 반증 ①(`sign()` 측정)은 로그로 확인할 수 없어 실행하지 않았다 |
| H2 | DB · 쿼리 | `submission` 조회(`distribution_id=5`)가 인덱스 `idx_submission_distribution` 을 타지 못하고(또는 테이블이 비정상적으로 커져서) 130만 행을 읽느라 9.8초 걸렸고, 이 느린 쿼리가 커넥션 보유 시간을 늘렸다 | `docs/rca/a-connection-pool.md:151` 느린 쿼리 시작 14:37:42, `submission` · `distribution_id=5`. `:157` `Query_time 9.81`, `Rows_examined 1,284,310` 인데 `Rows_sent 4` 다. 평소 slow log 는 2초대였다. `:158` 첫 누수 경고(14:37:52.199)가 쿼리 종료와 같은 초다 | ① 같은 쿼리의 `EXPLAIN` 이 `idx_submission_distribution` 을 쓰고 예상 행 수가 수십 이하다(운영 통계 기준). ② `long_query_time` 이 2초 이하로 설정되어 있는데도 이상 구간(14:37:52 ~ 14:51:49)의 slow log 가 1건뿐이다. 그렇다면 나머지 133건의 누수 경고는 느린 쿼리로 설명되지 않는다. ③ 쿼리를 실행한 커넥션(@6e8f14d3)의 반환 시각(14:38:01.902)이 쿼리 종료(14:37:52)보다 10초 가까이 늦어서, 보유 시간의 절반 이상이 쿼리 밖에서 쓰였다 | 로컬: `docker compose --profile modern up -d mariadb` 후 `docker compose exec mariadb mariadb -uapp -p[SECRET] itembank -e "EXPLAIN SELECT s1_0.id, s1_0.distribution_id, s1_0.score, s1_0.student_id, s1_0.submitted_at FROM submission s1_0 WHERE s1_0.distribution_id=5 ORDER BY s1_0.student_id; SHOW INDEX FROM submission;"`. 운영(DBA 요청): 같은 `EXPLAIN`, `SHOW VARIABLES LIKE 'long_query_time'`, `SELECT COUNT(*) FROM submission`, `information_schema.TABLES` 의 `submission` 행 수 · 마지막 `ANALYZE` 시각, 14:30 전후 대량 INSERT · 인덱스 변경 이력. `db/mariadb/init/01-schema.sql:105` 의 인덱스 정의와 비교한다 | **기각** | 반증 ② · ③ 이 성립했다. 이상 구간의 slow log 는 1건뿐인데 누수 경고는 134건이다. 그 1건을 실행한 커넥션 @6e8f14d3 도 쿼리가 끝나고 9.9초 뒤에야 반환됐다(3.4 H2). 단, 이 판정은 `long_query_time` ≤ 2.04초(가장 짧은 기록 항목)라는 추정에 기대고 있다. 130만 행을 읽은 쿼리 자체는 별도의 의심 사항으로 남긴다 |
| H3 | 트래픽 | 14:38 부터 교사 화면 4곳(교사단말A · 교사단말B · 교사단말C · 교사단말D)에서 학급 리포트 요청이 분당 3건에서 12 ~ 19건으로 늘어, 요청당 보유 시간 × 동시 요청 수가 풀 크기 5를 넘었다 | `docs/rca/a-connection-pool.md:162` 리포트 요청이 분당 3 · 3 · 10 · 12 ~ 19건으로 늘었고 네 IP 에서 왔다. 전체 요청량은 그대로다. `:148` 이전 구간 리포트는 10분에 5건이었다. `:174` 풀 대기 수가 4 → 7 로 늘었다 | ① 리포트 요청 증가가 첫 풀 타임아웃(14:40:52) · 첫 5xx **이후**에 시작됐다. 그렇다면 증가는 원인이 아니라 실패 뒤의 재시도(새로고침 · 프런트 자동 재시도)다. ② 같은 날 이상 구간 밖에 분당 리포트 요청이 10건 이상인 구간이 있었는데 장애가 없었다. ③ 늘어난 요청이 같은 IP · 같은 URL 의 일정 간격 반복이라 사용자 수가 늘었다고 볼 수 없다 | `grep '/report' incident-logs/a-connection-pool/nginx-access.log \| awk '{print substr($4,14,8), $1, $7, $9}' \| sed -n '/14:37:/,/14:41:/p'` 로 14:40:52 전후 초 단위 순서와 IP 를 본다. 하루 전체 분당 리포트 요청 수는 `grep '/report' incident-logs/a-connection-pool/nginx-access.log \| awk '{print substr($4,14,5)}' \| uniq -c \| sort -rn \| head`. IP 별 요청 간격은 `awk '$7 ~ /report/ {print $1, substr($4,14,8)}' incident-logs/a-connection-pool/nginx-access.log \| sort` 로 본다. `modern/web` 의 리포트 화면에 재시도 · 폴링 로직이 있는지 `grep -rn 'report' modern/web/src` 로 확인한다 | **유지** | 반증 ① · ② 가 성립하지 않았다. 첫 풀 타임아웃(14:40:52) 전에 이미 리포트 요청 16건이 모두 200 으로 끝났고, 이상 구간 밖의 분당 최대는 2건이다. 반증 ③ 은 일부만 성립했다. 늘어난 요청은 새 사용자가 아니라 5개 세션(4 IP)이 같은 URL 을 15 ~ 40초 간격으로 반복한 것이다. 첫 타임아웃 시점에 리포트가 쥔 커넥션도 5개(=풀 전체)였다(3.4 H3) |
| H4 | 인프라 · 배포 | 두 번째 앱 인스턴스 API-2 가 이상 구간 중에 내려가 있었거나(배포 · 재시작 · 장애) upstream 에서 빠져, 모든 요청이 API-1 한 대(풀 5개)로 몰렸다 | `docs/rca/a-connection-pool.md:169` 14:42:01 에 API-2 연결 거부가 처음 나오고, 그 전에는 API-2 에 대한 오류가 없다. `:168` · `:171` API-1 이 임시 제외된 뒤 `no live upstreams` 가 나온다. `:151` · `:167` 느린 쿼리 · 504 의 호스트가 모두 API-1 이다 | ① nginx upstream 설정에 API-2 가 평소 `backup` 이거나 원래 꺼져 있던 서버라, 이상 구간 이전에도 API-1 한 대가 모든 요청을 받고 있었다. ② API-2 의 배포 · 재시작 · 종료 이력이 14:30 ~ 14:42 사이에 없고, 14:42:01 의 거부는 API-1 이 제외된 뒤 nginx 가 처음 API-2 로 넘긴 시점일 뿐이다. ③ API-1 의 풀 고갈(14:40:52, `:165`)이 API-2 거부(14:42:01)보다 먼저라서, API-2 가 살아 있었어도 API-1 의 고갈 시점은 같았다 | nginx 설정의 `upstream` 블록(`nginx -T` 출력 또는 LB 설정 저장소)에서 API-1 · API-2 의 가중치 · `backup` · `max_fails` · `fail_timeout` 을 본다. `grep -n 'API-2' incident-logs/a-connection-pool/nginx-error.log \| head` 로 첫 등장을 확인한다. API-2 의 `app.log` · 컨테이너 기동 시각(`docker ps` / `journalctl`)과 배포 이력(CI/CD 실행 기록)을 14:00 ~ 14:52 범위로 요청한다(이번 수집 범위 밖이다). access 로그에 `$upstream_addr` 가 없어 어느 인스턴스가 응답했는지는 지금 로그로는 알 수 없다 | **판단 불가(로그 부족)** | API-2 의 거부는 14:42:01 ~ 14:50:52 의 14건뿐이다. 하지만 그 전에 API-2 가 요청을 받고 있었는지, 원래 `backup` 이라 시도되지 않았는지를 지금 로그로는 구분할 수 없다. access 로그에 upstream 필드가 없다(3.4 H4). **판단에 필요한 로그**: `$upstream_addr` · `$upstream_status` · `$upstream_response_time` · `$request_time` 이 든 nginx access 로그, nginx `upstream` 설정(`nginx -T`), API-2 의 `app.log` 와 프로세스 기동 · 종료 시각 · 배포 이력 |

### 3.3 가설 간 관계

- 네 가설은 서로 배타적이지 않다. H1 · H2 는 "요청당 커넥션 보유 시간", H3 은 "동시 요청 수", H4 는 "받아 줄 풀의 총량"을 설명한다. 풀 고갈은 이 셋의 곱으로 생기므로, 검증은 가설마다 **기여가 있는지 없는지**를 따로 판정한다.
- 검증 순서 제안: 로컬에서 바로 확인할 수 있는 H1 ①(`sign()` 측정) · H2 ①(`EXPLAIN`) · H3 ①(access 로그 초 단위 순서)을 먼저 하고, 외부 자료(운영 DB 통계 · nginx 설정 · API-2 이력)가 필요한 H2 ② · H4 는 요청을 보내 둔다.
- 수집하지 않은 자료: API-2 의 `app.log`, nginx 설정, 운영 `application.yml`(1.2 에서 `leak-detection-threshold` 가 운영과 같은지 확인하지 않았다), 운영 DB 의 `long_query_time`.
- 3.4 판정 뒤에 남은 것: 유지된 H1 · H3 은 함께 성립할 수 있다. 세션 5개가 각각 요청 1건씩을 처리 중이었고, 요청마다 커넥션을 약 19초 쥐었으며, 풀은 5개였다. 다만 같은 코드가 평소(이상 구간 이전 리포트 56건)에는 10초 안에 끝났으므로, 보유 시간이 왜 19초까지 늘었는지는 로그만으로 설명되지 않는다. 동시 실행 때 CPU 경합이 있었는지는 반증 ①(`sign()` 측정)과 당시 CPU 지표로 확인해야 한다. 5개 세션이 같은 URL 을 반복한 이유(자동 새로고침 · 재시도 · 사용자 새로고침)도 로그만으로는 알 수 없다.

### 3.4 로그 확인 실행 기록

> 대상은 `incident-logs/a-connection-pool/` 이다. 아래 명령은 그 폴더에서 실행했다. 숫자는 출력을 그대로 옮겼다.
> 로그로 확인할 수 없는 항목(H1 ① `sign()` 측정, H2 ① `EXPLAIN` · 운영 DB 변수, H4 의 nginx 설정 · API-2 이력)은 실행하지 않았다.

**H1 — 누수 커넥션의 보유 시간과 반환 시점**

대여 시각은 누수 경고 시각에서 10초(`leak-detection-threshold`)를 뺀 값으로 잡았다. 운영 값이 같다는 가정이다(1.2 참고). 스레드 이름(`exec-N`)으로 경고 · 리포트 생성 · 반환을 짝지었다.

```bash
cat > hold.awk <<'AWK'   # 스레드별 경고(−10s) ~ 반환 간격, 리포트 생성 → 반환 간격
function sec(ts){ split(substr(ts,12,12),a,":"); return a[1]*3600+a[2]*60+a[3] }
/leak detection triggered/ { match($0,/exec-[0-9]+/); t=substr($0,RSTART,RLENGTH); warn[t]=sec($1); nwarn++ }
/built class report/ { match($0,/exec-[0-9]+/); t=substr($0,RSTART,RLENGTH); built[t]=sec($1) }
/returned to the pool/ { match($0,/exec-[0-9]+/); t=substr($0,RSTART,RLENGTH); r=sec($1);
  hold=r-(warn[t]-10); gap=(t in built)? r-built[t] : -1; n++;
  if(gap>=0 && gap<0.1) g0++; holds[n]=hold; sum+=hold; if(hold>max)max=hold; if(min==""||hold<min)min=hold }
END { printf "warn=%d returned=%d built→return<0.1s=%d hold(s): min=%.1f avg=%.1f max=%.1f\n", nwarn,n,g0,min,sum/n,max }
AWK
awk -f hold.awk app.log
# warn=134 returned=134 built→return<0.1s=134 hold(s): min=15.5 avg=19.0 max=21.5

grep -c 'built class report' app.log                                    # 190 (하루 전체)
awk '/built class report/ && substr($1,12,8)>="14:37:52" && substr($1,12,8)<="14:51:49"' app.log | wc -l   # 134
grep 'built class report' app.log | sed 's/.*: built/built/' | sort | uniq -c
#  72 class 1 / 35 class 2 / 83 class 3 — 모두 "3 distributions, 10 submissions"
```

- 이상 구간의 리포트 134건은 모두 10초를 넘겨 경고가 났다. 이상 구간 이전 56건은 경고가 0건이다. payload 는 어느 쪽이나 "배포 3 · 제출 10" 으로 같다.
- 134건 모두 리포트 생성 로그를 찍고 0.1초 안에 커넥션이 반환됐다. 즉 보유 시간은 전부 `buildClassReport()` 의 로그 직전까지(쿼리 + `sign()`)에서 쓰였다.

**H2 — slow log 와 보유 시간 비교**

```bash
grep -c '^# Query_time' mariadb-slow.log                                       # 3
grep '^# Query_time' mariadb-slow.log | awk '{print $3, "rows_examined="$9}'
# 2.318446 rows_examined=41822 / 2.041903 rows_examined=5 / 9.812417 rows_examined=1284310
grep '^# Time' mariadb-slow.log | awk '$4>="5:37:52" && $4<="5:51:49"' | wc -l  # 1  (이상 구간, UTC)
TZ=Asia/Seoul date -d @1789537062 +%T                                         # 14:37:42 (쿼리 시작)
grep -n '6e8f14d3 on thread http-nio-8080-exec-6' app.log | head -2
# 1243: 14:37:52.199 경고 → 대여 ≈ 14:37:42.2   /   1259: 14:38:01.902 반환
```

- 기록된 가장 짧은 항목이 2.04초라서 `long_query_time` 은 2.04초 이하로 추정한다. 리포트 1건은 쿼리 5개(학급 1 · 배포 1 · 제출 3)라서, slow log 에 남지 않은 쿼리만으로는 합쳐도 10초 미만이다.
- 느린 쿼리를 실행한 커넥션: 대여 14:37:42.2, 쿼리 종료 14:37:52, 반환 14:38:01.9 다. 보유 19.7초 중 9.9초가 쿼리 밖이다. DB 와 앱 호스트의 시계 차이는 초 단위로 남는다.

**H3 — 리포트 요청 증가의 선후와 구성**

```bash
grep '/report' nginx-access.log | awk '{print substr($4,14,8), $1, $7, $9}' | awk '$1>="14:37:00" && $1<="14:40:52"'
# 16건, 전부 200. 14:38:01 · 14:38:27 · 14:38:57 · 14:39:24 (모두 교사단말B class 3) → 14:39:54 부터 교사단말A · 교사단말C · 교사단말D 합류

grep '/report' nginx-access.log | awk '{print substr($4,14,5)}' | uniq -c | sort -rn | head -5
# 19 14:46 / 19 14:43 / 16 14:49 / 16 14:41 / 15 14:45
grep '/report' nginx-access.log | awk '{m=substr($4,14,5); if(m<"14:37"||m>"14:52")print m}' | uniq -c | sort -rn | head -3
# 2 15:20 / 2 14:59 / 2 12:20   (이상 구간 밖 분당 최대 = 2)

grep '/report' nginx-access.log | awk '{s=substr($4,14,8); if(s>="14:37:00"&&s<="14:52:00")print $1, $7}' | sort | uniq -c | sort -rn
# 40 교사단말B class 3 / 35 교사단말C class 3 / 35 교사단말A class 1 / 32 교사단말A class 2 / 29 교사단말D class 1   (합계 171)
grep '/report' nginx-access.log | awk '{s=substr($4,14,8); if(s>="14:37:00"&&s<="14:52:00")print $9}' | sort | uniq -c
# 200=79 / 500=5 / 502=32 / 504=55
grep '/report' nginx-access.log | awk '{s=substr($4,14,8); if(s<"14:37:00")print $1}' | sort | uniq -c
# 이상 구간 이전: 교사단말A=19 / 교사단말B=16 / 교사단말C=17  (교사단말D 는 없음)
# 교사단말B class 3 요청 간격(초): 1~4s×7, 15~25s×21, 26~42s×12

# 동시 보유 커넥션 수: 경고−10s ~ 반환 구간을 겹쳐 셈
# max concurrent=5 at 14:40:25.8 / 첫 풀 타임아웃(14:40:52.319) 시점 보유 중인 리포트 커넥션=5
```

- 증가는 첫 5xx 보다 먼저 시작됐다. 교사단말B 는 응답을 받자마자 같은 URL 을 다시 요청하는 패턴이다(응답 약 20초, 간격 15 ~ 30초).
- 늘어난 171건은 5개 세션(Referer `/teacher/classes/{1,2,3}/report`, 브라우저 Safari 2 · Firefox 3)이 반복 요청한 것이다.
- 200 79건 + 504 55건 = 134건 = 앱의 리포트 생성 수다. nginx 가 504 를 준 요청도 앱에서는 끝까지 실행되며 커넥션을 쥐었다.

**H4 — API-2 의 상태**

```bash
grep -n 'API-2' nginx-error.log | sed -n '1p;$p'   # 11: 14:42:01 / 159: 14:50:52
grep -c 'API-2' nginx-error.log                    # 14  (모두 connect() failed (111: Connection refused))
grep -n 'API-1' nginx-error.log | head -1          # 1: 09:12:03 [warn] (buffered)
head -1 nginx-access.log | awk '{print NF" fields"}'    # 24 fields (combined, upstream 필드 없음)
grep -c 'API-1·API-2 주소' nginx-access.log                # 0
# API-2 거부 14건 중 같은 초에 API-1 temporarily disabled 가 있는 것: 5건, 없는 것: 9건
```

- 14:42:01 이전에는 API-2 에 대한 기록이 하나도 없다. 그런데 "그 전엔 정상적으로 요청을 처리했다" 와 "`backup` 이라 시도된 적이 없다" 가 같은 로그를 만든다. 둘을 가를 필드가 access 로그에 없다.
- `Connection refused` 는 14:42 ~ 14:50 사이에 API-2 의 8080 포트가 열려 있지 않았다는 뜻이다. 언제부터 닫혀 있었는지는 알 수 없다.


### 3.5 판정

> 3.4 에서 유지된 H1 · H3 을 `modern/api` 의 설정 파일과 로그에 찍힌 클래스와 대조했다. 판단 불가였던 H4 는 대조할 코드가 없어 최종 정리에서만 다룬다.
> 설정값은 파일에서 읽은 값만 쓴다. 벤치마크 · 테스트는 실행하지 않았다.
> 대조 범위는 `modern/api` 뿐이다. 프런트엔드(`modern/web` 등)와 운영 서버의 실제 설정은 보지 않았다.

#### 3.5.1 로그 단서 → 코드 · 설정

| 로그 단서 | 출처 | 찾은 위치 |
|---|---|---|
| `itembank-pool`, `total=5`, `request timed out after 3000ms` | `app.log:1522` · `app.log:3249` | `modern/api/src/main/resources/application.yml:10` `pool-name: itembank-pool`, `:14` `maximum-pool-size: 5`, `:15` `connection-timeout: 3000` |
| `ProxyLeakTask` `Connection leak detection triggered` | `app.log:1243` | `application.yml:18` `leak-detection-threshold: 10000` |
| 스택 `ReportController.classReport(ReportController.java:22)` | `app.log:1243` 스택 | `modern/api/src/main/java/com/example/assignment/ReportController.java:22` `return reportService.buildClassReport(id);` |
| 스택 `ReportService$$SpringCGLIB$$0.buildClassReport` → `JpaTransactionManager.doBegin` → `HikariDataSource.getConnection` | `app.log:1245` · `app.log:1250` | `modern/api/src/main/java/com/example/assignment/ReportService.java:41` `@Transactional` |
| `built class report for class N: …` | `app.log:1258` | `ReportService.java:73` · `:74` |
| `unhandled exception on /api/units/…/items` | `app.log:1523` | `modern/api/src/main/java/com/example/common/GlobalExceptionHandler.java:53` ~ `:56` (`Exception` → 500) |
| `redistributed distribution …` | `app.log:1278` | `modern/api/src/main/java/com/example/assignment/DistributionService.java` `redistribute()` (짧은 `@Transactional`, 리포트와 무관) |
| 프로시저 | — | 로그에 프로시저 이름이 없다. 리포트는 MS-SQL 저장 프로시저가 아니라 `ReportService` 가 JPA 로 계산한다 |

#### 3.5.2 유지된 가설 대조

| # | 판정 | 근거 (파일:줄번호 인용) |
|---|---|---|
| H1 | **코드 · 설정이 가설을 지지한다** | ① `ReportService.java:41` `@Transactional` 이 메서드 전체를 감싼다. 로그 스택(`app.log:1250` `JpaTransactionManager.doBegin` → `app.log:1245` `HikariDataSource.getConnection`)대로, 메서드에 들어가는 순간 커넥션을 빌린다. ② DB 작업은 `:43` ~ `:63` 에서 끝난다. 그 뒤 `:69` ~ `:71` 에서 `// TODO: 트랜잭션 밖으로 — … DB 작업은 위에서 끝났는데 커넥션(트랜잭션)을 쥔 채로 돈다.` 주석과 함께 `String signature = sign(payload.toString());` 를 같은 트랜잭션 안에서 호출한다. ③ `sign()` 은 `:23` `SIGNATURE_ROUNDS = 200_000` 회 SHA-256 을 반복하는 CPU 루프다(`:85` ~ `:93`). DB 를 쓰지 않는데 커넥션을 쥐고 있다. ④ 로그 `built class report` 는 `sign()` 다음 줄(`:73`)에서 찍히고, 메서드가 끝나면 커밋하며 커넥션을 반환한다. 3.4 의 "134건 모두 리포트 생성 로그 후 0.1초 안에 반환" 과 맞는다. ⑤ 풀은 `application.yml:14` 기준 5개, 대기는 `:15` 기준 3000ms 이고, `:11` ~ `:12` 주석에 "풀이 작고 대기 시간이 짧아, 트랜잭션을 오래 쥐는 코드가 있으면 금방 고갈된다" 고 적혀 있다. ⑥ `main` 코드의 다른 `@Transactional` 은 `DistributionService.java:30` · `:36` · `:49`, `ItemService.java:14`, `UnitService.java:10`, `ItemSearchService.java:20` 이다. 이 중 쿼리 밖에서 오래 도는 루프가 있는 곳은 `ReportService` 하나다. 이는 누수 경고 134건의 스택이 모두 `ReportController.classReport` 인 것(`docs/rca/a-connection-pool.md:158`)과 맞는다 |
| H3 | **관련 코드를 찾지 못했다** | 요청이 늘어난 원인(5개 세션이 같은 URL 을 15 ~ 40초 간격으로 반복, 3.4 H3)은 클라이언트 쪽 동작이다. `modern/api` 에는 이를 설명할 코드가 없다. api 쪽에서 확인한 사실(증폭 요인일 뿐, 원인 코드는 아니다): ① `ReportController.java:21` ~ `:22` 는 요청마다 `buildClassReport()` 를 새로 실행한다. `main` 코드와 `build.gradle` 에 `@Cacheable` · `EnableCaching` · rate limit 이 없다(`grep -rniE 'ratelimit\|bucket4j\|@Cacheable\|EnableCaching'` 결과 0건). ② `ReportService.java:41` `@Transactional` 에 `timeout` 속성이 없다. `application.yml:28` ~ `:29` 의 `server` 설정은 `port: 8080` 뿐이다. 그래서 nginx 가 504 를 준 뒤에도 요청이 끝까지 돌며 커넥션을 쥔다. 3.4 의 "200 79 + 504 55 = 134 = 생성 수" 와 맞는다 |

#### 3.5.3 최종 정리

**채택: H1**: `ReportService.buildClassReport()` 가 `@Transactional` 안에서 CPU 를 쓰는 `sign()` 을 돌려, 리포트 요청 1건이 커넥션 1개를 15 ~ 21초 쥔다. 동시 리포트 요청이 풀 크기(5)에 이르자 다른 API 까지 커넥션을 얻지 못했다.

**채택하지 않은 가설과 사유**

| # | 3.4 판정 | 채택하지 않은 사유 |
|---|---|---|
| H2 (DB · 쿼리) | 기각 | 이상 구간의 slow log 는 1건뿐인데 누수 경고는 134건이다. 그 1건의 커넥션도 쿼리가 끝나고 9.9초 뒤에 반환됐다. 커넥션 보유 시간의 대부분이 쿼리 밖이다. 130만 행을 읽은 쿼리(`idx_submission_distribution` 이 있는데도, `db/mariadb/init/01-schema.sql:112`)는 이번 장애의 원인이 아닌 별도 의심 사항으로 남긴다 |
| H3 (트래픽) | 유지 → **원인이 아니라 촉발 요인** | 전체 요청량은 이상 구간에도 분당 34 ~ 58건으로 평소와 같았다(`docs/rca/a-connection-pool.md:162`). 늘어난 것은 리포트 세션 5개의 반복 요청뿐이다. 요청이 커넥션을 짧게 쥐었다면 같은 요청 수로는 풀 5개가 고갈되지 않는다. 따라서 근본 원인은 보유 시간(H1)이고, 동시 리포트 요청 5건은 H1 을 드러낸 조건이다. 반복 요청의 원인은 api 밖에 있어 이번 대조로는 확인하지 못했다 |
| H4 (인프라 · 배포) | 판단 불가 | API-2 의 상태를 가를 로그가 없다. 또 API-1 의 첫 풀 타임아웃(14:40:52, `docs/rca/a-connection-pool.md:165`)이 API-2 의 첫 연결 거부(14:42:01, `:169`)보다 먼저다. 풀 크기도 인스턴스마다 5다(`application.yml:14`). 그래서 API-2 가 빠진 것이 고갈을 시작시켰다는 근거는 없다. API-2 가 살아 있었다면 부하를 나눠 피해를 줄였을 가능성은 남는다 |

**확신 수준: 중간**

- 높음이 아닌 이유:
  - 이상 구간 이전 리포트 56건은 같은 코드 · 같은 payload(배포 3 · 제출 10)였는데도 10초 안에 끝났다(누수 경고 0건). 이상 구간에는 15 ~ 21초로 늘었다. `sign()` 이 동시 실행될 때 CPU 경합으로 느려진다고 보면 설명되지만, `sign()` 실행 시간과 당시 CPU 사용률은 측정하지 않았다(지시대로 벤치마크를 실행하지 않음).
  - 대조한 `application.yml` 은 저장소 파일이다. `:11` 주석은 "운영 값과 같게 맞춘다" 고 하지만, 운영 서버의 실제 값을 본 것은 아니다. 다만 로그의 `total=5` · `3000ms` 는 `:14` · `:15` 와 일치한다.
- 낮음이 아닌 이유:
  - 누수 경고 134건의 스택이 모두 `ReportController.java:22` → `ReportService.buildClassReport` 다.
  - 코드상 커넥션을 쥔 채 도는 비-DB 루프가 `ReportService.java:69` ~ `:71` 에 있고, 작성자 TODO 주석으로도 문제가 명시돼 있다.
  - 로그의 반환 시점(생성 로그 후 0.1초 안)과 코드 구조가 맞는다.
  - 동시 보유 최대치 5가 `maximum-pool-size: 5` 와 맞는다.
- 확신을 높일 확인: 로컬에서 `sign()` 단독 · 5건 동시 실행 시간 측정, 당시 API-1 의 CPU 지표, 반복 요청을 만든 클라이언트 코드 확인.

## 4. 원인

### 4.1 직접 원인

> 리포트 트랜잭션 5건이 커넥션 풀 5개를 동시에 15 ~ 21초씩 점유했고, 그동안 다른 요청은 3초 대기 끝에 실패했다.

인과 순서(문장 번호는 5절과 대응한다):

- **C1** 14:37:42 에 교사단말B 의 class 3 리포트 요청이 커넥션을 빌렸다. 그 안의 `submission` 조회가 9.8초 걸렸고, 커넥션은 19.7초 뒤인 14:38:01.9 에 반환됐다.
- **C2** 교사단말B 는 응답을 받자마자 같은 리포트를 다시 요청했다(15 ~ 30초 간격). 14:39:54 부터 교사단말A · 교사단말C · 교사단말D 이 합류해 리포트 세션이 5개가 됐다.
- **C3** 리포트 1건이 커넥션을 15.5 ~ 21.5초(평균 19.0초) 쥐었다. 14:40:25.8 에 리포트가 쥔 커넥션이 5개, 즉 풀 전체가 됐다.
- **C4** 14:40:52 부터 리포트가 아닌 요청(`/api/units/…/items`)이 3000ms 를 기다린 끝에 커넥션을 얻지 못해 500 이 났다. 대기 스레드는 최대 7개까지 늘었다.
- **C5** 14:41:06 부터 리포트 요청이 nginx 응답 대기 시간을 넘겨 504 가 났다. 그래도 앱은 그 요청을 끝까지 처리하며 커넥션을 계속 쥐었다(200 79 + 504 55 = 생성 134).
- **C6** 14:42:01 에 nginx 가 API-1 을 upstream 에서 임시 제외했다. API-2 는 연결을 거부했다. 그 결과 `no live upstreams` 와 502 가 났다.
- **C7** 14:51 을 끝으로 리포트 반복 요청이 멈췄다. 이후 마지막 누수 경고(14:51:37)와 마지막 5xx(14:51:47)가 나온 뒤 정상으로 돌아왔다.

### 4.2 배경 원인 (직접 원인을 가능하게 한 조건)

- **B1 (코드)** `ReportService.buildClassReport()` 는 `@Transactional` 메서드 안에서, DB 작업이 끝난 뒤 `sign()` 을 호출한다. 커넥션은 메서드에 들어갈 때 빌리고 커밋할 때 반환하므로, CPU 루프가 도는 동안에도 커넥션이 묶인다.
- **B2 (설정)** 풀이 인스턴스당 5개, 대기 상한이 3000ms 다. 오래 쥐는 트랜잭션 몇 건만으로 고갈되는 크기다. 운영 값과 맞춘 설정이고, 이번 수정 대상은 아니다.
- **B3 (코드)** 리포트 엔드포인트에 캐시 · 동시 실행 상한 · 트랜잭션 timeout 이 없다. 그래서 같은 결과를 반복 계산하고, nginx 가 끊은 요청도 끝까지 실행한다.
- **B4 (미확인)** API-2 가 장애 동안 연결을 거부해, 부하를 나눠 받을 곳이 없었다. 원래 상태는 확인하지 못했다(9절).

## 5. 근거 로그 줄

| 문장 | 파일:줄번호 | 발췌 |
|---|---|---|
| C1 | `mariadb-slow.log:18` · `:20` | `Query_time: 9.812417 … Rows_examined: 1284310` · `SET timestamp=1789537062`(14:37:42 KST) |
| C1 | `app.log:1243` · `app.log:1259` | `14:37:52.199 … leak detection triggered … @6e8f14d3 on thread …exec-6` · `14:38:01.902 … was returned to the pool` |
| C2 | `nginx-access.log:18801` · `:18815` · `:18839` | `교사단말B … 14:38:01 … /api/classes/3/report … 200` (이어서 14:38:27 · 14:38:57) |
| C2 | `nginx-access.log:18881` | `교사단말A … 14:39:54 … "GET /api/classes/1/report …" 200` |
| C3 | 3.4 H1 집계(`app.log` 누수 경고 134 · 반환 134) | `hold(s): min=15.5 avg=19.0 max=21.5` |
| C3 | `app.log:1468` | `14:40:35.820 … leak detection triggered … @2ac19f07` (대여 ≈ 14:40:25.8, 동시 5개째) |
| C4 | `app.log:1522` · `nginx-access.log:18918` | `itembank-pool - Connection is not available, request timed out after 3000ms (total=5, active=5, idle=0, waiting=4)` · `"GET /api/units/M6-2/items …" 500` |
| C4 | `app.log:3249` | `… (total=5, active=5, idle=0, waiting=7)` |
| C5 | `nginx-error.log:6` · `nginx-access.log:18933` | `upstream timed out (110: Connection timed out) … /api/classes/3/report` · `… 504` |
| C5 | 3.4 H3 집계 | 이상 구간 리포트 상태 `200=79 / 504=55`, 앱 `built class report` 134 |
| C6 | `nginx-error.log:10` · `:11` · `:12` | `upstream server temporarily disabled` · `connect() failed (111: Connection refused) … API-2` · `no live upstreams` |
| C6 | `nginx-access.log:18970` | `"GET /api/classes/2/report …" 502` |
| C7 | `app.log:5251` · `nginx-access.log:19381` | `14:51:37.674 … leak detection triggered` · `14:51:47 … /api/classes/3/report … 504`(마지막 5xx) |
| B1 | `modern/api/src/main/java/com/example/assignment/ReportService.java:41` · `:69` ~ `:71` | `@Transactional` · `// TODO: 트랜잭션 밖으로 — … 커넥션(트랜잭션)을 쥔 채로 돈다.` · `String signature = sign(payload.toString());` |
| B1 | `app.log:1250` · `app.log:1245` | `JpaTransactionManager.doBegin` → `HikariDataSource.getConnection` (트랜잭션 시작 때 대여) |
| B2 | `modern/api/src/main/resources/application.yml:14` · `:15` · `:18` | `maximum-pool-size: 5` · `connection-timeout: 3000` · `leak-detection-threshold: 10000` |
| B3 | `ReportController.java:22` · `ReportService.java:41` | `return reportService.buildClassReport(id);`(캐시 없음) · `@Transactional`(timeout 없음) |

## 6. 수정안 (지금 적용)

> 제안만 한다. 코드는 아직 고치지 않았다. `application.yml` 의 HikariCP 값(`maximum-pool-size: 5`, `connection-timeout: 3000`)은 프로젝트 규칙상 바꾸지 않는다.

1. **`ReportService.java` 에서 서명을 트랜잭션 밖으로 뺀다**
   - `:41` 의 `@Transactional` 을 지운다.
   - DB 조회 부분(`:43` ~ `:63`)과 `classRoom.getName()` 을 `TransactionTemplate`(읽기 전용) 안에서 실행한다. 결과는 새 record `ReportSnapshot`(classId · className · distributionCount · submissionCount · averageScore · payload)으로 받는다.
   - 트랜잭션이 끝난 뒤 `sign(snapshot.payload())` 를 호출하고 `ClassReport` 를 만든다. `:69` ~ `:70` 의 TODO 주석은 지운다.
   - 같은 클래스 안에서 다른 `@Transactional` 메서드를 부르는 방식은 프록시를 거치지 않아 트랜잭션이 걸리지 않는다. 그래서 `TransactionTemplate` 을 쓴다(spring-tx 기본 제공, 의존성 추가 없음).
2. **`ReportServiceTest.java` 에 회귀 테스트를 추가한다**: 서명이 커밋 뒤에 실행되는지 검증한다. `sign()` 을 주입 가능한 `ReportSigner` 로 옮긴다. 그다음 Mockito `InOrder` 로 `PlatformTransactionManager.commit()` → `ReportSigner.sign()` 순서를 확인한다(`@DisplayName("서명은 트랜잭션 커밋 뒤에 계산한다")`).
3. 적용 뒤 `cd modern/api && ./gradlew test` 로 확인한다.

## 7. 재발 방지 (구조적 장치)

| 장치 | 무엇을 막나 | 내용 |
|---|---|---|
| 트랜잭션 안 비-DB 작업 금지 규칙 | B1 유형 전체 | CLAUDE.md 금지 사항의 "외부 HTTP 호출 · 전체 테이블 루프" 에 "CPU 연산 · 서명 · 파일 I/O 등 DB 외 장시간 작업" 을 추가한다. `convention-check` 스킬의 점검 항목에도 넣는다. ArchUnit 같은 자동 검사는 의존성 추가라 별도 승인을 받는다 |
| 무거운 엔드포인트 격벽 | 한 API 가 풀 전체를 점유 | 리포트처럼 오래 걸리는 엔드포인트는 동시 실행 수를 풀 크기보다 작게 묶는다(예: 세마포어 2). 넘치면 429 를 즉시 돌려준다. 다른 API 몫의 커넥션이 항상 남는다 |
| 동일 요청 흡수 | 반복 요청이 부하를 키움 | 같은 학급 리포트는 진행 중인 계산을 공유하거나 짧게 캐시한다(Spring 캐시 추상화 `ConcurrentMapCache` 는 의존성 추가 없음). 배포 · 제출이 바뀌면 무효화한다 |
| 클라이언트 재요청 억제 | 응답 대기 중 재요청 | 리포트 화면에서 요청이 진행 중일 때는 같은 요청을 다시 보내지 않게 한다. 자동 새로고침이 있다면 간격을 응답 시간보다 길게 둔다(반복 요청을 만든 원인을 9절에서 확인한 뒤 적용) |
| 앱 처리 상한 < nginx 대기 상한 | nginx 가 끊은 요청을 앱이 계속 처리 | 리포트 처리 상한(예: 쿼리 timeout · 격벽 대기 상한)을 nginx `proxy_read_timeout` 보다 짧게 맞춰, 504 가 난 요청이 커넥션을 쥐고 남지 않게 한다 |
| 동시성 회귀 테스트 | 수정이 되돌아가는 것 | 테스트 프로필(H2, `maximum-pool-size: 2`)에서 리포트 3건을 동시에 실행하는 동안 `GET /api/units` 가 3초 안에 응답하는지 확인하는 통합 테스트를 둔다 |

## 8. 모니터링 항목

> "이번 경보 시각" 은 이번 타임라인 로그에 임계값을 적용해 계산했다. 사용자 영향 시작(첫 5xx)은 14:40:52 다.
> Hikari 지표(active · pending · usage)는 Actuator/Micrometer 로 노출해야 한다. 의존성을 추가하게 되면 별도 승인을 받는다.

| 지표 | 임계값(제안) | 이번 경보 시각 | 근거 |
|---|---|---|---|
| Hikari 누수 경고(`ProxyLeakTask` WARN) | 5분에 1건 이상 → 경고 | **14:37:52** (영향 3분 전) | `app.log:1243`. 평소 0건 |
| 리포트 요청률 | 분당 5건 초과 → 경고 | **14:40:36** (영향 16초 전) | 평소 분당 최대 2건. 14:40 분의 6번째 요청이 14:40:36 이다(3.4 H3) |
| Hikari active = max 지속 | 10초 이상 → 심각 | **14:40:35 무렵(추정)** | 14:40:25.8 에 5개 점유(`app.log:1468` −10s). 계속 점유됐는지는 로그로 확인할 수 없다 |
| Hikari 연결 대기 타임아웃 / pending | 1건 이상 → 심각 | **14:40:52** | `app.log:1522` |
| 5xx 비율 | 1분 동안 1% 초과 → 심각 | **14:40:52** (14:40 분 1/42 = 2.4%) | 1.2 분 단위 집계 |
| nginx upstream 비활성 · `no live upstreams` | 1건 이상 → 심각 | **14:42:01** | `nginx-error.log:10` · `:12` |
| 엔드포인트별 응답 시간 p95 | 리포트 10초 초과 → 경고 | 14:38:01 (계산 시) | 첫 리포트가 약 19.7초 걸렸다. 지금 access 로그에는 `$request_time` 이 없어 실제로는 계산할 수 없다 |

- 가장 일찍 울리는 신호는 누수 경고다. 이 신호를 보고 대응했다면 사용자 영향 약 3분 전에 알아챘을 것이다. 지금은 이 경고가 로그에만 남고 경보로 연결되지 않는다.

## 9. 확인하지 못한 것

| 판단하지 못한 것 | 이유 | 다음을 위해 남길 로그 · 자료 |
|---|---|---|
| 평소 10초 안에 끝나던 리포트가 왜 15 ~ 21초로 늘었나(동시 `sign()` 의 CPU 경합 추정) | 요청 처리 시간과 CPU 사용률 기록이 없다. 벤치마크는 실행하지 않았다 | 호스트 CPU · JVM 스레드 지표, Tomcat access 로그(`%D` 처리 시간) |
| API-2 의 원래 상태(정상 서비스 중이었나 · `backup` 이었나 · 언제 내려갔나) | access 로그에 upstream 필드가 없다. API-2 로그를 수집하지 않았다 | nginx 로그 형식에 `$upstream_addr` · `$upstream_status` · `$upstream_response_time` · `$request_time` 추가, `nginx -T` 설정, API-2 의 `app.log` · 기동/종료 · 배포 이력 |
| 5개 세션이 같은 리포트를 반복 요청한 이유(자동 새로고침 · 재시도 · 사용자 새로고침) | 서버 로그만으로는 구분할 수 없다. 클라이언트 코드는 대조 범위 밖이었다 | 프런트엔드 요청 로그 · 요청 ID(`X-Request-Id`)를 nginx · 앱 로그에 같이 남기기, 리포트 화면 코드 확인 |
| 운영 설정이 저장소 `application.yml` 과 같은가 | 운영 설정을 보지 못했다. `total=5` · `3000ms` 만 로그와 일치한다 | 기동 시 유효 설정(풀 크기 · timeout · leak threshold) 로그 |
| `submission` 조회가 인덱스가 있는데도 130만 행을 읽은 이유 | 운영 `EXPLAIN` · 통계 · `long_query_time` 을 보지 못했다 | DBA 를 통한 운영 `EXPLAIN` · `SHOW VARIABLES LIKE 'long_query_time'` · 테이블 통계(이번 원인과 별개로 조사) |
| 누수 경고 대여 시각(경고 −10초)의 정확성 | 운영 `leak-detection-threshold` 가 10000ms 라는 가정에 기댄다 | Hikari 커넥션 사용 시간 지표(`hikaricp_connections_usage`) |

---

마스킹 적용: IP 86건 · 이메일 0건 · 학생 식별자 1건 · 토큰/세션/키 0건 · 비밀번호/접속 문자열 1건
