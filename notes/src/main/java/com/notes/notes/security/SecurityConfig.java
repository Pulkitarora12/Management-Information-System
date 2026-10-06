package com.notes.notes.security;

import com.notes.notes.entity.authEntities.AppRole;
import com.notes.notes.entity.authEntities.Role;
import com.notes.notes.entity.authEntities.User;
import com.notes.notes.repository.authRepo.RoleRepository;
import com.notes.notes.repository.authRepo.UserRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true,
        securedEnabled = true,
        jsr250Enabled = true)
public class SecurityConfig {

    @Bean
    SecurityFilterChain defaultSecurityFilterChain(HttpSecurity http) throws Exception {
//        http.csrf(csrf ->
//                csrf.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
//                        .ignoringRequestMatchers("/api/auth/public/**", "public/**", "/logout")
//        );

        http.csrf(csrf -> csrf.disable());

        http.authorizeHttpRequests(requests -> requests
                .requestMatchers("/auth/**", "/verification/**", "/api/csrf-token", "/logout").permitAll()
                .requestMatchers("/admin/**").hasRole("ADMIN")
                .anyRequest().authenticated()
        );

        http.exceptionHandling(exception ->
                exception.authenticationEntryPoint(
                        (request, response, authException) ->
                                response.sendRedirect("/auth/login")
                )
        );

// ---- FORM LOGIN CONFIG ----
        http.formLogin(form -> form
                .loginPage("/auth/login")
                .loginProcessingUrl("/auth/login")
                .defaultSuccessUrl("/auth/dashboard", true)  // Allow login
                .permitAll()
        );

// ---- LOGOUT CONFIG ----
        http.logout(logout -> logout
                .logoutUrl("/logout")
                .invalidateHttpSession(true)
                .deleteCookies("JSESSIONID")
                .logoutSuccessUrl("/auth/login?logout")
                .logoutRequestMatcher(new AntPathRequestMatcher("/logout", "GET"))
                .permitAll()
        );

        return http.build();
    }


    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration authenticationConfiguration) throws Exception {
        return authenticationConfiguration.getAuthenticationManager();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public CommandLineRunner createAdminUser(UserRepository userRepository,
                                             RoleRepository roleRepository,
                                             PasswordEncoder passwordEncoder) {
        return args -> {

            String adminEmail = "info@supermetal.co.in";

            // If admin user already exists, skip
            if (userRepository.findByEmail(adminEmail).isPresent()) {
                return;
            }

            // Ensure ADMIN role exists
            Role adminRole = roleRepository.findByRoleName(AppRole.valueOf(AppRole.ROLE_ADMIN.name()))
                    .orElseGet(() -> {
                        Role newRole = new Role();
                        newRole.setRoleName(AppRole.valueOf(AppRole.ROLE_ADMIN.name()));
                        return roleRepository.save(newRole);
                    });

            Role userRole = roleRepository.findByRoleName(AppRole.valueOf(AppRole.ROLE_USER.name()))
                    .orElseGet(() -> {
                        Role newRole = new Role();
                        newRole.setRoleName(AppRole.valueOf(AppRole.ROLE_USER.name()));
                        return roleRepository.save(newRole);
                    });

            // Create Admin User
            User admin = new User();
            admin.setUserName("superadmin");
            admin.setEmail(adminEmail);
            admin.setPassword(passwordEncoder.encode("Admin@123"));
            admin.setVerified(true);
            admin.setEnabled(true);
            admin.setAccountNonExpired(true);
            admin.setAccountNonLocked(true);
            admin.setCredentialsNonExpired(true);
            admin.setRole(adminRole);

            admin.setEmployeeFullName("Super Admin");
            admin.setEmployeeDepartment("Management");
            admin.setEmployeePhone("9990627700");

            userRepository.save(admin);
        };
    }

}



