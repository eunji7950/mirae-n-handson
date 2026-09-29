package com.example.item;

import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 문항 검색 API. 레거시 {@code GET /search.php} 와 파라미터 이름이 같다
 * ({@code q · unit · level · tag · sort · dir · page}). 값 해석은 {@link ItemSearchCriteria} 가 한다.
 */
@RestController
@RequestMapping("/api/items")
public class ItemSearchController {

    private final ItemSearchService itemSearchService;

    public ItemSearchController(ItemSearchService itemSearchService) {
        this.itemSearchService = itemSearchService;
    }

    /** {@code GET /api/items/search} — 공개 문항 검색. 잘못된 값도 400 이 아니라 레거시처럼 보정하거나 0건으로 응답한다. */
    @GetMapping("/search")
    public ItemSearchResponse search(@RequestParam MultiValueMap<String, String> params) {
        return itemSearchService.search(ItemSearchCriteria.fromParams(params));
    }
}
