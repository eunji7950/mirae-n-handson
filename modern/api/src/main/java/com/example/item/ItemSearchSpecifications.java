package com.example.item;

import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.jpa.domain.Specification;

/** 문항 검색 조건을 JPA {@link Specification} 으로 바꾼다. 조건은 모두 AND 로 묶는다. */
final class ItemSearchSpecifications {

    private static final String LIKE_WILDCARD = "%";

    private ItemSearchSpecifications() {
    }

    static Specification<Item> from(ItemSearchCriteria criteria) {
        Specification<Item> spec = active();
        if (criteria.hasKeyword()) {
            spec = spec.and(keywordInTitleOrStem(criteria.keyword()));
        }
        if (criteria.hasUnit()) {
            spec = spec.and(unitCodeEquals(criteria.unit()));
        }
        spec = spec.and(levelCondition(criteria.level()));
        if (criteria.hasTag()) {
            spec = spec.and(hasTagNamed(criteria.tag()));
        }
        return spec;
    }

    /** 공개({@code status = 'A'}) 문항만. */
    static Specification<Item> active() {
        return (root, query, cb) -> cb.equal(root.get("status"), ItemStatus.ACTIVE);
    }

    /** 제목 또는 지문에 키워드가 들어 있다. {@code %} · {@code _} 는 이스케이프하지 않고 와일드카드로 쓴다. */
    static Specification<Item> keywordInTitleOrStem(String keyword) {
        String pattern = LIKE_WILDCARD + keyword + LIKE_WILDCARD;
        return (root, query, cb) -> cb.or(
            cb.like(root.get("title"), pattern),
            cb.like(root.get("stem"), pattern));
    }

    static Specification<Item> unitCodeEquals(String unitCode) {
        return (root, query, cb) -> cb.equal(root.get("unit").get("code"), unitCode);
    }

    /**
     * 난이도 조건. 지정하지 않으면({@code null}) 난이도 5 미만만, 지정하면 그 값과 같은 문항만 찾는다.
     * 범위 밖 값(0, 음수, 아주 큰 수)도 그대로 비교하므로 결과는 대개 0건이다.
     */
    static Specification<Item> levelCondition(Long level) {
        if (level == null) {
            return (root, query, cb) ->
                cb.lessThan(root.<Integer>get("level"), ItemSearchCriteria.LEVEL_EXCLUSIVE_UPPER_BOUND);
        }
        if (level < Integer.MIN_VALUE || level > Integer.MAX_VALUE) {
            return (root, query, cb) -> cb.disjunction();
        }
        int value = level.intValue();
        return (root, query, cb) -> cb.equal(root.get("level"), value);
    }

    /** 이름이 정확히 같은 태그가 붙은 문항만. */
    static Specification<Item> hasTagNamed(String tagName) {
        return (root, query, cb) -> {
            Subquery<Integer> subquery = query.subquery(Integer.class);
            Root<Item> tagged = subquery.correlate(root);
            Join<Item, Tag> tags = tagged.join("tags");
            subquery.select(cb.literal(1)).where(cb.equal(tags.get("name"), tagName));
            return cb.exists(subquery);
        };
    }
}
