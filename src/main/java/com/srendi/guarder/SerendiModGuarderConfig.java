package com.srendi.guarder;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    // ═══════════════════════════════════════════════════════════════
    //  出厂默认值 —— JSON 缺失字段时回退到这些
    //  顺序：消息文案 → 违禁列表
    // ═══════════════════════════════════════════════════════════════

    private static final String DEFAULT_KICK_MESSAGE =
            "§c§l✿ 检测到不允许的 Mod\n" +
            "§7\n" +
            "§7  Mod   §f%mod%\n" +
            "§7  原因  §f%reason%\n" +
            "§7\n" +
            "§7  这个真的不能带进来啦 orz\n" +
            "§7  卸掉之后重新连接试试～";

    private static final String DEFAULT_MUST_INSTALL_MESSAGE =
            "§c§l✿ 需要安装 SerendiModGuarder\n" +
            "§7\n" +
            "§7  本服务器要求安装此 Mod 才能进入\n" +
            "§7  请从服务器官网或管理员处获取安装包\n" +
            "§7\n" +
            "§7  装好之后，你就是自己人了（拍肩）";

    private static final String DEFAULT_TIMEOUT_MESSAGE =
            "§c§l✿ 登录验证超时了\n" +
            "§7\n" +
            "§7  检查一下：\n" +
            "§7  • SerendiModGuarder 是否装好\n" +
            "§7  • 客户端与服务端版本是否一致\n" +
            "§7  • 网络是不是有点抖\n" +
            "§7\n" +
            "§7  再试一次吧，这次一定行.jpg";

    private static final String DEFAULT_TAMPER_MESSAGE =
            "§c§l✿ Mod 列表签名验证失败\n" +
            "§7\n" +
            "§7  你的 Mod 列表和服务器记录的对不上\n" +
            "§7  可能装了被改过的版本，或者被外部工具动过\n" +
            "§7\n" +
            "§7  请去下官方版本，重新放好再连～\n" +
            "§7  （我们不接受魔改客户端，真的 orz）";

    private static final List<String> DEFAULT_FORBIDDEN_MODS = List.of(
            "meteor", "wurst", "aoba", "bthack", "catlean",
            "eternity", "minced", "pubdlc", "thunderhack",
            "liquidbounce", "augustus", "aristois", "future",
            "impact", "lambda", "novoline", "phobos",
            "rusherhack", "salhack", "seppuku", "sigtools",
            "weepcraft", "xray", "jello", "inertia",
            "bleach", "nightx", "youtube", "kami",
            "forgehax", "exeter", "crystallix"
    );

    private static final List<String> DEFAULT_FORBIDDEN_BRANDS = List.of(
            "cheatbreaker", "aristois", "impact-client", "meteor-client"
    );

    // ═══════════════════════════════════════════════════════════════
    //  Singleton
    // ═══════════════════════════════════════════════════════════════

    private static volatile SerendiModGuarderConfig instance;

    public static SerendiModGuarderConfig getInstance() {
        SerendiModGuarderConfig local = instance;
        if (local == null) {
            synchronized (SerendiModGuarderConfig.class) {
                local = instance;
                if (local == null) {
                    local = new SerendiModGuarderConfig();
                    instance = local;
                }
            }
        }
        return local;
    }

    // ═══════════════════════════════════════════════════════════════
    //  实例字段
    // ═══════════════════════════════════════════════════════════════

    private boolean enabled = true;
    private boolean debugMode = false;
    private int loginTimeoutSeconds = 10;

    private String kickMessage        = DEFAULT_KICK_MESSAGE;
    private String mustInstallMessage = DEFAULT_MUST_INSTALL_MESSAGE;
    private String timeoutMessage     = DEFAULT_TIMEOUT_MESSAGE;
    private String tamperMessage      = DEFAULT_TAMPER_MESSAGE;

    private List<String> forbiddenMods     = new ArrayList<>(DEFAULT_FORBIDDEN_MODS);
    private List<String> forbiddenBrands   = new ArrayList<>(DEFAULT_FORBIDDEN_BRANDS);
    private List<String> forbiddenChannels = new ArrayList<>();

    // ═══════════════════════════════════════════════════════════════
    //  load / save
    // ═══════════════════════════════════════════════════════════════

    public void load() {
        if (!Files.exists(CONFIG_PATH)) {
            save();
            return;
        }
        try {
            String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();

            // ⚠ 使用出厂默认值作为 fallback，而不是字段当前值。
            // 这样 /reload 时缺失字段会回退到出厂值而不是保留旧值。
            boolean enabledNew             = getBoolean(obj, "enabled", true);
            boolean debugNew               = getBoolean(obj, "debugMode", false);
            int timeoutNew                 = getInt(obj, "loginTimeoutSeconds", 10);
            String kickNew                 = getString(obj, "kickMessage", DEFAULT_KICK_MESSAGE);
            String mustInstallNew          = getString(obj, "mustInstallMessage", DEFAULT_MUST_INSTALL_MESSAGE);
            String timeoutMsgNew           = getString(obj, "timeoutMessage", DEFAULT_TIMEOUT_MESSAGE);
            String tamperNew               = getString(obj, "tamperMessage", DEFAULT_TAMPER_MESSAGE);
            List<String> forbiddenModsNew  = getStringList(obj, "forbiddenMods", DEFAULT_FORBIDDEN_MODS);
            List<String> brandsNew         = getStringList(obj, "forbiddenBrands", DEFAULT_FORBIDDEN_BRANDS);
            List<String> channelsNew       = getStringList(obj, "forbiddenChannels", List.of());

            // 一次性提交，避免半更新状态
            this.enabled             = enabledNew;
            this.debugMode           = debugNew;
            this.loginTimeoutSeconds = timeoutNew;
            this.kickMessage         = kickNew;
            this.mustInstallMessage  = mustInstallNew;
            this.timeoutMessage      = timeoutMsgNew;
            this.tamperMessage       = tamperNew;
            this.forbiddenMods       = new ArrayList<>(forbiddenModsNew);
            this.forbiddenBrands     = new ArrayList<>(brandsNew);
            this.forbiddenChannels   = new ArrayList<>(channelsNew);

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
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);

            SerendiModGuarderMod.logInfo("[SerendiModGuarder] 默认配置文件已生成: {}", CONFIG_PATH);
        } catch (IOException e) {
            SerendiModGuarderMod.logError("[SerendiModGuarder] 配置文件保存失败: {}", e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  JSON helpers
    // ═══════════════════════════════════════════════════════════════

    private static String getString(JsonObject obj, String key, String def) {
        try {
            JsonElement el = obj.get(key);
            if (el != null && el.isJsonPrimitive() && el.getAsJsonPrimitive().isString()) {
                return el.getAsString();
            }
        } catch (Exception ignored) {}
        return def;
    }

    private static boolean getBoolean(JsonObject obj, String key, boolean def) {
        try {
            JsonElement el = obj.get(key);
            if (el != null && el.isJsonPrimitive() && el.getAsJsonPrimitive().isBoolean()) {
                return el.getAsBoolean();
            }
        } catch (Exception ignored) {}
        return def;
    }

    private static int getInt(JsonObject obj, String key, int def) {
        try {
            JsonElement el = obj.get(key);
            if (el != null && el.isJsonPrimitive() && el.getAsJsonPrimitive().isNumber()) {
                return el.getAsInt();
            }
        } catch (Exception ignored) {}
        return def;
    }

    private static List<String> getStringList(JsonObject obj, String key, List<String> def) {
        try {
            JsonElement el = obj.get(key);
            if (el != null && el.isJsonArray()) {
                List<String> result = new ArrayList<>();
                for (JsonElement element : el.getAsJsonArray()) {
                    // ⚠ 严格校验元素是字符串，避免数字被 getAsString() 静默转换
                    if (element != null && element.isJsonPrimitive()
                            && element.getAsJsonPrimitive().isString()) {
                        result.add(element.getAsString());
                    }
                }
                return result;
            }
        } catch (Exception ignored) {}
        return def;
    }

    private static JsonArray toJsonArray(List<String> list) {
        JsonArray arr = new JsonArray();
        if (list != null) {
            for (String s : list) {
                arr.add(s == null ? "" : s);
            }
        }
        return arr;
    }

    // ═══════════════════════════════════════════════════════════════
    //  Getters
    // ═══════════════════════════════════════════════════════════════

    public boolean isEnabled() { return enabled; }
    public boolean isDebugMode() { return debugMode; }
    public int getLoginTimeoutSeconds() { return loginTimeoutSeconds; }
    public String getKickMessage() { return kickMessage; }
    public String getMustInstallMessage() { return mustInstallMessage; }
    public String getTimeoutMessage() { return timeoutMessage; }
    public String getTamperMessage() { return tamperMessage; }
    public List<String> getForbiddenMods() { return forbiddenMods; }
    public List<String> getForbiddenBrands() { return forbiddenBrands; }
    public List<String> getForbiddenChannels() { return forbiddenChannels; }
}