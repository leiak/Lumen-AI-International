package com.lumen.rbac.security;
import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

@Getter
public class RbacUserDetails implements UserDetails {
    private final Long userId;
    private final Long tenantId;
    private final List<String> roles;
    private final List<String> perms;

    public RbacUserDetails(Long userId, Long tenantId, List<String> roles, List<String> perms) {
        this.userId = userId; this.tenantId = tenantId; this.roles = roles; this.perms = perms;
    }

    @Override public Collection<? extends GrantedAuthority> getAuthorities() {
        return perms.stream().map(p -> (GrantedAuthority) new SimpleGrantedAuthority(p)).collect(Collectors.toList());
    }
    @Override public String getPassword() { return ""; }
    @Override public String getUsername() { return String.valueOf(userId); }
    @Override public boolean isAccountNonExpired() { return true; }
    @Override public boolean isAccountNonLocked() { return true; }
    @Override public boolean isCredentialsNonExpired() { return true; }
    @Override public boolean isEnabled() { return true; }
}
