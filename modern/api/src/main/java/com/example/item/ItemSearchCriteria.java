package com.example.item;

import java.util.List;
import java.util.regex.Pattern;
import org.springframework.data.domain.Sort;
import org.springframework.util.MultiValueMap;

/**
 * 문항 검색 조건. 레거시 {@code search.php} 가 GET 파라미터를 해석하는 규칙(BR-02 ~ BR-09, BR-11)을 그대로 따른다.
 * 값이 이상해도 예외를 던지지 않고 레거시처럼 보정하거나 그대로 조회한다.
 *
 * @param keyword 제목 · 지문 부분 일치 키워드. 없으면 빈 문자열
 * @param unit    단원 코드. 없으면 빈 문자열
 * @param level   난이도. {@code null} 이면 지정하지 않은 것(난이도 5 제외)
 * @param tag     태그 이름. 없으면 빈 문자열
 * @param sort    정렬
 * @param page    페이지 번호(1 ~ {@value #MAX_PAGE})
 */
record ItemSearchCriteria(String keyword, String unit, Long level, String tag, Sort sort, int page) {

    static final int PAGE_SIZE = 20;
    static final int MAX_PAGE = 999;
    static final int MAX_KEYWORD_LENGTH = 100;
    static final int MAX_TAG_LENGTH = 50;
    /** 난이도를 지정하지 않았을 때 이 값 미만만 조회한다. */
    static final int LEVEL_EXCLUSIVE_UPPER_BOUND = 5;

    private static final Pattern SINGLE_LEVEL = Pattern.compile("[1-5]");
    /** PHP 의 {@code /^[0-9]+$/} 는 끝의 개행 하나를 허용한다. */
    private static final Pattern PAGE_NUMBER = Pattern.compile("([0-9]+)\\n?");

    private static final String ASC = "asc";
    private static final String DESC = "desc";
    private static final String SORT_ID = "id";
    private static final String SORT_TITLE = "title";
    private static final String SORT_UNIT = "unit";
    private static final String SORT_LEVEL = "level";
    private static final String SORT_CREATED = "created";

    private static final String PROP_ID = "id";
    private static final String PROP_TITLE = "title";
    private static final String PROP_UNIT_CODE = "unit.code";
    private static final String PROP_LEVEL = "level";
    private static final String PROP_CREATED_AT = "createdAt";

    /** 요청 파라미터에서 조건을 만든다. 같은 이름이 여러 번 오면 PHP 처럼 마지막 값을 쓴다. */
    static ItemSearchCriteria fromParams(MultiValueMap<String, String> params) {
        return of(
            lastValue(params, "q"),
            lastValue(params, "unit"),
            lastValue(params, "level"),
            lastValue(params, "tag"),
            lastValue(params, "sort"),
            lastValue(params, "dir"),
            params.containsKey("page") ? lastValue(params, "page") : "1");
    }

    static ItemSearchCriteria of(
        String q, String unit, String level, String tag, String sort, String dir, String page) {
        return new ItemSearchCriteria(
            parseKeyword(q),
            LegacyInput.trim(unit),
            parseLevel(level),
            parseTag(tag),
            parseSort(sort, dir),
            parsePage(page));
    }

    boolean hasKeyword() {
        return !keyword.isEmpty();
    }

    boolean hasUnit() {
        return !unit.isEmpty();
    }

    boolean hasTag() {
        return !tag.isEmpty();
    }

    private static String lastValue(MultiValueMap<String, String> params, String name) {
        List<String> values = params.get(name);
        if (values == null || values.isEmpty()) {
            return "";
        }
        String last = values.get(values.size() - 1);
        return last == null ? "" : last;
    }

    private static String parseKeyword(String q) {
        return LegacyInput.truncate(LegacyInput.trim(q), MAX_KEYWORD_LENGTH);
    }

    private static String parseTag(String tag) {
        return LegacyInput.truncate(LegacyInput.trim(tag), MAX_TAG_LENGTH);
    }

    private static Long parseLevel(String level) {
        String trimmed = LegacyInput.trim(level);
        if (trimmed.isEmpty()) {
            return null;
        }
        if (SINGLE_LEVEL.matcher(trimmed).matches()) {
            return Long.parseLong(trimmed);
        }
        return LegacyInput.toLong(trimmed);
    }

    private static int parsePage(String pageRaw) {
        var matcher = PAGE_NUMBER.matcher(pageRaw);
        if (!matcher.matches()) {
            return 1;
        }
        long page = LegacyInput.digitsToLong(matcher.group(1));
        return (int) Math.max(1L, Math.min(page, MAX_PAGE));
    }

    private static Sort parseSort(String sortRaw, String dirRaw) {
        String sort = LegacyInput.asciiLowerCase(LegacyInput.trim(sortRaw));
        String dir = LegacyInput.asciiLowerCase(LegacyInput.trim(dirRaw));
        boolean asc = ASC.equals(dir);
        boolean desc = DESC.equals(dir);

        return switch (sort) {
            case SORT_ID -> Sort.by(desc ? Sort.Order.desc(PROP_ID) : Sort.Order.asc(PROP_ID));
            case SORT_TITLE -> Sort.by(
                desc ? Sort.Order.desc(PROP_TITLE) : Sort.Order.asc(PROP_TITLE), Sort.Order.asc(PROP_ID));
            case SORT_UNIT -> Sort.by(
                desc ? Sort.Order.desc(PROP_UNIT_CODE) : Sort.Order.asc(PROP_UNIT_CODE),
                Sort.Order.desc(PROP_LEVEL),
                Sort.Order.asc(PROP_ID));
            // 난이도 · 등록일은 방향을 비우면 내림차순이다.
            case SORT_LEVEL -> Sort.by(
                asc ? Sort.Order.asc(PROP_LEVEL) : Sort.Order.desc(PROP_LEVEL), Sort.Order.asc(PROP_ID));
            case SORT_CREATED -> Sort.by(
                asc ? Sort.Order.asc(PROP_CREATED_AT) : Sort.Order.desc(PROP_CREATED_AT), Sort.Order.asc(PROP_ID));
            default -> Sort.by(Sort.Order.desc(PROP_LEVEL), Sort.Order.asc(PROP_ID));
        };
    }
}
