package com.example.item;

import java.util.List;

/**
 * 검색 결과 한 행. 필드는 동작 보존 테스트의 정규화 결과와 같다: {@code unit} 은 단원 코드, {@code tags} 는 태그 이름(id 순).
 */
public record ItemSearchRowResponse(Integer id, String title, String unit, Integer level, List<String> tags) {

    static ItemSearchRowResponse from(Item item) {
        return new ItemSearchRowResponse(
            item.getId(),
            item.getTitle(),
            item.getUnit().getCode(),
            item.getLevel(),
            item.getTags().stream().map(Tag::getName).toList());
    }
}
