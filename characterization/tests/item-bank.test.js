// 동작 보존 테스트 — 문항 은행(item-bank)의 문항 검색 화면 search.php
//
// 규칙
// - 대상 주소는 lib/target.mjs 가 정한다(환경 변수 TARGET_BASE_URL, 없으면 레거시 기본 주소).
// - 기대값을 손으로 적지 않는다. 지금 시스템의 실제 응답이 기대값이다(npm run baseline -- item-bank).
// - 케이스 이름 앞의 BR-xx 는 docs/item-bank/BUSINESS-RULES.md 의 규칙 ID 다.
// - 이 파일은 레거시와 새 API 양쪽에서 같은 결과가 나와야 하므로 건너뛰기를 두지 않는다.
import { describe, expect, it } from 'vitest';
import { fetchNormalized } from '../lib/target.mjs';

const MODULE = 'item-bank';
const PATH = '/search.php';

const LONG_KEYWORD_100 = '가'.repeat(100);
const LONG_KEYWORD_101 = '가'.repeat(101);

describe('item-bank · 문항 검색(search.php)', () => {
  // ---- 정상 입력 ----
  it('정상 · 파라미터 없는 기본 화면 — 공개 + 난이도 5 제외 + 기본 정렬 (BR-01 02 09 11 13)', async () => {
    const result = await fetchNormalized(MODULE, PATH);
    expect(result).toMatchSnapshot();
  });

  it('정상 · 단원 + 난이도 + 키워드 복합 조건 (BR-03 04 05 06 13)', async () => {
    const result = await fetchNormalized(MODULE, PATH, { unit: 'M5-1', level: '3', q: '분수' });
    expect(result).toMatchSnapshot();
  });

  it('정상 · 태그 + 제목 오름차순 정렬 (BR-07 08 09 10)', async () => {
    const result = await fetchNormalized(MODULE, PATH, { tag: '계산', sort: 'title', dir: 'asc' });
    expect(result).toMatchSnapshot();
  });

  // ---- 경계값 ----
  it('경계 · 난이도 5 를 명시하면 기본 화면에서 빠지던 문항이 나온다 (BR-02 03)', async () => {
    const result = await fetchNormalized(MODULE, PATH, { level: '5' });
    expect(result).toMatchSnapshot();
  });

  it('경계 · 난이도 6 — 1~5 밖의 첫 값 (BR-03)', async () => {
    const result = await fetchNormalized(MODULE, PATH, { level: '6' });
    expect(result).toMatchSnapshot();
  });

  it('경계 · 난이도 3abc — 앞자리 숫자만 읽는 형변환 (BR-03)', async () => {
    const result = await fetchNormalized(MODULE, PATH, { level: '3abc' });
    expect(result).toMatchSnapshot();
  });

  it('경계 · 키워드 100자 — 절단 없음 (BR-04)', async () => {
    const result = await fetchNormalized(MODULE, PATH, { q: LONG_KEYWORD_100 });
    expect(result).toMatchSnapshot();
  });

  it('경계 · 키워드 101자 — 100자로 절단 (BR-04)', async () => {
    const result = await fetchNormalized(MODULE, PATH, { q: LONG_KEYWORD_101 });
    expect(result).toMatchSnapshot();
  });

  it('경계 · 페이지 1000 — 999 로 줄임 (BR-11 12)', async () => {
    const result = await fetchNormalized(MODULE, PATH, { page: '1000' });
    expect(result).toMatchSnapshot();
  });

  it('경계 · 마지막 페이지 너머(page=2) — 건수는 있는데 행이 없다 (BR-11 12)', async () => {
    const result = await fetchNormalized(MODULE, PATH, { page: '2' });
    expect(result).toMatchSnapshot();
  });

  // ---- 빈 값 · 누락 ----
  it('빈 값 · 모든 파라미터가 빈 문자열 (BR-02 09 11 13)', async () => {
    const result = await fetchNormalized(MODULE, PATH, {
      q: '',
      unit: '',
      level: '',
      tag: '',
      sort: '',
      dir: '',
      page: '',
    });
    expect(result).toMatchSnapshot();
  });

  it('빈 값 · 공백만 있는 값은 trim 뒤 조건이 사라진다 (BR-02 04 05)', async () => {
    const result = await fetchNormalized(MODULE, PATH, { q: '  ', level: ' ', unit: ' ' });
    expect(result).toMatchSnapshot();
  });

  // ---- 이상한 값 ----
  it('이상한 값 · 음수 난이도와 음수 페이지 (BR-03 11)', async () => {
    const result = await fetchNormalized(MODULE, PATH, { level: '-1', page: '-1' });
    expect(result).toMatchSnapshot();
  });

  it('이상한 값 · 없는 단원 · 태그 · 정렬 · 방향 코드 (BR-05 06 07 08 09)', async () => {
    const result = await fetchNormalized(MODULE, PATH, {
      unit: 'zz9-99',
      tag: '없는태그',
      sort: 'foo',
      dir: 'up',
    });
    expect(result).toMatchSnapshot();
  });
});
