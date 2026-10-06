package com.example.item;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 문항 검색 서비스. 레거시 {@code search.php} 의 조회 동작을 옮겼다.
 * 엔티티는 트랜잭션 안에서 DTO 로 바꿔 돌려준다(OSIV 꺼져 있음).
 */
@Service
@Transactional(readOnly = true)
public class ItemSearchService {

    static final String NO_RESULT_MESSAGE = "검색 결과가 없습니다";

    private static final Logger log = LoggerFactory.getLogger(ItemSearchService.class);

    private final ItemRepository itemRepository;

    public ItemSearchService(ItemRepository itemRepository) {
        this.itemRepository = itemRepository;
    }

    /**
     * 조건에 맞는 공개 문항을 한 페이지(20건) 돌려준다. 없는 단원 · 태그 · 범위 밖 난이도는 오류가 아니라 0건이다.
     * 마지막 페이지를 넘어선 번호는 건수는 그대로이고 행만 비어 있다.
     */
    public ItemSearchResponse search(ItemSearchCriteria criteria) {
        Page<Item> page = itemRepository.findAll(
            ItemSearchSpecifications.from(criteria),
            PageRequest.of(criteria.page() - 1, ItemSearchCriteria.PAGE_SIZE, criteria.sort()));

        List<ItemSearchRowResponse> rows = loadRows(page.getContent());
        long total = page.getTotalElements();
        log.debug("item search: page={}, rows={}, total={}", criteria.page(), rows.size(), total);
        return new ItemSearchResponse(rows, total, total == 0 ? NO_RESULT_MESSAGE : null);
    }

    /**
     * 페이지의 id 순서를 유지한 채 단원 · 태그를 한 번에 가져와 행으로 바꾼다(컬렉션 fetch 와 페이징을 분리).
     * 두 조회 사이에 사라진 행은 건너뛴다. 레거시도 행 조회와 건수 조회가 별개 쿼리라 같은 상황에서 행만 빠진다.
     */
    private List<ItemSearchRowResponse> loadRows(List<Item> pageItems) {
        if (pageItems.isEmpty()) {
            return List.of();
        }
        List<Integer> ids = pageItems.stream().map(Item::getId).toList();
        Map<Integer, Item> byId = itemRepository.findWithDetailsByIdIn(ids).stream()
            .collect(Collectors.toMap(Item::getId, Function.identity()));
        return ids.stream()
            .map(byId::get)
            .filter(Objects::nonNull)
            .map(ItemSearchRowResponse::from)
            .toList();
    }
}
