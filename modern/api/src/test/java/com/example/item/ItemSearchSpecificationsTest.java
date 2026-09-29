package com.example.item;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 저장소 슬라이스 — H2(MariaDB 모드)에 실제 SQL 을 날린다.
 * H2 는 MariaDB 와 문자열 정렬 · 대소문자 비교가 달라서 그런 단정은 두지 않는다(실제 DB 는 동작 보존 테스트가 본다).
 */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class ItemSearchSpecificationsTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private ItemRepository itemRepository;

    @BeforeEach
    void seed() {
        Unit fraction = entityManager.persist(ItemFixtures.unit(1, "M5-1", "분수의 덧셈과 뺄셈", 5));
        Unit decimal = entityManager.persist(ItemFixtures.unit(3, "M5-3", "소수의 곱셈", 5));
        Tag calc = entityManager.persist(ItemFixtures.tag(1, "계산"));
        Tag essay = entityManager.persist(ItemFixtures.tag(5, "서술형"));

        entityManager.persist(ItemFixtures.item(null, fraction, "분수 덧셈", 1, ItemStatus.ACTIVE, calc));
        entityManager.persist(ItemFixtures.item(null, fraction, "분수 뺄셈", 3, ItemStatus.ACTIVE, calc));
        entityManager.persist(ItemFixtures.item(null, fraction, "분수 검수중", 3, ItemStatus.REVIEWING, calc));
        entityManager.persist(ItemFixtures.item(null, fraction, "분수 삭제됨", 2, ItemStatus.DELETED));
        entityManager.persist(ItemFixtures.item(null, fraction, "분수 서술형", 5, ItemStatus.ACTIVE, essay));
        entityManager.persist(ItemFixtures.item(null, decimal, "소수 곱셈", 2, ItemStatus.ACTIVE));
        entityManager.flush();
        entityManager.clear();
    }

    private List<String> titles(ItemSearchCriteria criteria) {
        Page<Item> page = itemRepository.findAll(
            ItemSearchSpecifications.from(criteria), PageRequest.of(0, ItemSearchCriteria.PAGE_SIZE, criteria.sort()));
        return page.getContent().stream().map(Item::getTitle).toList();
    }

    private static ItemSearchCriteria criteria(String q, String unit, String level, String tag, String sort,
        String dir) {
        return ItemSearchCriteria.of(q, unit, level, tag, sort, dir, "1");
    }

    @Test
    @DisplayName("조건이 없으면 공개 문항 중 난이도 5 를 뺀 것이 level DESC · id ASC 로 나온다")
    void defaultSearchExcludesLevelFiveAndNonPublic() {
        assertThat(titles(criteria("", "", "", "", "", "")))
            .containsExactly("분수 뺄셈", "소수 곱셈", "분수 덧셈");
    }

    @Test
    @DisplayName("난이도 5 를 명시하면 난이도 5 문항이 나오고, 범위 밖 값은 0건이다")
    void explicitLevel() {
        assertThat(titles(criteria("", "", "5", "", "", ""))).containsExactly("분수 서술형");
        assertThat(titles(criteria("", "", "6", "", "", ""))).isEmpty();
        assertThat(titles(criteria("", "", "-1", "", "", ""))).isEmpty();
        assertThat(titles(criteria("", "", "99999999999999999999", "", "", ""))).isEmpty();
    }

    @Test
    @DisplayName("키워드는 제목 또는 지문의 부분 일치이고 % 는 와일드카드로 쓴다")
    void keywordMatchesTitleOrStem() {
        assertThat(titles(criteria("덧셈", "", "", "", "id", "asc"))).containsExactly("분수 덧셈");
        // ItemFixtures 의 지문은 "<제목> 문제 본문" 이다.
        assertThat(titles(criteria("문제 본문", "", "", "", "id", "asc")))
            .containsExactly("분수 덧셈", "분수 뺄셈", "소수 곱셈");
        assertThat(titles(criteria("분%셈", "", "", "", "id", "asc"))).containsExactly("분수 덧셈", "분수 뺄셈");
    }

    @Test
    @DisplayName("단원은 코드가 같은 문항만, 없는 코드는 0건이다")
    void unitCode() {
        assertThat(titles(criteria("", "M5-3", "", "", "", ""))).containsExactly("소수 곱셈");
        assertThat(titles(criteria("", "zz9-99", "", "", "", ""))).isEmpty();
    }

    @Test
    @DisplayName("태그는 이름이 같은 태그가 붙은 문항만, 난이도 조건과 AND 로 묶인다")
    void tagName() {
        assertThat(titles(criteria("", "", "", "계산", "id", "asc"))).containsExactly("분수 덧셈", "분수 뺄셈");
        // 서술형 태그는 난이도 5 문항에만 붙어 있어서, 난이도를 비우면 0건이다.
        assertThat(titles(criteria("", "", "", "서술형", "", ""))).isEmpty();
        assertThat(titles(criteria("", "", "5", "서술형", "", ""))).containsExactly("분수 서술형");
        assertThat(titles(criteria("", "", "", "없는태그", "", ""))).isEmpty();
    }

    @Test
    @DisplayName("정렬: id 내림차순과 단원 코드 정렬(2차 키 난이도 내림차순)")
    void sorting() {
        assertThat(titles(criteria("", "", "", "", "id", "desc")))
            .containsExactly("소수 곱셈", "분수 뺄셈", "분수 덧셈");
        assertThat(titles(criteria("", "", "", "", "unit", "")))
            .containsExactly("분수 뺄셈", "분수 덧셈", "소수 곱셈");
    }

    @Test
    @DisplayName("전체 건수는 페이지와 무관하고, 마지막 페이지를 넘으면 행이 빈다")
    void totalCountAndPastLastPage() {
        ItemSearchCriteria criteria = criteria("", "", "", "", "", "");
        Page<Item> beyond = itemRepository.findAll(
            ItemSearchSpecifications.from(criteria), PageRequest.of(1, ItemSearchCriteria.PAGE_SIZE, criteria.sort()));

        assertThat(beyond.getContent()).isEmpty();
        assertThat(beyond.getTotalElements()).isEqualTo(3);
    }

    @Test
    @DisplayName("findWithDetailsByIdIn: 단원 · 태그를 함께 로드한다")
    void loadsDetailsByIds() {
        List<Integer> ids = itemRepository.findAll().stream().map(Item::getId).toList();
        entityManager.clear();

        List<Item> items = itemRepository.findWithDetailsByIdIn(ids);

        assertThat(items).hasSize(6);
        assertThat(items).allSatisfy(item -> {
            assertThat(Hibernate.isInitialized(item.getUnit())).isTrue();
            assertThat(Hibernate.isInitialized(item.getTags())).isTrue();
        });
    }
}
