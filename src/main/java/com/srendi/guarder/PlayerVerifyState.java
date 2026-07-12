package com.srendi.guarder;

import java.util.Collections;
import java.util.List;

/**
 * 玩家多阶段验证状态记录。
 * 追踪每个玩家从登录 → JOIN → 游戏内各阶段的验证结果。
 */
public class PlayerVerifyState {

    private final String playerName;

    /** 第1阶段：登录 SHA256 验证通过 */
    private boolean loginVerified = false;

    /** 登录阶段使用的 nonce */
    private long loginNonce;

    /** 登录阶段上报的 Mod 列表快照（用于后续阶段交叉比对） */
    private List<String> loginMods = Collections.emptyList();

    /** PLAY 阶段客户端主动推送的 Mod 列表 */
    private List<String> playMods = Collections.emptyList();

    /** 第3阶段：JOIN 一致性验证通过 */
    private boolean joinVerified = false;

    /** 第4阶段：延迟验证通过 */
    private boolean delayedVerified = false;

    public PlayerVerifyState(String playerName) {
        this.playerName = playerName;
    }

    // ── getters / setters ──

    public String getPlayerName() {
        return playerName;
    }

    public boolean isLoginVerified() {
        return loginVerified;
    }

    public void setLoginVerified(boolean loginVerified) {
        this.loginVerified = loginVerified;
    }

    public long getLoginNonce() {
        return loginNonce;
    }

    public void setLoginNonce(long loginNonce) {
        this.loginNonce = loginNonce;
    }

    public List<String> getLoginMods() {
        return loginMods;
    }

    public void setLoginMods(List<String> loginMods) {
        this.loginMods = loginMods != null ? Collections.unmodifiableList(loginMods) : Collections.emptyList();
    }

    public List<String> getPlayMods() {
        return playMods;
    }

    public void setPlayMods(List<String> playMods) {
        this.playMods = playMods != null ? Collections.unmodifiableList(playMods) : Collections.emptyList();
    }

    public boolean isJoinVerified() {
        return joinVerified;
    }

    public void setJoinVerified(boolean joinVerified) {
        this.joinVerified = joinVerified;
    }

    public boolean isDelayedVerified() {
        return delayedVerified;
    }

    public void setDelayedVerified(boolean delayedVerified) {
        this.delayedVerified = delayedVerified;
    }
}
