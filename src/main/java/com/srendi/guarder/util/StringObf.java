package com.srendi.guarder.util;

/**
 * 字符串混淆：编译期 XOR 加密 + 运行时解密。
 *
 * <p>目的：阻止 JD-GUI/CFR/Procyon 等反编译器直接看到明文常量
 * （主要是 {@code SHARED_SECRET} 这种硬编码密钥）。
 *
 * <p><b>局限</b>：XOR 只防静态分析，不防动态分析（jdb/attach API 可以 dump 内存）。
 * 且密钥本身也在同一个 jar 里，反编译后必然暴露。请配合 R8/ProGuard 混淆
 * 解密方法名使用，并注意 {@link ModListHasher} 中关于安全边界的说明。
 */
public final class StringObf {

    // 密钥 — 编译期随机生成；修改此数组不会影响已加密的字符串，
    // 必须用 {@link #encode(String)} 重新加密
    private static final byte[] K = {
            (byte) 0x5A, (byte) 0xA3, (byte) 0x17, (byte) 0xE2,
            (byte) 0x9C, (byte) 0x4B, (byte) 0x71, (byte) 0x8D,
            (byte) 0x36, (byte) 0xF1, (byte) 0x82, (byte) 0x0C
    };

    private StringObf() {}

    /** 加密原始字符串（编译期使用） */
    public static byte[] encode(String s) {
        byte[] data = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] out = new byte[data.length];
        for (int i = 0; i < data.length; i++) {
            out[i] = (byte) (data[i] ^ K[i % K.length]);
        }
        return out;
    }

    /** 解密加密串（运行时使用） */
    public static String decode(byte[] data) {
        byte[] out = new byte[data.length];
        for (int i = 0; i < data.length; i++) {
            out[i] = (byte) (data[i] ^ K[i % K.length]);
        }
        return new String(out, java.nio.charset.StandardCharsets.UTF_8);
    }
}