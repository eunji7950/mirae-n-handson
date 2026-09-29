package com.example.item;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("test")
class LegacyInputTest {

    @Test
    @DisplayName("trim: ASCII 공백만 지우고 전각 공백은 남긴다(PHP trim)")
    void trimsAsciiWhitespaceOnly() {
        assertThat(LegacyInput.trim(" \t분수\n\r")).isEqualTo("분수");
        assertThat(LegacyInput.trim("　분수　")).isEqualTo("　분수　");
        assertThat(LegacyInput.trim("   ")).isEmpty();
    }

    @Test
    @DisplayName("length · truncate: UTF-16 이 아니라 코드포인트 단위로 센다")
    void countsCodePoints() {
        String emoji = "😀".repeat(3);

        assertThat(LegacyInput.length(emoji)).isEqualTo(3);
        assertThat(LegacyInput.truncate(emoji, 2)).isEqualTo("😀😀");
        assertThat(LegacyInput.truncate("가나다", 5)).isEqualTo("가나다");
    }

    @Test
    @DisplayName("asciiLowerCase: ASCII 대문자만 소문자로 바꾼다")
    void lowersAsciiOnly() {
        assertThat(LegacyInput.asciiLowerCase("SoRt")).isEqualTo("sort");
        assertThat(LegacyInput.asciiLowerCase("Ä분")).isEqualTo("Ä분");
    }

    @Test
    @DisplayName("toLong: PHP (int) 처럼 앞쪽 숫자만 읽는다")
    void castsLikePhpInt() {
        assertThat(LegacyInput.toLong("3abc")).isEqualTo(3L);
        assertThat(LegacyInput.toLong("03")).isEqualTo(3L);
        assertThat(LegacyInput.toLong("3.9")).isEqualTo(3L);
        assertThat(LegacyInput.toLong("-1")).isEqualTo(-1L);
        assertThat(LegacyInput.toLong("+5")).isEqualTo(5L);
        assertThat(LegacyInput.toLong("1e3")).isEqualTo(1000L);
        assertThat(LegacyInput.toLong("abc")).isZero();
        assertThat(LegacyInput.toLong("")).isZero();
    }

    @Test
    @DisplayName("toLong: 64비트를 넘는 정수는 최댓값 · 최솟값으로 자른다")
    void clampsOverflow() {
        assertThat(LegacyInput.toLong("99999999999999999999")).isEqualTo(Long.MAX_VALUE);
        assertThat(LegacyInput.toLong("-99999999999999999999")).isEqualTo(Long.MIN_VALUE);
        assertThat(LegacyInput.digitsToLong("99999999999999999999")).isEqualTo(Long.MAX_VALUE);
    }
}
