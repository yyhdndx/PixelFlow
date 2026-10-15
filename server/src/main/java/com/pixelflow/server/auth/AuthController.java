package com.pixelflow.server.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService auth;
    public AuthController(AuthService auth) { this.auth=auth; }
    public record Registration(@NotBlank @Pattern(regexp="[A-Za-z0-9_]{3,32}") String username,
            @NotBlank @Size(min=10,max=72) String password, @NotBlank @Size(max=40) String nickname) {}
    public record Login(@NotBlank @Size(max=32) String username, @NotBlank @Size(max=72) String password) {}
    public record Refresh(@NotBlank @Size(max=128) String refreshToken) {}
    @PostMapping("/register") @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public Map<String,String> register(@Valid @RequestBody Registration input) {
        return auth.register(input.username(),input.password(),input.nickname());
    }
    @PostMapping("/login") public AuthService.Tokens login(@Valid @RequestBody Login input) {
        return auth.login(input.username(),input.password());
    }
    @PostMapping("/refresh") public AuthService.Tokens refresh(@Valid @RequestBody Refresh input) {
        return auth.refresh(input.refreshToken());
    }
    @PostMapping("/logout") public Map<String,Boolean> logout(@AuthenticationPrincipal AuthService.Actor actor) {
        auth.logout(actor); return Map.of("success",true);
    }
}
