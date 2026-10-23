package com.assistance.identity.internal.repo;

import com.assistance.identity.internal.domain.AppUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface AppUserRepository extends JpaRepository<AppUser, UUID> {

    /**
     * Lookup de login: pasa por la función SECURITY DEFINER
     * {@code find_app_user_by_email} (ver V10__identity_app_user.sql) porque
     * en este punto todavía no se conoce el tenant y por tanto no hay
     * {@code app.tenant_id} seteado para que RLS deje pasar una query normal.
     */
    @Query(value = "SELECT * FROM find_app_user_by_email(:email)", nativeQuery = true)
    Optional<AppUser> findActiveByEmailForLogin(@Param("email") String email);
}
