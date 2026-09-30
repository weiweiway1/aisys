package com.aisys.auth.controller;

import com.aisys.auth.dto.*;
import com.aisys.auth.service.AuthService;
import com.aisys.auth.service.MenuService;
import com.aisys.common.core.context.UserContext;
import com.aisys.common.core.response.ApiResponse;
import com.aisys.common.log.annotation.AuditLog;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;
    private final MenuService menuService;

    public AuthController(AuthService authService, MenuService menuService) {
        this.authService = authService;
        this.menuService = menuService;
    }

    @PostMapping("/login")
    @AuditLog(action = "LOGIN", resource = "USER", description = "用户登录")
    public ApiResponse<TokenResponse> login(@Valid @RequestBody LoginRequest req) {
        return ApiResponse.success(authService.login(req));
    }

    @PostMapping("/register")
    public ApiResponse<Long> register(@Valid @RequestBody RegisterRequest req) {
        return ApiResponse.success(authService.register(req));
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(HttpServletRequest request) {
        String auth = request.getHeader("Authorization");
        String token = (auth != null && auth.startsWith("Bearer ")) ? auth.substring(7) : null;
        authService.logout(token, UserContext.getUserId());
        return ApiResponse.success();
    }

    @PostMapping("/refresh")
    public ApiResponse<TokenResponse> refresh(@Valid @RequestBody RefreshRequest req) {
        return ApiResponse.success(authService.refresh(req));
    }

    @GetMapping("/me")
    public ApiResponse<MeResponse> me() {
        return ApiResponse.success(authService.me(UserContext.getUserId()));
    }

    /** 当前用户可访问的菜单树（后端动态路由，DDD 6.1 getAsyncRoutes）。 */
    @GetMapping("/menus")
    public ApiResponse<List<MenuNode>> menus() {
        return ApiResponse.success(menuService.menusForCurrentUser());
    }

    @PutMapping("/password")
    @AuditLog(action = "UPDATE", resource = "USER", description = "修改密码", resourceId = "#result")
    public ApiResponse<Void> changePassword(@Valid @RequestBody PasswordChangeRequest req, HttpServletRequest request) {
        String auth = request.getHeader("Authorization");
        String token = (auth != null && auth.startsWith("Bearer ")) ? auth.substring(7) : null;
        authService.changePassword(UserContext.getUserId(), req, token);
        return ApiResponse.success();
    }

    /** 当前用户自助更新资料（昵称/邮箱/电话）PUT /api/v1/auth/profile */
    @PutMapping("/profile")
    @AuditLog(action = "UPDATE", resource = "USER", description = "更新个人资料", resourceId = "#result")
    public ApiResponse<Void> updateProfile(@Valid @RequestBody ProfileUpdateRequest req) {
        authService.updateProfile(UserContext.getUserId(), req);
        return ApiResponse.success();
    }
}
