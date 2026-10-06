package com.notes.notes.security.services;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

import com.notes.notes.entity.authEntities.User;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import com.fasterxml.jackson.annotation.JsonIgnore;

@NoArgsConstructor
@Data
public class UserDetailsImpl implements UserDetails {
    private static final long serialVersionUID = 1L;

    private Long id;
    private String username;
    private String email;

    @JsonIgnore
    private String password;

    private boolean is2faEnabled;

    private Collection<? extends GrantedAuthority> authorities;

    private boolean isEnabled;
    private boolean isVerified;
    private boolean isAccountNonLocked;
    private boolean isAccountNonExpired;
    private boolean isCredentialsNonExpired;

    private String employeeFullName;
    private String employeeDepartment;
    private String employeePhone;

    public UserDetailsImpl(
            Long id,
            String username,
            String email,
            String password,
            boolean is2faEnabled,
            boolean enabled,
            boolean verified,
            boolean accountNonLocked,
            boolean accountNonExpired,
            boolean credentialsNonExpired,
            Collection<? extends GrantedAuthority> authorities,
            String employeeFullName,
            String employeeDepartment,
            String employeePhone
    ) {
        this.id = id;
        this.username = username;
        this.email = email;
        this.password = password;
        this.is2faEnabled = is2faEnabled;
        this.isEnabled = enabled;
        this.isVerified = verified;
        this.isAccountNonLocked = accountNonLocked;
        this.isAccountNonExpired = accountNonExpired;
        this.isCredentialsNonExpired = credentialsNonExpired;
        this.authorities = authorities;
        this.employeeFullName = employeeFullName;
        this.employeeDepartment = employeeDepartment;
        this.employeePhone = employeePhone;
    }



    public static UserDetailsImpl build(User user) {
        GrantedAuthority authority = new SimpleGrantedAuthority(user.getRole().getRoleName().name());

        return new UserDetailsImpl(
                user.getUserId(),
                user.getUserName(),
                user.getEmail(),
                user.getPassword(),
                user.isTwoFactorEnabled(),
                user.isEnabled(),
                user.isVerified(),
                user.isAccountNonLocked(),
                user.isAccountNonExpired(),
                user.isCredentialsNonExpired(),
                List.of(authority),
                user.getEmployeeFullName(),
                user.getEmployeeDepartment(),
                user.getEmployeePhone()
        );
    }


    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    public Long getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    @Override
    public String getPassword() {
        return password;
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public boolean isAccountNonExpired() {
        return this.isAccountNonExpired;
    }

    @Override
    public boolean isAccountNonLocked() {
        return this.isAccountNonLocked;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return this.isCredentialsNonExpired;
    }

    @Override
    public boolean isEnabled() {
        return this.isEnabled;
    }

    public boolean is2faEnabled() {
        return is2faEnabled;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        UserDetailsImpl user = (UserDetailsImpl) o;
        return Objects.equals(id, user.id);
    }

}
