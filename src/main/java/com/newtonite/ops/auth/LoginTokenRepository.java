package com.newtonite.ops.auth;

import org.springframework.data.jpa.repository.JpaRepository;

public interface LoginTokenRepository extends JpaRepository<LoginToken, String> {
}
