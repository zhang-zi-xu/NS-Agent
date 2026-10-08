package com.nongxin.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Expensive knowledge-base maintenance is disabled until an operator sets a separate token. */
@Component
public class KbAdminGuard {
    private final byte[] token;

    public KbAdminGuard(@Value("${nongxin.kb.admin-token:}") String token) {
        this.token = token == null ? new byte[0] : token.getBytes(StandardCharsets.UTF_8);
    }

    public void require(String supplied) {
        byte[] candidate =
                supplied == null ? new byte[0] : supplied.getBytes(StandardCharsets.UTF_8);
        if (token.length < 20 || !MessageDigest.isEqual(token, candidate)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "资料库维护接口未启用或无权访问");
        }
    }
}
