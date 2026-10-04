package com.srendi.guarder.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Mod 列表哈希工具 —— 客户端与服务端共用，保证两端计算结果完全一致。
 *
 * <p><b>⚠ 安全边界声明</b>
 * <p>本类用 SHA-256 对 {@code nonce + mods + SHARED_SECRET} 做摘要，只能防止
 * 传输过程中被第三方被动篡改。它<b>不能防止恶意客户端</b>：攻击者可以直接
 * 反编译本 jar 拿到 {@link #SHARED_SECRET}，或直接调用本类的 {@link #compute}
 * 方法生成合法 hash。这是所有"客户端上报型反作弊"的固有局限。
 * 如果需要真正的防护，必须引入远程认证（例如服务端下发的动态挑战 +
 * 硬件绑定），本类仅作为完整性检查使用。
 *
 * <p>关键实现点：
 * <ul>
 *   <li>输入列表统一排序（{@link Collections#sort}，字典序）</li>
 *   <li>使用 {@code b & 0xFF} 规避 {@code String.format("%02x", byte)} 对负数 byte 输出 8 位的问题</li>
 *   <li>{@code SHARED_SECRET} 为编译期常量，两端必须使用同一份编译产物</li>
 * </ul>
 */
public final class ModListHasher {

    private static final String HASH_ALGORITHM = "SHA-256";

    private static final byte[] SHARED_SECRET_ENC = {
            (byte) 0x09, (byte) 0xC6, (byte) 0x65, (byte) 0x87,
            (byte) 0xF2, (byte) 0x2F, (byte) 0x18, (byte) 0xCA,
            (byte) 0x43, (byte) 0x90, (byte) 0xF0, (byte) 0x68,
            (byte) 0x05, (byte) 0x91, (byte) 0x27, (byte) 0xD0,
            (byte) 0xAA, (byte) 0x14, (byte) 0x22, (byte) 0xBE,
            (byte) 0x55, (byte) 0x83, (byte) 0xB1, (byte) 0x78,
            (byte) 0x7B, (byte) 0xE3, (byte) 0x34
    };

    public static final String SHARED_SECRET = StringObf.decode(SHARED_SECRET_ENC);

    private ModListHasher() {}

    /**
     * 计算 Mod 列表签名。
     *
     * @param nonce   服务端下发的随机数
     * @param modIds  Mod ID 列表（内部会排序拷贝，不修改入参）
     * @return 64 位小写十六进制 SHA-256；出错时返回空字符串
     */
    public static String compute(long nonce, List<String> modIds) {
        try {
            List<String> sorted = new ArrayList<>(modIds == null ? Collections.emptyList() : modIds);
            Collections.sort(sorted);

            String data = nonce + "|" + String.join(",", sorted) + "|" + SHARED_SECRET;

            MessageDigest digest = MessageDigest.getInstance(HASH_ALGORITHM);
            byte[] hash = digest.digest(data.getBytes(StandardCharsets.UTF_8));

            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (Exception e) {
            return "";
        }
    }
}