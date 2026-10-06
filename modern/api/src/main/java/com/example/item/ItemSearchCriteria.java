package com.example.item;

import java.util.List;
import java.util.Map;
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

    /** PHP 에서 배열을 {@code (string)} 으로 바꾼 결과. */
    private static final String PHP_ARRAY_TO_STRING = "Array";

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

    /**
     * 요청 파라미터에서 조건을 만든다. PHP {@code $_GET} 처럼 같은 이름이 여러 번 오면 마지막 값을 쓰고,
     * {@code name[]} · {@code name[key]} 배열로 오면 첫 원소를 쓴다(search.php:51-80 의 {@code reset()}).
     */
    static ItemSearchCriteria fromParams(MultiValueMap<String, String> params) {
        String page = phpValue(params, "page");
        return of(
            orEmpty(phpValue(params, "q")),
            orEmpty(phpValue(params, "unit")),
            orEmpty(phpValue(params, "level")),
            orEmpty(phpValue(params, "tag")),
            orEmpty(phpValue(params, "sort")),
            orEmpty(phpValue(params, "dir")),
            page == null ? "1" : page);
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

    /**
     * PHP 가 {@code $_GET[name]} 으로 받을 값을 문자열로 돌려준다. 해당 키가 없으면 {@code null} 이다.
     * 스칼라 키와 배열 키가 함께 오면 나중에 나온 쪽이 이긴다. 키는 처음 나온 순서로 들어 있다.
     * 배열의 첫 원소는 처음 나온 배열 키다. {@code name[]} 이면 그 첫 값, {@code name[key]} 이면
     * 덮어쓴 마지막 값을 쓴다. 첫 원소가 다시 배열({@code name[a][b]})이면 PHP 처럼 {@code "Array"} 다.
     * 서로 다른 키를 번갈아 보낸 경우({@code q[]=a&q=b&q[]=c})는 순서 정보가 없어 재현하지 못한다.
     */
    private static String phpValue(MultiValueMap<String, String> params, String name) {
        int index = 0;
        int scalarIndex = -1;
        int arrayIndex = -1;
        String scalar = null;
        String arrayFirst = null;
        String arrayPrefix = name + "[";
        for (Map.Entry<String, List<String>> entry : params.entrySet()) {
            String key = entry.getKey();
            List<String> values = entry.getValue();
            if (values != null && !values.isEmpty()) {
                if (key.equals(name)) {
                    scalarIndex = index;
                    scalar = values.get(values.size() - 1);
                } else if (arrayIndex < 0 && key.startsWith(arrayPrefix) && key.indexOf(']') > 0) {
                    arrayIndex = index;
                    arrayFirst = firstArrayElement(key.substring(name.length()), values);
                }
            }
            index++;
        }
        if (scalarIndex < 0 && arrayIndex < 0) {
            return null;
        }
        String value = scalarIndex > arrayIndex ? scalar : arrayFirst;
        return value == null ? "" : value;
    }

    /** {@code brackets} 는 {@code [] · [key] · [a][b]} 처럼 이름 뒤에 붙은 부분이다. */
    private static String firstArrayElement(String brackets, List<String> values) {
        int close = brackets.indexOf(']');
        if (brackets.startsWith("[", close + 1)) {
            return PHP_ARRAY_TO_STRING;
        }
        boolean appendKey = close == 1;
        return appendKey ? values.get(0) : values.get(values.size() - 1);
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
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
