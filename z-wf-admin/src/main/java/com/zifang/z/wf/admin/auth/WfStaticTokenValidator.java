package com.zifang.z.wf.admin.auth;

import java.io.UnsupportedEncodingException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * 服务令牌校验器（机器对机器调用用）。
 *
 * <p>令牌配置格式：{@code name:secret} 或裸 {@code secret}。
 * {@code name} 只进审计日志（归因到「哪个服务在调」），不进网络。
 *
 * <p><b>比较用 {@link MessageDigest#isEqual} 而不是 {@code equals}</b>：
 * {@code String.equals} 的短路比较在理论上会因响应时间差泄漏前缀长度；
 * 这类令牌是长期有效的共享密钥，不值得留这个口子。
 *
 * @author zifang
 */
public class WfStaticTokenValidator implements WfTokenValidator {

    private final List<String[]> entries = new ArrayList<>();

    public WfStaticTokenValidator(List<String> configured) {
        if (configured == null) {
            return;
        }
        int seq = 0;
        for (String raw : configured) {
            if (raw == null) {
                continue;
            }
            String item = raw.trim();
            if (item.isEmpty()) {
                continue;
            }
            seq++;
            int idx = item.indexOf(':');
            String name;
            String secret;
            if (idx > 0) {
                name = item.substring(0, idx).trim();
                secret = item.substring(idx + 1);
            } else {
                name = "token#" + seq;
                secret = item;
            }
            if (secret.isEmpty()) {
                continue;
            }
            entries.add(new String[]{name, secret});
        }
    }

    @Override
    public String source() {
        return WfPrincipal.SOURCE_STATIC_TOKEN;
    }

    @Override
    public WfPrincipal validate(String token) {
        if (token == null || token.isEmpty()) {
            return null;
        }
        byte[] candidate = utf8(token);
        for (String[] entry : entries) {
            if (MessageDigest.isEqual(utf8(entry[1]), candidate)) {
                return new WfPrincipal(entry[0], source(), null);
            }
        }
        return null;
    }

    /**
     * 令牌条数（启动日志用：让人一眼看出「到底配了几个凭据来源」）。
     */
    public int size() {
        return entries.size();
    }

    private static byte[] utf8(String s) {
        try {
            return s.getBytes("UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException("UTF-8 不可用", e);
        }
    }
}
