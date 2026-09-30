package com.aisys.common.core.jwt;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * RSA 密钥加载工具：从 PEM 文本（或 base64 DER）解析 PKCS8 私钥 / X509 公钥（DDD 8.2）。
 * <p>放在 common-core，使网关（reactive）与各业务服务（servlet）均可用，无需引入 servlet 栈。
 */
public final class JwtKeyUtil {

    private JwtKeyUtil() {}

    public static PrivateKey readPrivateKey(String pemOrBase64) {
        try {
            byte[] der = decodePem(pemOrBase64);
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (Exception e) {
            throw new IllegalStateException("读取 JWT 私钥失败", e);
        }
    }

    public static PublicKey readPublicKey(String pemOrBase64) {
        try {
            byte[] der = decodePem(pemOrBase64);
            return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
        } catch (Exception e) {
            throw new IllegalStateException("读取 JWT 公钥失败", e);
        }
    }

    private static byte[] decodePem(String content) {
        String s = content == null ? "" : content.trim();
        if (s.contains("BEGIN")) {
            StringBuilder b64 = new StringBuilder();
            for (String line : s.split("\\r?\\n")) {
                String t = line.trim();
                if (t.startsWith("-----")) continue;
                b64.append(t);
            }
            return Base64.getDecoder().decode(b64.toString().getBytes(StandardCharsets.UTF_8));
        }
        return Base64.getDecoder().decode(s.replaceAll("\\s+", "").getBytes(StandardCharsets.UTF_8));
    }
}
