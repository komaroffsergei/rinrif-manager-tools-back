package ru.reinform.rinrif.managertools.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import ru.reinform.cdp.security.config.BaseSecurityConfig;
import ru.reinform.rinrif.common.security.RinrifSecurityConfig;

@Configuration
public class SecurityConfig extends BaseSecurityConfig {
    private final RinrifSecurityConfig rinrifSecurityConfig;

    public SecurityConfig(RinrifSecurityConfig rinrifSecurityConfig) {
        this.rinrifSecurityConfig = rinrifSecurityConfig;
    }

    @Override
    protected HttpSecurity authorizeRequests(HttpSecurity httpSecurity) throws Exception {
        rinrifSecurityConfig.buildHttpSecurity(httpSecurity);
        return httpSecurity.authorizeRequests().and();
    }
}
