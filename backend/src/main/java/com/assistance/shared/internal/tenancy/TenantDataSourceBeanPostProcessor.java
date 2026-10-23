package com.assistance.shared.internal.tenancy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/**
 * Envuelve el {@code DataSource} principal de la aplicación (bean
 * {@code "dataSource"}, el nombre por defecto que usa
 * {@code DataSourceAutoConfiguration} de Spring Boot) con
 * {@link TenantAwareDataSource}, sin tocar {@code application.yml} ni la
 * configuración del datasource en sí.
 *
 * <p>No se envuelve ningún otro {@code DataSource} con otro nombre de bean
 * (p. ej. uno dedicado a Flyway con el rol de migración/superusuario) — ese
 * no necesita ni debe llevar GUCs de tenant, corre como rol administrativo.
 *
 * <p><b>Nota para AGENTE A</b>: si el datasource principal de la app se
 * declara con otro nombre de bean (no {@code "dataSource"}), este
 * post-processor no lo detectará y la propagación de tenant NO aplicará.
 * Verificar o ajustar el nombre aquí.
 */
@Component
public class TenantDataSourceBeanPostProcessor implements BeanPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(TenantDataSourceBeanPostProcessor.class);

    static final String TARGET_BEAN_NAME = "dataSource";

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (TARGET_BEAN_NAME.equals(beanName)
                && bean instanceof DataSource dataSource
                && !(bean instanceof TenantAwareDataSource)) {
            log.info("Envolviendo bean '{}' con TenantAwareDataSource para propagar app.tenant_id", beanName);
            return new TenantAwareDataSource(dataSource);
        }
        return bean;
    }
}
