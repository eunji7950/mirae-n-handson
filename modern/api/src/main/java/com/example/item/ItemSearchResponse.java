package com.example.item;

import java.util.List;

/**
 * 문항 검색 응답. {@code count} 는 조건에 맞는 전체 건수이고(현재 페이지의 행 수가 아니다),
 * {@code message} 는 0건일 때만 값이 있다.
 */
public record ItemSearchResponse(List<ItemSearchRowResponse> rows, long count, String message) {
}
