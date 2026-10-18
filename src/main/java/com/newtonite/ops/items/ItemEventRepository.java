package com.newtonite.ops.items;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ItemEventRepository extends JpaRepository<ItemEvent, Long> {

	/** The newest events of an item first. Ask for one more than you show to learn whether there are older ones. */
	List<ItemEvent> findByItemIdOrderByIdDesc(Long itemId, Pageable page);

	/** The same, starting just before an event you already have. */
	List<ItemEvent> findByItemIdAndIdLessThanOrderByIdDesc(Long itemId, Long before, Pageable page);

	long countByItemId(Long itemId);
}
