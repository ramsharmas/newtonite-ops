package com.newtonite.ops.web;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface IdempotencyRepository extends JpaRepository<IdempotencyRecord, Long> {

	Optional<IdempotencyRecord> findByUserIdAndIdemKey(Long userId, String idemKey);
}
