package com.srendi.guarder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class PlayerVerifyState {

    private final String playerName;

    private boolean loginVerified = false;
    private boolean hasNonce = false;
    private long loginNonce;

    private String expectedHash;
    private String clientHash;

    private List<String> loginMods = Collections.emptyList();
    private List<String> playMods = Collections.emptyList();

    private boolean joinVerified = false;
    private boolean delayedVerified = false;

    private volatile String brand;

    public PlayerVerifyState(String playerName) {
        this.playerName = playerName;
    }

    public String getPlayerName() { return playerName; }

    public boolean isLoginVerified() { return loginVerified; }
    public void setLoginVerified(boolean v) { this.loginVerified = v; }

    public boolean hasNonce() { return hasNonce; }
    public long getLoginNonce() { return loginNonce; }
    public void setLoginNonce(long v) { this.loginNonce = v; this.hasNonce = true; }

    public String getExpectedHash() { return expectedHash; }
    public void setExpectedHash(String v) { this.expectedHash = v; }

    public String getClientHash() { return clientHash; }
    public void setClientHash(String v) { this.clientHash = v; }

    public List<String> getLoginMods() { return loginMods; }

    // ⚠ 深拷贝 + 排序 + 不可变。
    // 排序后 modListsEqual 可以直接用 equals 比较，避免每次延迟验证都重新排序两份列表。
    // 深拷贝避免调用方后续修改入参污染 state 内部数据（Collections.unmodifiableList 只是视图）。
    public void setLoginMods(List<String> v) {
        if (v == null || v.isEmpty()) {
            this.loginMods = Collections.emptyList();
            return;
        }
        List<String> copy = new ArrayList<>(v);
        Collections.sort(copy);
        this.loginMods = Collections.unmodifiableList(copy);
    }

    public List<String> getPlayMods() { return playMods; }

    public void setPlayMods(List<String> v) {
        if (v == null || v.isEmpty()) {
            this.playMods = Collections.emptyList();
            return;
        }
        List<String> copy = new ArrayList<>(v);
        Collections.sort(copy);
        this.playMods = Collections.unmodifiableList(copy);
    }

    public boolean isJoinVerified() { return joinVerified; }
    public void setJoinVerified(boolean v) { this.joinVerified = v; }

    public boolean isDelayedVerified() { return delayedVerified; }
    public void setDelayedVerified(boolean v) { this.delayedVerified = v; }

    public String getBrand() { return brand; }
    public void setBrand(String v) { this.brand = v; }
}