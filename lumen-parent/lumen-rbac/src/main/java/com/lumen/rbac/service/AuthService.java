package com.lumen.rbac.service;

import com.lumen.rbac.dto.LoginRequest;
import com.lumen.rbac.dto.TokenResponse;

public interface AuthService {
    TokenResponse login(LoginRequest req, String ip, String userAgent);
    TokenResponse refresh(String refreshToken);
    void logout(String accessToken);
}
