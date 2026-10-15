package com.rea.academy.controller;

import com.rea.academy.dto.MemberDto;
import com.rea.academy.service.MemberService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 인증 API (인증키 로그인).
 */
@RestController
@RequestMapping("/api/rea/auth")
@RequiredArgsConstructor
public class AuthController {

    private final MemberService memberService;

    @PostMapping("/key-login")
    public ResponseEntity<MemberDto.KeyLoginResponse> loginByKey(
            @RequestBody MemberDto.KeyLoginRequest req) {
        return ResponseEntity.ok(memberService.loginByKey(req.getAuthKey()));
    }
}
