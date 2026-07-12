package com.srendi.guarder;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.srendi.guarder.network.PlayModListPayload;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.*;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.PermissionLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class SerendiModGuarderMod implements ModInitializer {

    public static final String MOD_ID = "serendimodguarder";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    /** 定时调度线程池 — 避免在主线程 sleep */
    private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "SerendiModGuarder-Timer");
        t.setDaemon(true);
        return t;
    });

    /** 服务端 → 客户端 查询 Mod 列表的自定义频道（登录阶段） */
    public static final Identifier MODLIST_CHANNEL = Identifier.fromNamespaceAndPath(MOD_ID, "modlist");

    /** 客户端 → 服务端 推送 Mod 列表（PLAY 阶段） */
    public static final Identifier PLAY_MODLIST_CHANNEL = Identifier.fromNamespaceAndPath(MOD_ID, "play_mods");

    private static final String SHARED_SECRET = "SerendiGuard_2026_S3cr3t!@#";
    private static final String HASH_ALGORITHM = "SHA-256";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static MinecraftServer server;

    private final Map<String, LoginCheck> pendingLogins = new ConcurrentHashMap<>();
    private final Map<String, PlayerVerifyState> verifiedPlayers = new ConcurrentHashMap<>();
    private final Map<String, String> loginFlagged = new ConcurrentHashMap<>();
    private final List<ServerPlayer> pendingPlayCheck = new ArrayList<>();
    private final Map<String, DelayedVerifyTask> delayedVerifyQueue = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> playerBrands = new ConcurrentHashMap<>();

    @Override
    public void onInitialize() {
        SerendiModGuarderConfig.getInstance().load();

        // 注册 Payload 类型 + 接收器
        PayloadTypeRegistry.serverboundPlay().register(PlayModListPayload.TYPE, PlayModListPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(PlayModListPayload.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            if (player == null) return;
            String name = player.getName().getString();

            PlayerVerifyState state = verifiedPlayers.get(name);
            if (state != null) state.setPlayMods(payload.mods());

            SerendiModGuarderConfig config = SerendiModGuarderConfig.getInstance();
            if (!config.isEnabled()) return;

            for (String modId : payload.mods()) {
                String lower = modId.toLowerCase(Locale.ROOT);
                for (String forbidden : config.getForbiddenMods()) {
                    if (lower.contains(forbidden.toLowerCase(Locale.ROOT))) {
                        LOGGER.info("[SerendiModGuarder] ⛔ [PLAY推送] {} 违规Mod: {}", name, modId);
                        kickForMod(player, forbidden);
                        return;
                    }
                }
            }
        });

        registerLoginQuery();
        registerCommands();

        ServerLifecycleEvents.SERVER_STARTED.register(s -> server = s);

        ServerPlayConnectionEvents.JOIN.register((handler, sender, srv) -> {
            ServerPlayer player = handler.getPlayer();
            if (player == null) return;
            String name = player.getName().getString();

            String detected = loginFlagged.remove(name);
            if (detected != null) {
                if (isInternalCode(detected)) {
                    LOGGER.warn("[SerendiModGuarder] ⚠ [JOIN] {} 有 LOGIN 阶段违规标记({})，放行让延迟验证兜底", name, detected);
                } else {
                    kickForMod(player, detected);
                    return;
                }
            }

            PlayerVerifyState state = verifiedPlayers.remove(name);
            if (state == null || !state.isLoginVerified()) {
                LOGGER.warn("[SerendiModGuarder] ⚠ [JOIN] {} 没有登录验证记录，创建临时状态放行", name);
                state = state != null ? state : new PlayerVerifyState(name);
            }

            state.setJoinVerified(true);
            verifiedPlayers.put(name, state);

            delayedVerifyQueue.put(name, new DelayedVerifyTask(player, name,
                    System.currentTimeMillis() + 60_000, state.getLoginMods()));

            synchronized (pendingPlayCheck) {
                pendingPlayCheck.add(player);
            }
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, srv) -> {
            ServerPlayer player = handler.getPlayer();
            if (player != null) {
                String name = player.getName().getString();
                verifiedPlayers.remove(name);
                loginFlagged.remove(name);
                delayedVerifyQueue.remove(name);
            }
        });

        ServerTickEvents.START_SERVER_TICK.register(srv -> {
            List<ServerPlayer> toCheck;
            synchronized (pendingPlayCheck) {
                if (!pendingPlayCheck.isEmpty()) {
                    toCheck = new ArrayList<>(pendingPlayCheck);
                    pendingPlayCheck.clear();
                } else {
                    toCheck = null;
                }
            }
            if (toCheck != null) {
                for (ServerPlayer p : toCheck) checkPlayerModsPlayPhase(p);
            }

            long now = System.currentTimeMillis();
            for (var it = delayedVerifyQueue.entrySet().iterator(); it.hasNext(); ) {
                DelayedVerifyTask task = it.next().getValue();
                if (now >= task.deadline) {
                    it.remove();
                    if (!task.player.hasDisconnected()) {
                        doDelayedVerify(task.player, task.name, task.loginMods);
                    }
                }
            }
        });

        LOGGER.info("[SerendiModGuarder] ✅ 启动完成");
    }

    // ═══════════════════════════════════════════════════════════════════
    //  第1道防线：SHA256 签名登录查询
    // ═══════════════════════════════════════════════════════════════════

    private void registerLoginQuery() {
        ServerLoginConnectionEvents.QUERY_START.register((handler, srv, sender, synchronizer) -> {
            String playerName = handler.getUserName();
            SerendiModGuarderConfig config = SerendiModGuarderConfig.getInstance();

            long nonce = RANDOM.nextLong();
            LoginCheck check = new LoginCheck(playerName, nonce);
            pendingLogins.put(playerName, check);
            synchronizer.waitFor(check.future);

            ServerLoginNetworking.registerReceiver(handler, MODLIST_CHANNEL,
                    (server_, loginHandler, understood, buf, sync, responder) -> {

                LoginCheck existing = pendingLogins.remove(loginHandler.getUserName());
                if (existing == null) return;
                if (!config.isEnabled()) { existing.future.complete(null); return; }

                if (!understood) {
                    LOGGER.info("[SerendiModGuarder] ⛔ [登录] {} 未安装 SerendiModGuarder", playerName);
                    loginHandler.disconnect(Component.literal(config.getMustInstallMessage()));
                    loginFlagged.put(playerName, "__NO_MOD__");
                    existing.future.complete(null);
                    return;
                }

                try {
                    List<String> clientMods = readModList(buf);
                    String clientHash = buf.readUtf(64);
                    String expectedHash = computeModListHash(existing.nonce, clientMods);

                    if (!expectedHash.equals(clientHash)) {
                        LOGGER.warn("[SerendiModGuarder] ⛔ [登录] {} SHA256 签名验证失败！期望={} 收到={} nonce={}",
                                playerName, expectedHash, clientHash, existing.nonce);
                        loginHandler.disconnect(Component.literal(
                                "§c验证失败：Mod 列表签名不匹配。请确保使用正版 SerendiModGuarder！"));
                        loginFlagged.put(playerName, "__TAMPER__");
                        existing.future.complete(null);
                        return;
                    }

                    validateModList(clientMods, playerName, loginHandler, existing, nonce);
                } catch (Exception e) {
                    LOGGER.warn("[SerendiModGuarder] [登录] {} 解析失败: {}", playerName, e.getMessage());
                    existing.future.complete(null);
                }
            });

            FriendlyByteBuf queryBuf = FriendlyByteBufs.create();
            queryBuf.writeLong(nonce);
            sender.sendPacket(MODLIST_CHANNEL, queryBuf);

            long timeoutMs = TimeUnit.SECONDS.toMillis(config.getLoginTimeoutSeconds());
            TIMER.schedule(() -> {
                LoginCheck timedOut = pendingLogins.remove(playerName);
                if (timedOut != null && !timedOut.future.isDone()) {
                    LOGGER.info("[SerendiModGuarder] ⛔ [登录] {} 查询超时", playerName);
                    // 踢出操作必须在服务器主线程执行
                    srv.execute(() -> handler.disconnect(Component.literal(config.getTimeoutMessage())));
                    timedOut.future.complete(null);
                }
            }, timeoutMs, TimeUnit.MILLISECONDS);
        });

        LOGGER.info("[SerendiModGuarder] ✅ 第1道防线：登录查询");
    }

    public static String computeModListHash(long nonce, List<String> modIds) {
        try {
            List<String> sorted = new ArrayList<>(modIds);
            Collections.sort(sorted);
            String data = nonce + "|" + String.join(",", sorted) + "|" + SHARED_SECRET;
            MessageDigest digest = MessageDigest.getInstance(HASH_ALGORITHM);
            byte[] hash = digest.digest(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            LOGGER.error("[SerendiModGuarder] SHA256 计算失败", e);
            return "";
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Mod 列表验证
    // ═══════════════════════════════════════════════════════════════════

    private void validateModList(List<String> clientMods, String playerName,
                                  ServerLoginPacketListenerImpl handler, LoginCheck check, long nonce) {
        SerendiModGuarderConfig config = SerendiModGuarderConfig.getInstance();

        if (!clientMods.contains(MOD_ID)) {
            LOGGER.info("[SerendiModGuarder] ⛔ [登录] {} 未包含本 Mod", playerName);
            handler.disconnect(Component.literal(
                    config.getKickMessage().replace("%reason%", "未安装 SerendiModGuarder")));
            loginFlagged.put(playerName, "__NO_MOD__");
            check.future.complete(null);
            return;
        }

        for (String modId : clientMods) {
            String lower = modId.toLowerCase(Locale.ROOT);
            for (String forbidden : config.getForbiddenMods()) {
                if (lower.contains(forbidden.toLowerCase(Locale.ROOT))) {
                    LOGGER.info("[SerendiModGuarder] ⛔ [登录] {} 违规Mod: {}", playerName, modId);
                    handler.disconnect(Component.literal(config.getKickMessage()
                            .replace("%mod%", modId).replace("%player%", playerName)
                            .replace("%reason%", "使用违规Mod: " + modId)));
                    loginFlagged.put(playerName, modId);
                    check.future.complete(null);
                    return;
                }
            }
        }

        PlayerVerifyState state = new PlayerVerifyState(playerName);
        state.setLoginVerified(true);
        state.setLoginNonce(nonce);
        state.setLoginMods(clientMods);
        verifiedPlayers.put(playerName, state);
        check.future.complete(null);
    }

    // ═══════════════════════════════════════════════════════════════════
    //  延迟交叉验证（T+60s）
    // ═══════════════════════════════════════════════════════════════════

    private void doDelayedVerify(ServerPlayer player, String name, List<String> loginMods) {
        SerendiModGuarderConfig config = SerendiModGuarderConfig.getInstance();
        if (!config.isEnabled()) return;

        PlayerVerifyState state = verifiedPlayers.get(name);
        if (state == null) {
            LOGGER.info("[SerendiModGuarder] ⛔ [延迟] {} 验证状态丢失", name);
            kickForMod(player, "__STATE_LOST__");
            return;
        }

        if (!state.isLoginVerified()) {
            LOGGER.info("[SerendiModGuarder] ⚠ [延迟] {} 缺少登录验证记录 — 走频道扫描兜底", name);
        } else if (!state.isJoinVerified()) {
            LOGGER.info("[SerendiModGuarder] ⛔ [延迟] {} 缺少 JOIN 验证记录", name);
            kickForMod(player, "__NO_JOIN_VERIFY__");
            return;
        }

        Set<Identifier> channels = getAllChannels(player);

        boolean hasBrand = checkPlayerBrand(player, channels);
        playerBrands.put(name, hasBrand ? "detected" : "missing");

        if (channels != null && !channels.isEmpty() && !hasBrand) {
            LOGGER.warn("[SerendiModGuarder] [延迟] {} 有频道({}个)但无 brand", name, channels.size());
        }

        if (channels != null) {
            for (Identifier ch : channels) {
                String chStr = ch.toString().toLowerCase(Locale.ROOT);
                for (String forbidden : config.getForbiddenMods()) {
                    if (chStr.contains(forbidden.toLowerCase(Locale.ROOT))) {
                        LOGGER.info("[SerendiModGuarder] ⛔ [延迟] {} 频道违禁: {}", name, ch);
                        kickForMod(player, forbidden);
                        return;
                    }
                }
                if (chStr.contains("meteor") || chStr.contains("wurst") || chStr.contains("baritone")) {
                    LOGGER.info("[SerendiModGuarder] ⛔ [延迟] {} 已知危险频道: {}", name, ch);
                    kickForMod(player, "危险频道: " + chStr);
                    return;
                }
            }
        }

        int loginModCount = loginMods != null ? loginMods.size() : 0;
        int channelCount = channels != null ? channels.size() : 0;
        if (loginModCount > 20 && channelCount < 3) {
            LOGGER.warn("[SerendiModGuarder] [延迟] {} Mod({})与频道({})不匹配", name, loginModCount, channelCount);
        }

        state.setDelayedVerified(true);
    }

    private Set<Identifier> getAllChannels(ServerPlayer player) {
        Set<Identifier> all = new HashSet<>();
        try {
            Set<Identifier> received = ServerPlayNetworking.getReceived(player);
            if (received != null) all.addAll(received);
        } catch (Exception ignored) {}
        try {
            Set<Identifier> sendable = ServerPlayNetworking.getSendable(player);
            if (sendable != null) all.addAll(sendable);
        } catch (Exception ignored) {}
        return all;
    }

    private boolean checkPlayerBrand(ServerPlayer player, Set<Identifier> channels) {
        if (channels == null || channels.isEmpty()) return false;
        boolean hasBrand = channels.stream().anyMatch(ch -> ch.toString().equals("minecraft:brand"));
        if (!hasBrand) LOGGER.warn("[SerendiModGuarder] [Brand] {} 未注册 brand", player.getName().getString());
        return hasBrand;
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Tick 频道检测
    // ═══════════════════════════════════════════════════════════════════

    private void checkPlayerModsPlayPhase(ServerPlayer player) {
        if (player == null || player.hasDisconnected()) return;
        SerendiModGuarderConfig config = SerendiModGuarderConfig.getInstance();
        if (!config.isEnabled()) return;

        Set<Identifier> channels = getAllChannels(player);
        if (channels == null || channels.isEmpty()) return;

        for (Identifier ch : channels) {
            String lower = ch.toString().toLowerCase(Locale.ROOT);
            for (String forbidden : config.getForbiddenMods()) {
                if (lower.contains(forbidden.toLowerCase(Locale.ROOT))) {
                    kickForMod(player, forbidden);
                    return;
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  指令
    // ═══════════════════════════════════════════════════════════════════

    private void registerCommands() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(Commands.literal("serendimodguarder")
                    .requires(src -> src.permissions().hasPermission(
                            new Permission.HasCommandLevel(PermissionLevel.byId(2))))
                    .then(Commands.literal("reload")
                            .executes(context -> {
                                SerendiModGuarderConfig.getInstance().load();
                                context.getSource().sendSuccess(
                                        () -> Component.literal("§9[SerendiModGuarder] §a配置已重载！"), true);
                                LOGGER.info("[SerendiModGuarder] 配置已重载");
                                return Command.SINGLE_SUCCESS;
                            }))
                    .then(Commands.literal("check")
                            .then(Commands.argument("player", StringArgumentType.word())
                                    .executes(context -> {
                                        CommandSourceStack source = context.getSource();
                                        String name = StringArgumentType.getString(context, "player");
                                        if (server == null) { source.sendFailure(Component.literal("§c服务器尚未就绪")); return 0; }
                                        ServerPlayer target = server.getPlayerList().getPlayerByName(name);
                                        if (target == null) { source.sendFailure(Component.literal("§c找不到在线玩家: " + name)); return 0; }
                                        doCheckPlayer(target, source);
                                        return Command.SINGLE_SUCCESS;
                                    }))));
        });
    }

    private void doCheckPlayer(ServerPlayer target, CommandSourceStack source) {
        String name = target.getName().getString();
        PlayerVerifyState state = verifiedPlayers.get(name);

        source.sendSystemMessage(Component.literal("§9[SerendiModGuarder] §b" + name + " §7验证状态:"));
        if (state != null) {
            source.sendSystemMessage(Component.literal("  §7登录SHA256: " + (state.isLoginVerified() ? "§a✅" : "§c❌")));
            source.sendSystemMessage(Component.literal("  §7JOIN一致性: " + (state.isJoinVerified() ? "§a✅" : "§c❌")));
            source.sendSystemMessage(Component.literal("  §7延迟验证: " + (state.isDelayedVerified() ? "§a✅" : "§c❌")));
            boolean hasPlay = state.getPlayMods() != null && !state.getPlayMods().isEmpty();
            source.sendSystemMessage(Component.literal("  §7PLAY推送: " + (hasPlay ? "§a✅ (" + state.getPlayMods().size() + "个)" : "§7(未收到)")));
        } else {
            source.sendSystemMessage(Component.literal("  §c无验证记录"));
        }

        source.sendSystemMessage(Component.literal(""));

        Set<Identifier> channels = getAllChannels(target);

        List<String> channelList = new ArrayList<>();
        List<String> matchedCh = new ArrayList<>();
        SerendiModGuarderConfig config = SerendiModGuarderConfig.getInstance();

        if (channels != null) {
            for (Identifier id : channels) {
                String ch = id.toString();
                channelList.add(ch);
                String lower = ch.toLowerCase(Locale.ROOT);
                for (String forbidden : config.getForbiddenMods()) {
                    if (lower.contains(forbidden.toLowerCase(Locale.ROOT)) && !matchedCh.contains(forbidden)) matchedCh.add(forbidden);
                }
            }
        }

        source.sendSystemMessage(Component.literal("§7频道 (" + channelList.size() + "):"));
        for (String ch : channelList) {
            boolean isMatched = matchedCh.stream().anyMatch(m -> ch.toLowerCase(Locale.ROOT).contains(m.toLowerCase(Locale.ROOT)));
            source.sendSystemMessage(Component.literal((isMatched ? "  §c⚠ " : "  §7- ") + ch));
        }
        if (channelList.isEmpty()) source.sendSystemMessage(Component.literal("§7  (无频道)"));
        source.sendSystemMessage(Component.literal(""));
        source.sendSystemMessage(Component.literal(matchedCh.isEmpty() ? "§a未检测到违禁频道" : "§c检测到违禁频道: §e" + String.join(", ", matchedCh)));
    }

    // ═══════════════════════════════════════════════════════════════════
    //  踢出
    // ═══════════════════════════════════════════════════════════════════

    private static boolean isInternalCode(String code) {
        return code != null && code.startsWith("__") && code.endsWith("__");
    }

    private void kickForMod(ServerPlayer player, String detectedMod) {
        SerendiModGuarderConfig config = SerendiModGuarderConfig.getInstance();
        String reason = isInternalCode(detectedMod) ? "登录验证异常（状态码: " + detectedMod + "）" : "使用违规Mod: " + detectedMod;
        String kickMsg = config.getKickMessage().replace("%mod%", detectedMod)
                .replace("%player%", player.getName().getString()).replace("%reason%", reason);
        LOGGER.info("[SerendiModGuarder] ⛔ 踢出 {} (原因: {})", player.getName().getString(), detectedMod);
        player.connection.disconnect(Component.literal(kickMsg));
    }

    private List<String> readModList(FriendlyByteBuf buf) {
        if (buf == null || !buf.isReadable()) return Collections.emptyList();
        int size = buf.readVarInt();
        List<String> mods = new ArrayList<>(size);
        for (int i = 0; i < size; i++) mods.add(buf.readUtf(32767));
        return mods;
    }

    private static class LoginCheck {
        final CompletableFuture<Void> future = new CompletableFuture<>();
        final String playerName;
        final long nonce;
        LoginCheck(String playerName, long nonce) { this.playerName = playerName; this.nonce = nonce; }
    }

    private static class DelayedVerifyTask {
        final ServerPlayer player;
        final String name;
        final long deadline;
        final List<String> loginMods;
        DelayedVerifyTask(ServerPlayer player, String name, long deadline, List<String> loginMods) {
            this.player = player; this.name = name; this.deadline = deadline; this.loginMods = loginMods;
        }
    }

    public static MinecraftServer getServer() { return server; }
}
