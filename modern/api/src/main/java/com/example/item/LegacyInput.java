package com.example.item;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 레거시 {@code search.php}(PHP 7.4)의 입력 처리를 그대로 옮긴 함수 모음.
 * Java 기본 함수와 결과가 다른 곳(공백 범위 · 글자 수 · 숫자 변환)에서 레거시 동작을 유지한다.
 */
final class LegacyInput {

    /** PHP {@code trim()} 기본 문자: 공백 · 탭 · 개행 · 캐리지리턴 · NUL · 세로 탭. */
    private static final String TRIM_CHARS = " \t\n\r\0\u000B";

    /** PHP 가 숫자 문자열 앞에서 건너뛰는 공백. */
    private static final String LEADING_WHITESPACE = " \t\n\r\u000B\f";

    /** PHP 가 읽는 숫자 접두어: 부호, 정수부, 소수부, 지수부. */
    private static final Pattern NUMERIC_PREFIX =
        Pattern.compile("^([+-]?)(\\d+(?:\\.\\d*)?|\\.\\d+)([eE][+-]?\\d+)?");

    private static final BigInteger LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE);
    private static final BigInteger LONG_MIN = BigInteger.valueOf(Long.MIN_VALUE);

    private LegacyInput() {
    }

    /** PHP {@code trim()}. 전각 공백 등 ASCII 밖의 공백은 지우지 않는다. */
    static String trim(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && TRIM_CHARS.indexOf(value.charAt(start)) >= 0) {
            start++;
        }
        while (end > start && TRIM_CHARS.indexOf(value.charAt(end - 1)) >= 0) {
            end--;
        }
        return value.substring(start, end);
    }

    /** PHP {@code mb_strlen($s, 'UTF-8')} — 코드포인트 수. */
    static int length(String value) {
        return value.codePointCount(0, value.length());
    }

    /** PHP {@code mb_substr($s, 0, $max, 'UTF-8')} — 코드포인트 단위로 자른다. */
    static String truncate(String value, int maxCodePoints) {
        if (length(value) <= maxCodePoints) {
            return value;
        }
        return value.substring(0, value.offsetByCodePoints(0, maxCodePoints));
    }

    /** PHP {@code strtolower()} — ASCII 대문자만 바꾼다. */
    static String asciiLowerCase(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            out.append(c >= 'A' && c <= 'Z' ? (char) (c + ('a' - 'A')) : c);
        }
        return out.toString();
    }

    /**
     * PHP 7.4 {@code (int)$string}. 앞쪽 숫자만 읽고(`3abc`=3, `3.9`=3, `1e3`=1000),
     * 숫자가 아니면 0, 범위를 넘는 정수는 64비트 최댓값 · 최솟값으로 자른다.
     */
    static long toLong(String value) {
        int start = 0;
        while (start < value.length() && LEADING_WHITESPACE.indexOf(value.charAt(start)) >= 0) {
            start++;
        }
        Matcher matcher = NUMERIC_PREFIX.matcher(value.substring(start));
        if (!matcher.find()) {
            return 0L;
        }
        boolean isFloat = matcher.group(2).contains(".") || matcher.group(3) != null;
        if (isFloat) {
            double parsed = Double.parseDouble(matcher.group());
            if (Double.isNaN(parsed) || Double.isInfinite(parsed)) {
                return 0L;
            }
            return clamp(BigDecimal.valueOf(parsed).toBigInteger());
        }
        return clamp(new BigInteger(matcher.group().replace("+", "")));
    }

    /** 숫자만으로 된 문자열을 64비트 범위로 자른 값. */
    static long digitsToLong(String digits) {
        return clamp(new BigInteger(digits));
    }

    private static long clamp(BigInteger value) {
        if (value.compareTo(LONG_MAX) > 0) {
            return Long.MAX_VALUE;
        }
        if (value.compareTo(LONG_MIN) < 0) {
            return Long.MIN_VALUE;
        }
        return value.longValue();
    }
}
