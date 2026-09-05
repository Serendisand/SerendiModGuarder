package com.srendi.guarder;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonArray;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * SerendiModGuarder 配置。
 * 文件: {@code config/serendimodguarder.json}
 */
public class SerendiModGuarderConfig {

    private static final Path CONFIG_PATH = FabricLoader.getInstance()
            .getConfigDir().resolve("serendimodguarder.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static SerendiModGuarderConfig instance;

    // ── fields ──
    private boolean enabled = true;
    private boolean debugMode = false;
    private int loginTimeoutSeconds = 10;

    private String kickMessage = "§c━━━━━━━━━━━━━━━━━━━━━━\n§c§l检测到违规 Mod §e%mod%\n§c━━━━━━━━━━━━━━━━━━━━━━\n\n§7被检测项目: §f%mod%\n§7原因: §f%reason%\n\n§e请移除该 Mod 后重新连接\n§7如认为误判，请联系管理员\n§7并提供完整 Mod 列表（mods/fabricloader.log）";
    private String mustInstallMessage = "§c━━━━━━━━━━━━━━━━━━━━━━\n§c§l未检测到 SerendiModGuarder\n§c━━━━━━━━━━━━━━━━━━━━━━\n\n§7本服务器要求安装 §eSerendiModGuarder §7才能进入\n§7请从服务器官网或管理员处获取Mod文件";
    private String timeoutMessage = "§c━━━━━━━━━━━━━━━━━━━━━━\n§c§l登录验证超时\n§c━━━━━━━━━━━━━━━━━━━━━━\n\n§7请确保：\n§7• 已正确安装 SerendiModGuarder\n§7• 客户端与服务器版本匹配\n§7• 网络连接正常";
    private String tamperMessage = "§c━━━━━━━━━━━━━━━━━━━━━━\n§c§lMod 列表签名验证失败\n§c━━━━━━━━━━━━━━━━━━━━━━\n\n§7可能原因：\n§7• SerendiModGuarder 与服务器版本不匹配\n§7• 安装了第三方修改版本\n§7• Mod 列表被外部工具篡改\n\n§e请重新下载官方版本 SerendiModGuarder\n§7并放入 mods 文件夹后重新连接";

    private List<String> forbiddenMods = new ArrayList<>(List.of(
            "meteor", "wurst", "aoba", "bthack", "catlean",
            "eternity", "minced", "pubdlc", "thunderhack",
            "liquidbounce", "augustus", "aristois", "future",
            "impact", "lambda", "novoline", "phobos",
            "rusherhack", "salhack", "seppuku", "sigtools",
            "weepcraft", "xray", "jello", "inertia",
            "bleach", "nightx", "youtube", "kami",
            "forgehax", "exeter", "crystallix"
    ));

    /** 禁用的客户端 brand 关键词（小写匹配），命中即踢 */
    private List<String> forbiddenBrands = new ArrayList<>(List.of(
            "cheatbreaker", "aristois", "impact-client", "meteor-client"
    ));

    /** 禁用的 Fabric 自定义 payload 频道名关键词（如 baritone:wurst 等），命中即踢 */
    private List<String> forbiddenChannels = new ArrayList<>();

    // ── singleton ──

    public static SerendiModGuarderConfig getInstance() {
        if (instance == null) {
            instance = new SerendiModGuarderConfig();
        }
        return instance;
    }

    // ── load / save ──

    public void load() {
        if (!Files.exists(CONFIG_PATH)) {
            save();
            return;
        }
        try {
            String json = Files.readString(CONFIG_PATH);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();

            enabled              = getBoolean(obj, "enabled", true);
            debugMode            = getBoolean(obj, "debugMode", false);
            loginTimeoutSeconds  = getInt(obj, "loginTimeoutSeconds", 10);
            kickMessage          = getString(obj, "kickMessage", kickMessage);
            mustInstallMessage   = getString(obj, "mustInstallMessage", mustInstallMessage);
            timeoutMessage       = getString(obj, "timeoutMessage", timeoutMessage);
            tamperMessage        = getString(obj, "tamperMessage", tamperMessage);
            forbiddenMods        = getStringList(obj, "forbiddenMods", forbiddenMods);
            forbiddenBrands      = getStringList(obj, "forbiddenBrands", forbiddenBrands);
            forbiddenChannels    = getStringList(obj, "forbiddenChannels", forbiddenChannels);

            SerendiModGuarderMod.logInfo("[SerendiModGuarder] 配置已加载. 违禁Mod: {}",
                    String.join(", ", forbiddenMods));
        } catch (Exception e) {
            SerendiModGuarderMod.logError("[SerendiModGuarder] 配置加载失败，使用默认配置: {}", e.getMessage());
        }
    }

    public void save() {
        try {
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("debugMode", debugMode);
            obj.addProperty("loginTimeoutSeconds", loginTimeoutSeconds);
            obj.addProperty("kickMessage", kickMessage);
            obj.addProperty("mustInstallMessage", mustInstallMessage);
            obj.addProperty("timeoutMessage", timeoutMessage);
            obj.addProperty("tamperMessage", tamperMessage);
            obj.add("forbiddenMods", toJsonArray(forbiddenMods));
            obj.add("forbiddenBrands", toJsonArray(forbiddenBrands));
            obj.add("forbiddenChannels", toJsonArray(forbiddenChannels));

            Files.createDirectories(CONFIG_PATH.getParent());
            Files.writeString(CONFIG_PATH, GSON.toJson(obj));

            SerendiModGuarderMod.logInfo("[SerendiModGuarder] 默认配置文件已生成: {}", CONFIG_PATH);
        } catch (IOException e) {
            SerendiModGuarderMod.logError("[SerendiModGuarder] 配置文件保存失败: {}", e.getMessage());
        }
    }

    // ── helpers ──

    private static String getString(JsonObject obj, String key, String def) {
        if (obj.has(key) && obj.get(key).isJsonPrimitive() && obj.get(key).getAsJsonPrimitive().isString()) {
            return obj.get(key).getAsString();
        }
        return def;
    }

    private static boolean getBoolean(JsonObject obj, String key, boolean def) {
        if (obj.has(key) && obj.get(key).isJsonPrimitive() && obj.get(key).getAsJsonPrimitive().isBoolean()) {
            return obj.get(key).getAsBoolean();
        }
        return def;
    }

    private static int getInt(JsonObject obj, String key, int def) {
        if (obj.has(key) && obj.get(key).isJsonPrimitive() && obj.get(key).getAsJsonPrimitive().isNumber()) {
            return obj.get(key).getAsInt();
        }
        return def;
    }

    private static List<String> getStringList(JsonObject obj, String key, List<String> def) {
        if (obj.has(key) && obj.get(key).isJsonArray()) {
            List<String> result = new ArrayList<>();
            for (var element : obj.getAsJsonArray(key)) {
                result.add(element.getAsString());
            }
            return result;
        }
        return def;
    }

    private static JsonArray toJsonArray(List<String> list) {
        JsonArray arr = new JsonArray();
        for (String s : list) {
            arr.add(s);
        }
        return arr;
    }

    // ── getters ──

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isDebugMode() {
        return debugMode;
    }

    public int getLoginTimeoutSeconds() {
        return loginTimeoutSeconds;
    }

    public String getKickMessage() {
        return kickMessage;
    }

    public String getMustInstallMessage() {
        return mustInstallMessage;
    }

    public String getTimeoutMessage() {
        return timeoutMessage;
    }

    public String getTamperMessage() {
        return tamperMessage;
    }

    public List<String> getForbiddenMods() {
        return forbiddenMods;
    }

    public List<String> getForbiddenBrands() {
        return forbiddenBrands;
    }

    public List<String> getForbiddenChannels() {
        return forbiddenChannels;
    }
}
