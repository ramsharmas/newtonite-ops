package com.newtonite.ops.items;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CommentRepository extends JpaRepository<Comment, Long> {

	long countByItemId(Long itemId);
}
