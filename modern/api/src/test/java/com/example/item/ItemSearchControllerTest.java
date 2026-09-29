package com.example.item;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** 컨트롤러 슬라이스 — 서비스는 목. 경로 · 파라미터 이름 · JSON 필드가 동작 보존 테스트의 기대와 같은지 본다. */
@WebMvcTest({ItemSearchController.class, ItemController.class})
@ActiveProfiles("test")
class ItemSearchControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ItemSearchService itemSearchService;

    @MockBean
    private ItemService itemService;

    @Test
    @DisplayName("GET /api/items/search → 200, rows · count · message 만 담긴 JSON")
    void returnsRowsCountMessage() throws Exception {
        when(itemSearchService.search(any(ItemSearchCriteria.class))).thenReturn(new ItemSearchResponse(
            List.of(new ItemSearchRowResponse(12, "대분수의 덧셈", "M5-1", 3, List.of("계산", "오답률높음"))), 1, null));

        mockMvc.perform(get("/api/items/search").param("unit", "M5-1").param("level", "3").param("q", "분수"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.count").value(1))
            .andExpect(jsonPath("$.message").doesNotExist())
            .andExpect(jsonPath("$.rows[0].id").value(12))
            .andExpect(jsonPath("$.rows[0].title").value("대분수의 덧셈"))
            .andExpect(jsonPath("$.rows[0].unit").value("M5-1"))
            .andExpect(jsonPath("$.rows[0].level").value(3))
            .andExpect(jsonPath("$.rows[0].tags[1]").value("오답률높음"))
            .andExpect(jsonPath("$.rows[0].stem").doesNotExist())
            .andExpect(jsonPath("$.rows[0].status").doesNotExist());
    }

    @Test
    @DisplayName("GET /api/items/search → 0건이어도 200 이고 안내 메시지가 담긴다")
    void zeroResultsIsOk() throws Exception {
        when(itemSearchService.search(any(ItemSearchCriteria.class)))
            .thenReturn(new ItemSearchResponse(List.of(), 0, "검색 결과가 없습니다"));

        mockMvc.perform(get("/api/items/search").param("level", "6"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.rows").isEmpty())
            .andExpect(jsonPath("$.count").value(0))
            .andExpect(jsonPath("$.message").value("검색 결과가 없습니다"));
    }

    @Test
    @DisplayName("GET /api/items/search → 숫자가 아닌 page · level 도 400 이 아니라 서비스로 넘어간다")
    void oddValuesAreNotRejected() throws Exception {
        when(itemSearchService.search(any(ItemSearchCriteria.class)))
            .thenReturn(new ItemSearchResponse(List.of(), 0, "검색 결과가 없습니다"));

        mockMvc.perform(get("/api/items/search").param("page", "abc").param("level", "-1").param("sort", "foo"))
            .andExpect(status().isOk());

        ArgumentCaptor<ItemSearchCriteria> captor = ArgumentCaptor.forClass(ItemSearchCriteria.class);
        verify(itemSearchService).search(captor.capture());
        assertThat(captor.getValue().page()).isEqualTo(1);
        assertThat(captor.getValue().level()).isEqualTo(-1L);
    }

    @Test
    @DisplayName("GET /api/items/search 는 /api/items/{id} 로 해석되지 않는다")
    void searchPathBeatsIdPath() throws Exception {
        when(itemSearchService.search(any(ItemSearchCriteria.class)))
            .thenReturn(new ItemSearchResponse(List.of(), 0, "검색 결과가 없습니다"));

        mockMvc.perform(get("/api/items/search"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.count").value(0));
    }
}
