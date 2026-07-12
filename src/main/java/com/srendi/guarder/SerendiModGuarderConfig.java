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

    private String kickMessage = "§c检测到你正在使用违规Mod: §e%mod%\n§c请移除后重新加入服务器！\n§7若您未使用作弊Mod，请确保安装了 §eSerendiModGuarder §7后再进入。";
    private String mustInstallMessage = "§c本服务器要求必须安装 §eSerendiModGuarder §c才能进入！\n§7请访问服务器官网或联系管理员获取 Mod 文件。";
    private String timeoutMessage = "§c登录验证超时！\n§7请确保安装了 §eSerendiModGuarder §7，然后重新加入。";

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
            forbiddenMods        = getStringList(obj, "forbiddenMods", forbiddenMods);

            SerendiModGuarderMod.LOGGER.info("[SerendiModGuarder] 配置已加载. 违禁Mod: {}",
                    String.join(", ", forbiddenMods));
        } catch (Exception e) {
            SerendiModGuarderMod.LOGGER.error("[SerendiModGuarder] 配置加载失败，使用默认配置: {}", e.getMessage());
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
            obj.add("forbiddenMods", toJsonArray(forbiddenMods));

            Files.createDirectories(CONFIG_PATH.getParent());
            Files.writeString(CONFIG_PATH, GSON.toJson(obj));

            SerendiModGuarderMod.LOGGER.info("[SerendiModGuarder] 默认配置文件已生成: {}", CONFIG_PATH);
        } catch (IOException e) {
            SerendiModGuarderMod.LOGGER.error("[SerendiModGuarder] 配置文件保存失败: {}", e.getMessage());
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

    public List<String> getForbiddenMods() {
        return forbiddenMods;
    }
}
