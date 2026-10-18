package com.newtonite.ops.items;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ItemEventRepository extends JpaRepository<ItemEvent, Long> {

	List<ItemEvent> findByItemIdOrderByIdAsc(Long itemId);
}
