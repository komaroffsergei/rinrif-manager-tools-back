package ru.reinform.rinrif.managertools.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter;
import ru.reinform.cdp.security.config.BaseSecurityConfig;
import ru.reinform.rinrif.common.security.RinrifSecurityConfig;

@Configuration
@ConditionalOnProperty(name = "manager-tools.security.enabled", havingValue = "true", matchIfMissing = true)
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

@Configuration
@ConditionalOnProperty(name = "manager-tools.security.enabled", havingValue = "false")
@EnableWebSecurity
class LocalSecurityConfig extends WebSecurityConfigurerAdapter {
    @Override
    protected void configure(HttpSecurity httpSecurity) throws Exception {
        httpSecurity.csrf().disable().authorizeRequests().anyRequest().permitAll();
    }
}
