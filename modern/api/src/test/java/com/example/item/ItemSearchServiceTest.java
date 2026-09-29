package com.example.item;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.context.ActiveProfiles;

@ExtendWith(MockitoExtension.class)
@ActiveProfiles("test")
class ItemSearchServiceTest {

    @Mock
    private ItemRepository itemRepository;

    @InjectMocks
    private ItemSearchService itemSearchService;

    private final Unit unit = ItemFixtures.unit(1, "M5-1", "분수의 덧셈과 뺄셈", 5);
    private final Tag calc = ItemFixtures.tag(1, "계산");

    private static ItemSearchCriteria criteria(String level, String page) {
        return ItemSearchCriteria.of("", "", level, "", "", "", page);
    }

    @Test
    @DisplayName("search: 페이지의 id 순서를 유지하고 단원 코드 · 태그 이름을 담는다")
    void keepsPageOrderAndMapsFields() {
        Item first = ItemFixtures.item(12, unit, "대분수의 덧셈", 3, ItemStatus.ACTIVE, calc);
        Item second = ItemFixtures.item(3, unit, "분모가 같은 분수의 덧셈", 1, ItemStatus.ACTIVE);
        when(itemRepository.findAll(any(Specification.class), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(first, second), PageRequest.of(0, 20), 2));
        // 상세 조회는 순서를 보장하지 않으므로 일부러 뒤집어서 돌려준다.
        when(itemRepository.findWithDetailsByIdIn(anyCollection())).thenReturn(List.of(second, first));

        ItemSearchResponse response = itemSearchService.search(criteria("", "1"));

        assertThat(response.count()).isEqualTo(2);
        assertThat(response.message()).isNull();
        assertThat(response.rows()).extracting(ItemSearchRowResponse::id).containsExactly(12, 3);
        assertThat(response.rows().get(0)).isEqualTo(
            new ItemSearchRowResponse(12, "대분수의 덧셈", "M5-1", 3, List.of("계산")));
        assertThat(response.rows().get(1).tags()).isEmpty();
    }

    @Test
    @DisplayName("search: 0건이면 안내 메시지를 채우고 상세 조회는 하지 않는다")
    void zeroResultsHasMessage() {
        when(itemRepository.findAll(any(Specification.class), any(Pageable.class)))
            .thenReturn(Page.empty());

        ItemSearchResponse response = itemSearchService.search(criteria("6", "1"));

        assertThat(response.rows()).isEmpty();
        assertThat(response.count()).isZero();
        assertThat(response.message()).isEqualTo("검색 결과가 없습니다");
        verify(itemRepository, never()).findWithDetailsByIdIn(anyCollection());
    }

    @Test
    @DisplayName("search: 마지막 페이지를 넘으면 건수는 그대로이고 행은 비고 메시지는 없다")
    void pastLastPageKeepsCount() {
        when(itemRepository.findAll(any(Specification.class), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(), PageRequest.of(1, 20), 20));

        ItemSearchResponse response = itemSearchService.search(criteria("", "2"));

        assertThat(response.rows()).isEmpty();
        assertThat(response.count()).isEqualTo(20);
        assertThat(response.message()).isNull();
    }

    @Test
    @DisplayName("search: 페이지 크기 20, 페이지 번호는 0 기반으로 넘기고 999 를 넘지 않는다")
    void passesPageRequest() {
        when(itemRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(Page.empty());

        itemSearchService.search(criteria("", "1000"));

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(itemRepository).findAll(any(Specification.class), captor.capture());
        assertThat(captor.getValue().getPageSize()).isEqualTo(20);
        assertThat(captor.getValue().getPageNumber()).isEqualTo(998);
        assertThat(captor.getValue().getSort()).isEqualTo(Sort.by(Sort.Order.desc("level"), Sort.Order.asc("id")));
    }
}
