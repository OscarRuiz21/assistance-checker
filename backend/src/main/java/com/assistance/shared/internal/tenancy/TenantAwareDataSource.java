package com.assistance.shared.internal.tenancy;

import com.assistance.shared.api.TenantContext;
import com.assistance.shared.api.TenantScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.datasource.DelegatingDataSource;

import javax.sql.DataSource;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Optional;

/**
 * Propaga el {@link TenantContext} del hilo actual a cada conexión JDBC que
 * sale del pool, vía {@code set_config('app.tenant_id', ...)} +
 * {@code app.actor_user_id} / {@code app.actor_kiosko_id} (usados por el
 * trigger {@code write_audit_log()} y por las políticas RLS).
 *
 * <p>Esto cubre tanto JPA (Hibernate toma una conexión por transacción vía
 * {@code DataSourceUtils}) como {@code JdbcTemplate} con SQL nativo, porque
 * ambos obtienen la conexión a través de este mismo {@link DataSource}.
 *
 * <p><b>Fail-closed</b>: si no hay {@link TenantContext} en el hilo, se hace
 * {@code set_config(..., NULL, false)} (equivalente a {@code RESET}), de modo
 * que {@code current_setting('app.tenant_id', true)} regrese {@code NULL} y
 * las políticas RLS ({@code tenant_id = NULL}) no matcheen ninguna fila.
 *
 * <p>También se envuelve la conexión devuelta para limpiar las GUCs de sesión
 * en {@code close()}, como defensa adicional contra que el pool reutilice una
 * conexión "sucia" si algún código obtuviera la conexión por otra vía.
 */
public class TenantAwareDataSource extends DelegatingDataSource {

    private static final Logger log = LoggerFactory.getLogger(TenantAwareDataSource.class);

    private static final String SET_GUC_SQL =
            "SELECT set_config('app.tenant_id', ?, false), "
                    + "set_config('app.actor_user_id', ?, false), "
                    + "set_config('app.actor_kiosko_id', ?, false)";

    public TenantAwareDataSource(DataSource delegate) {
        super(delegate);
    }

    @Override
    public Connection getConnection() throws SQLException {
        Connection connection = super.getConnection();
        applyTenantGucs(connection);
        return wrap(connection);
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        Connection connection = super.getConnection(username, password);
        applyTenantGucs(connection);
        return wrap(connection);
    }

    private void applyTenantGucs(Connection connection) {
        Optional<TenantScope> scope = TenantContext.current();
        String tenantId = scope.map(s -> s.tenantId().toString()).orElse(null);
        String userId = scope.map(TenantScope::userId).map(Object::toString).orElse(null);
        String kioskoId = scope.map(TenantScope::kioskoId).map(Object::toString).orElse(null);
        try (PreparedStatement ps = connection.prepareStatement(SET_GUC_SQL)) {
            ps.setString(1, tenantId);
            ps.setString(2, userId);
            ps.setString(3, kioskoId);
            ps.execute();
        } catch (SQLException e) {
            log.error("No se pudo propagar app.tenant_id a la conexión JDBC; fail-closed", e);
            throw new IllegalStateException("No se pudo propagar tenant a la conexión JDBC", e);
        }
    }

    private void resetTenantGucs(Connection connection) {
        try (PreparedStatement ps = connection.prepareStatement(SET_GUC_SQL)) {
            ps.setString(1, null);
            ps.setString(2, null);
            ps.setString(3, null);
            ps.execute();
        } catch (SQLException e) {
            log.warn("No se pudieron limpiar las GUCs de tenant al devolver la conexión al pool", e);
        }
    }

    /**
     * Proxy dinámico que, al cerrar la conexión (es decir, al devolverla al
     * pool), limpia las GUCs de tenant/actor antes de delegar el close real.
     * Defensa en profundidad: aunque hoy toda conexión pasa por
     * {@link #getConnection()} (que ya fija el tenant correcto en cada
     * checkout), esto evita que una conexión "recordada" fuera de ciclo
     * normal filtre el tenant anterior a quien la reciba después.
     */
    private Connection wrap(Connection target) {
        InvocationHandler handler = (proxy, method, args) -> {
            if ("close".equals(method.getName())) {
                resetTenantGucs(target);
            }
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException e) {
                throw e.getTargetException();
            }
        };
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[] {Connection.class},
                handler);
    }
}
