package com.example.item;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/** 레거시 search.php 의 입력 해석(BR-02 ~ BR-09, BR-11)을 그대로 옮겼는지 확인한다. */
@ActiveProfiles("test")
class ItemSearchCriteriaTest {

    private static ItemSearchCriteria criteria(String q, String unit, String level, String tag, String sort,
        String dir, String page) {
        return ItemSearchCriteria.of(q, unit, level, tag, sort, dir, page);
    }

    private static ItemSearchCriteria withLevel(String level) {
        return criteria("", "", level, "", "", "", "1");
    }

    private static ItemSearchCriteria withPage(String page) {
        return criteria("", "", "", "", "", "", page);
    }

    private static ItemSearchCriteria withSort(String sort, String dir) {
        return criteria("", "", "", "", sort, dir, "1");
    }

    private static List<String> orders(ItemSearchCriteria criteria) {
        return criteria.sort().stream()
            .map(order -> order.getProperty() + " " + order.getDirection().name().toLowerCase())
            .toList();
    }

    @Test
    @DisplayName("난이도를 비우거나 공백이면 지정하지 않은 것으로 본다(BR-02)")
    void blankLevelIsUnspecified() {
        assertThat(withLevel("").level()).isNull();
        assertThat(withLevel("   ").level()).isNull();
    }

    @Test
    @DisplayName("난이도 1~5 는 그 값 그대로, 그 밖의 값은 정수로 바꿔 쓴다(BR-03)")
    void parsesLevel() {
        assertThat(withLevel("5").level()).isEqualTo(5L);
        assertThat(withLevel("6").level()).isEqualTo(6L);
        assertThat(withLevel("3abc").level()).isEqualTo(3L);
        assertThat(withLevel("-1").level()).isEqualTo(-1L);
        assertThat(withLevel("abc").level()).isZero();
    }

    @Test
    @DisplayName("키워드는 앞뒤 공백을 지우고 100자까지만 쓴다(BR-04)")
    void truncatesKeywordAtHundred() {
        assertThat(criteria("  분수  ", "", "", "", "", "", "1").keyword()).isEqualTo("분수");
        assertThat(criteria("가".repeat(100), "", "", "", "", "", "1").keyword()).hasSize(100);
        assertThat(criteria("가".repeat(101), "", "", "", "", "", "1").keyword()).hasSize(100);
        assertThat(criteria("  ", "", "", "", "", "", "1").hasKeyword()).isFalse();
    }

    @Test
    @DisplayName("단원은 형식이 틀리거나 소문자여도 trim 만 하고 그대로 쓴다(BR-05)")
    void keepsUnitAsGiven() {
        assertThat(criteria("", " m5-1 ", "", "", "", "", "1").unit()).isEqualTo("m5-1");
        assertThat(criteria("", "zz9-99", "", "", "", "", "1").unit()).isEqualTo("zz9-99");
        assertThat(criteria("", " ", "", "", "", "", "1").hasUnit()).isFalse();
    }

    @Test
    @DisplayName("태그는 trim 후 50자까지만 쓴다(BR-07)")
    void truncatesTagAtFifty() {
        assertThat(criteria("", "", "", " 계산 ", "", "", "1").tag()).isEqualTo("계산");
        assertThat(criteria("", "", "", "가".repeat(51), "", "", "1").tag()).hasSize(50);
    }

    @Test
    @DisplayName("정렬을 비우거나 모르는 값이면 level DESC, id ASC 다(BR-09)")
    void defaultSort() {
        assertThat(orders(withSort("", ""))).containsExactly("level desc", "id asc");
        assertThat(orders(withSort("foo", "up"))).containsExactly("level desc", "id asc");
        assertThat(orders(withSort("", "asc"))).containsExactly("level desc", "id asc");
    }

    @Test
    @DisplayName("정렬 기준마다 기본 방향과 2차 키가 다르다(BR-09)")
    void sortDirectionsPerKey() {
        assertThat(orders(withSort("id", ""))).containsExactly("id asc");
        assertThat(orders(withSort("id", "desc"))).containsExactly("id desc");
        assertThat(orders(withSort("title", ""))).containsExactly("title asc", "id asc");
        assertThat(orders(withSort("title", "desc"))).containsExactly("title desc", "id asc");
        assertThat(orders(withSort("unit", ""))).containsExactly("unit.code asc", "level desc", "id asc");
        assertThat(orders(withSort("unit", "desc"))).containsExactly("unit.code desc", "level desc", "id asc");
        assertThat(orders(withSort("level", ""))).containsExactly("level desc", "id asc");
        assertThat(orders(withSort("level", "asc"))).containsExactly("level asc", "id asc");
        assertThat(orders(withSort("created", ""))).containsExactly("createdAt desc", "id asc");
        assertThat(orders(withSort("created", "asc"))).containsExactly("createdAt asc", "id asc");
    }

    @Test
    @DisplayName("정렬 기준과 방향은 대소문자 · 앞뒤 공백을 무시한다(BR-09)")
    void sortIsCaseInsensitive() {
        assertThat(orders(withSort(" TITLE ", " DESC "))).containsExactly("title desc", "id asc");
        assertThat(withSort("id", "asc").sort()).isEqualTo(Sort.by(Sort.Order.asc("id")));
    }

    @Test
    @DisplayName("페이지는 1~999 로 보정하고 숫자가 아니면 1페이지다(BR-11)")
    void clampsPage() {
        assertThat(withPage("1").page()).isEqualTo(1);
        assertThat(withPage("2").page()).isEqualTo(2);
        assertThat(withPage("0").page()).isEqualTo(1);
        assertThat(withPage("999").page()).isEqualTo(999);
        assertThat(withPage("1000").page()).isEqualTo(999);
        assertThat(withPage("99999999999999999999").page()).isEqualTo(999);
        assertThat(withPage("-1").page()).isEqualTo(1);
        assertThat(withPage("abc").page()).isEqualTo(1);
        assertThat(withPage("").page()).isEqualTo(1);
        assertThat(withPage(" 2").page()).isEqualTo(1);
    }

    @Test
    @DisplayName("파라미터가 없으면 조건 없는 기본 검색이 된다")
    void emptyParamsMeansDefaultSearch() {
        ItemSearchCriteria criteria = ItemSearchCriteria.fromParams(new LinkedMultiValueMap<>());

        assertThat(criteria.hasKeyword()).isFalse();
        assertThat(criteria.hasUnit()).isFalse();
        assertThat(criteria.hasTag()).isFalse();
        assertThat(criteria.level()).isNull();
        assertThat(criteria.page()).isEqualTo(1);
        assertThat(orders(criteria)).containsExactly("level desc", "id asc");
    }

    @Test
    @DisplayName("같은 이름이 여러 번 오면 PHP 처럼 마지막 값을 쓴다")
    void usesLastValueOfRepeatedParam() {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("q", "첫째");
        params.add("q", "마지막");
        params.add("page", "2");

        ItemSearchCriteria criteria = ItemSearchCriteria.fromParams(params);

        assertThat(criteria.keyword()).isEqualTo("마지막");
        assertThat(criteria.page()).isEqualTo(2);
    }
}
