package com.srendi.guarder;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.srendi.guarder.network.PlayModListPayload;
import com.srendi.guarder.util.ModListHasher;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class SerendiModGuarderMod implements ModInitializer {

    public static final String MOD_ID = "serendimodguarder";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    // 双线程：一条给登录超时，一条给其它延时任务，避免互相阻塞
    private static final ScheduledExecutorService TIMER = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "SerendiModGuarder-Timer");
        t.setDaemon(true);
        return t;
    });

    private static final ExecutorService LOG_EXECUTOR = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(4096),
            r -> {
                Thread thread = new Thread(r, "SerendiModGuarder-Log");
                thread.setDaemon(true);
                return thread;
            },
            new ThreadPoolExecutor.DiscardOldestPolicy());

    public static final Identifier MODLIST_CHANNEL = Identifier.fromNamespaceAndPath(MOD_ID, "modlist");
    public static final Identifier PLAY_MODLIST_CHANNEL = Identifier.fromNamespaceAndPath(MOD_ID, "play_mods");

    public static final int MAX_MOD_COUNT = 512;
    public static final int MAX_MOD_ID_LENGTH = 128;
    private static final int MAX_PLAY_CHECKS_PER_TICK = 16;
    private static final SecureRandom RANDOM = new SecureRandom();

    // ═══════════════════════════════════════════════════════════════════
    //  ⚠ 所有跨线程共享状态统一为 static。
    //  原因：Fabric Loader 通过反射 new 一个 ModInitializer 实例调用 onInitialize()，
    //  而 mixin 通过 `SerendiModGuarderMod.INSTANCE.xxx()` 访问时会指向另一个实例，
    //  导致 brand 写入的 map 和事件处理器读的 map 不是同一个对象。
    //  改成 static 后，无论 Fabric 实例化多少次，状态都只有一份。
    // ═══════════════════════════════════════════════════════════════════
    private static volatile MinecraftServer server;
    private static final Map<String, LoginCheck> PENDING_LOGINS = new ConcurrentHashMap<>();
    private static final Map<String, PlayerVerifyState> VERIFIED_PLAYERS = new ConcurrentHashMap<>();
    private static final Queue<ServerPlayer> PENDING_PLAY_CHECK = new ConcurrentLinkedQueue<>();
    private static final Map<String, DelayedVerifyTask> DELAYED_VERIFY_QUEUE = new ConcurrentHashMap<>();
    private static final AtomicInteger DELAYED_VERIFY_TICK = new AtomicInteger();

    @Override
    public void onInitialize() {
        SerendiModGuarderConfig.getInstance().load();

        // C2S play 通道只在 common 注册一次。客户端初始化器不要再注册，否则会
        // 抛 IllegalArgumentException: Already registered。
        PayloadTypeRegistry.serverboundPlay().register(PlayModListPayload.TYPE, PlayModListPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(PlayModListPayload.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            if (player == null) return;
            // Fabric API 的 PLAY 接收器已经在服务器线程，无需再 execute
            handlePlayModList(player, payload.mods());
        });

        registerLoginQuery();
        registerCommands();

        ServerLifecycleEvents.SERVER_STARTED.register(s -> server = s);
        ServerLifecycleEvents.SERVER_STOPPED.register(s -> {
            // ⚠ 先 complete 所有 future 再清空，否则 TIMER 里已经入队的 timeout 任务
            // 会因为 remove() 返回 null 而不 complete future，导致 future 泄漏。
            PENDING_LOGINS.values().forEach(c -> c.future.complete(null));
            PENDING_LOGINS.clear();
            VERIFIED_PLAYERS.clear();
            PENDING_PLAY_CHECK.clear();
            DELAYED_VERIFY_QUEUE.clear();
            DELAYED_VERIFY_TICK.set(0);
            server = null;
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, srv) -> {
            ServerPlayer player = handler.getPlayer();
            if (player == null) return;
            String name = player.getName().getString().toLowerCase(Locale.ROOT);

            PlayerVerifyState state = VERIFIED_PLAYERS.get(name);
            if (state == null || !state.isLoginVerified()) {
                logWarn("[SerendiModGuarder] ⚠ [JOIN] {} 缺少登录验证记录 — 放行让延迟验证兜底", name);
                state = state != null ? state : new PlayerVerifyState(name);
                VERIFIED_PLAYERS.put(name, state);
            }

            state.setJoinVerified(true);

            DELAYED_VERIFY_QUEUE.put(name, new DelayedVerifyTask(player, name,
                    System.currentTimeMillis() + 60_000, state.getLoginMods()));

            PENDING_PLAY_CHECK.add(player);
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, srv) -> {
            ServerPlayer player = handler.getPlayer();
            if (player != null) {
                String name = player.getName().getString().toLowerCase(Locale.ROOT);
                VERIFIED_PLAYERS.remove(name);
                DELAYED_VERIFY_QUEUE.remove(name);
                PENDING_PLAY_CHECK.remove(player);
            }
        });

        ServerTickEvents.START_SERVER_TICK.register(srv -> {
            ServerPlayer queued;
            int checks = 0;
            while (checks++ < MAX_PLAY_CHECKS_PER_TICK && (queued = PENDING_PLAY_CHECK.poll()) != null) {
                checkPlayerModsPlayPhase(queued);
            }

            if (DELAYED_VERIFY_TICK.incrementAndGet() >= 20) {
                DELAYED_VERIFY_TICK.set(0);
                long now = System.currentTimeMillis();
                for (var it = DELAYED_VERIFY_QUEUE.entrySet().iterator(); it.hasNext(); ) {
                    DelayedVerifyTask task = it.next().getValue();
                    if (now >= task.deadline) {
                        it.remove();
                        if (!task.player.hasDisconnected()) {
                            doDelayedVerify(task.player, task.name, task.loginMods);
                        }
                    }
                }
            }
        });

        logInfo("[SerendiModGuarder] ✅ 启动完成");
    }

    // ═══════════════════════════════════════════════════════════════════
    //  PLAY 阶段 mod 列表推送处理
    // ═══════════════════════════════════════════════════════════════════

    private void handlePlayModList(ServerPlayer player, List<String> mods) {
        if (player == null || player.hasDisconnected()) return;
        String name = player.getName().getString().toLowerCase(Locale.ROOT);

        PlayerVerifyState state = VERIFIED_PLAYERS.get(name);
        if (state != null) state.setPlayMods(mods);

        SerendiModGuarderConfig config = SerendiModGuarderConfig.getInstance();
        if (!config.isEnabled()) return;

        for (String modId : mods) {
            String hit = matchForbidden(modId, config.getForbiddenMods());
            if (hit != null) {
                logInfo("[SerendiModGuarder] ⛔ [PLAY推送] {} 违规Mod: {} (匹配规则: {})", name, modId, hit);
                kickForMod(player, hit);
                return;
            }
        }
    }

    /**
     * mod id 匹配逻辑：
     * - 完整相等：foo
     * - 前缀：foo-* / foo:* / foo_* / foo.*
     * - 后缀：*-foo / *:foo / *_foo / *.foo
     * - 中缀：*-foo-* 等
     * 不使用 contains(裸字符串)，避免 "minecraft" 命中 "minecraft:brand" 这种误判。
     */
    private static String matchForbidden(String id, List<String> forbiddenList) {
        if (id == null || forbiddenList == null) return null;
        String lower = id.toLowerCase(Locale.ROOT);
        for (String forbidden : forbiddenList) {
            if (forbidden == null || forbidden.isEmpty()) continue;
            String f = forbidden.toLowerCase(Locale.ROOT);
            if (lower.equals(f)) return forbidden;
            if (lower.startsWith(f + "-") || lower.startsWith(f + ":")
                    || lower.startsWith(f + "_") || lower.startsWith(f + ".")) return forbidden;
            if (lower.endsWith("-" + f) || lower.endsWith(":" + f)
                    || lower.endsWith("_" + f) || lower.endsWith("." + f)) return forbidden;
            if (lower.contains("-" + f + "-") || lower.contains(":" + f + ":")
                    || lower.contains("_" + f + "_") || lower.contains("." + f + ".")) return forbidden;
        }
        return null;
    }

    /**
     * 频道匹配 —— 只做精确匹配和命名空间匹配。
     *
     * <p>与 {@link #matchForbidden} 的区别：
     * <ul>
     *   <li>{@code matchForbidden} 会对 mod id 做前缀/后缀/中缀匹配，
     *       因为 mod id 里 {@code -} {@code _} {@code .} {@code :} 都是分隔符</li>
     *   <li>频道名是 {@code namespace:path} 结构，如果也用中缀匹配，
     *       违禁词 {@code xray} 会命中 {@code foo:xray-mode} 这种无关频道</li>
     * </ul>
     *
     * 因此频道匹配只判断：
     * <ul>
     *   <li>完整相等：{@code channel == forbidden}</li>
     *   <li>命名空间相等：{@code channel.split(":")[0] == forbidden}</li>
     * </ul>
     *
     * @param channel        完整频道名，如 {@code meteor-client:main}
     * @param forbiddenList  违禁词列表
     * @return 命中的违禁词，未命中返回 {@code null}
     */
    private static String matchForbiddenChannel(String channel, List<String> forbiddenList) {
        if (channel == null || forbiddenList == null) return null;
        String lower = channel.toLowerCase(Locale.ROOT);
        String namespace = lower.contains(":")
                ? lower.substring(0, lower.indexOf(':'))
                : lower;
        for (String forbidden : forbiddenList) {
            if (forbidden == null || forbidden.isEmpty()) continue;
            String f = forbidden.toLowerCase(Locale.ROOT);
            if (lower.equals(f) || namespace.equals(f)) return forbidden;
        }
        return null;
    }

    // ═══════════════════════════════════════════════════════════════════
    //  第 1 道防线：登录查询（QUERY_START 阶段）
    // ═══════════════════════════════════════════════════════════════════

    private void registerLoginQuery() {
        ServerLoginConnectionEvents.QUERY_START.register((handler, srv, sender, synchronizer) -> {
            String playerName = handler.getUserName().toLowerCase(Locale.ROOT);
            SerendiModGuarderConfig config = SerendiModGuarderConfig.getInstance();

            long nonce = RANDOM.nextLong();
            LoginCheck check = new LoginCheck(nonce);
            PENDING_LOGINS.put(playerName, check);

            // ⚠ 先注册接收器，再 waitFor，最后发包。顺序不能乱。
            ServerLoginNetworking.registerReceiver(handler, MODLIST_CHANNEL,
                    (server_, loginHandler, understood, buf, sync, responder) -> {

                String loginName = loginHandler.getUserName().toLowerCase(Locale.ROOT);
                LoginCheck existing = PENDING_LOGINS.remove(loginName);
                if (existing == null) return;
                if (!config.isEnabled()) { existing.future.complete(null); return; }

                if (!understood) {
                    logInfo("[SerendiModGuarder] ⛔ [登录] {} 未安装 SerendiModGuarder", playerName);
                    try {
                        loginHandler.disconnect(Component.literal(config.getMustInstallMessage()));
                    } catch (Throwable ignored) {}
                    existing.future.complete(null);
                    return;
                }

                try {
                    List<String> clientMods = readModList(buf);
                    String clientHash = buf.readUtf();
                    String expectedHash = ModListHasher.compute(existing.nonce, clientMods);

                    if (config.isDebugMode()) {
                        logInfo("[SerendiModGuarder] [调试] {} nonce={} mods={} 期望hash={} 收到hash={}",
                                playerName, existing.nonce, clientMods.size(), expectedHash, clientHash);
                        int preview = Math.min(20, clientMods.size());
                        logInfo("[SerendiModGuarder] [调试] {} 客户端Mod列表前{}个: {}",
                                playerName, preview, clientMods.subList(0, preview));
                    }

                    if (!expectedHash.equals(clientHash)) {
                        logWarn("[SerendiModGuarder] ⛔ [登录] {} SHA256 签名验证失败", playerName);
                        logWarn("[SerendiModGuarder] ⛔ [登录] {} 期望={}", playerName, expectedHash);
                        logWarn("[SerendiModGuarder] ⛔ [登录] {} 收到={}", playerName, clientHash);
                        logWarn("[SerendiModGuarder] ⛔ [登录] {} Mod数={} 前10个={}",
                                playerName, clientMods.size(),
                                clientMods.subList(0, Math.min(10, clientMods.size())));
                        try {
                            loginHandler.disconnect(Component.literal(config.getTamperMessage()));
                        } catch (Throwable ignored) {}
                        existing.future.complete(null);
                        return;
                    }

                    validateModList(clientMods, playerName, loginHandler, existing,
                            expectedHash, clientHash);
                } catch (Exception e) {
                    logWarn("[SerendiModGuarder] [登录] {} 解析失败: {}", playerName, e.getMessage());
                    try {
                        loginHandler.disconnect(Component.literal(config.getTamperMessage()));
                    } catch (Throwable ignored) {}
                    existing.future.complete(null);
                }
            });

            synchronizer.waitFor(check.future);

            FriendlyByteBuf queryBuf = FriendlyByteBufs.create();
            queryBuf.writeLong(nonce);
            sender.sendPacket(MODLIST_CHANNEL, queryBuf);

            long timeoutMs = TimeUnit.SECONDS.toMillis(config.getLoginTimeoutSeconds());
            TIMER.schedule(() -> {
                LoginCheck timedOut = PENDING_LOGINS.remove(playerName);
                if (timedOut != null && !timedOut.future.isDone()) {
                    logInfo("[SerendiModGuarder] ⛔ [登录] {} 查询超时", playerName);
                    srv.execute(() -> {
                        try {
                            handler.disconnect(Component.literal(config.getTimeoutMessage()));
                        } catch (Throwable ignored) {}
                    });
                    timedOut.future.complete(null);
                }
            }, timeoutMs, TimeUnit.MILLISECONDS);
        });

        logInfo("[SerendiModGuarder] ✅ 第1道防线：登录查询");
    }

    private void validateModList(List<String> clientMods, String playerName,
                                  ServerLoginPacketListenerImpl handler, LoginCheck check,
                                  String expectedHash, String clientHash) {
        SerendiModGuarderConfig config = SerendiModGuarderConfig.getInstance();

        for (String modId : clientMods) {
            String hit = matchForbidden(modId, config.getForbiddenMods());
            if (hit != null) {
                logInfo("[SerendiModGuarder] ⛔ [登录] {} 违规Mod: {} (匹配规则: {})", playerName, modId, hit);
                try {
                    handler.disconnect(Component.literal(config.getKickMessage()
                            .replace("%mod%", modId).replace("%player%", playerName)
                            .replace("%reason%", "使用违规Mod '" + modId + "'")));
                } catch (Throwable ignored) {}
                check.future.complete(null);
                return;
            }
        }

        PlayerVerifyState state = new PlayerVerifyState(playerName);
        state.setLoginVerified(true);
        state.setLoginNonce(check.nonce);
        state.setLoginMods(clientMods);
        state.setExpectedHash(expectedHash);
        state.setClientHash(clientHash);
        VERIFIED_PLAYERS.put(playerName, state);
        check.future.complete(null);
    }

    // ═══════════════════════════════════════════════════════════════════
    //  第 2 道防线：延迟交叉验证（JOIN 后 60 秒）
    // ═══════════════════════════════════════════════════════════════════

    private void doDelayedVerify(ServerPlayer player, String name, List<String> loginMods) {
        SerendiModGuarderConfig config = SerendiModGuarderConfig.getInstance();
        if (!config.isEnabled()) return;

        String key = name.toLowerCase(Locale.ROOT);
        PlayerVerifyState state = VERIFIED_PLAYERS.get(key);
        if (state == null) {
            logInfo("[SerendiModGuarder] ⛔ [延迟] {} 验证状态丢失", name);
            kickForMod(player, "__STATE_LOST__");
            return;
        }

        if (!state.isLoginVerified()) {
            logInfo("[SerendiModGuarder] ⚠ [延迟] {} 缺少登录验证记录 — 走频道扫描兜底", name);
        } else if (!state.isJoinVerified()) {
            logInfo("[SerendiModGuarder] ⛔ [延迟] {} 缺少 JOIN 验证记录", name);
            kickForMod(player, "__NO_JOIN_VERIFY__");
            return;
        }

        // ⚠ PLAY 阶段推送的 mod 列表必须存在且与登录阶段一致。
        // 如果攻击者拦截了 PLAY 推送（或伪造登录响应），这里会抓出来。
        if (state.isLoginVerified()) {
            List<String> playMods = state.getPlayMods();
            if (playMods == null || playMods.isEmpty()) {
                logWarn("[SerendiModGuarder] ⛔ [延迟] {} PLAY 阶段未收到 mod 列表 — 可能被拦截", name);
                kickForMod(player, "__NO_PLAY_PUSH__");
                return;
            }
            if (!modListsEqual(loginMods, playMods)) {
                logWarn("[SerendiModGuarder] ⛔ [延迟] {} LOGIN/PLAY mod 列表不一致", name);
                logWarn("[SerendiModGuarder] ⛔ [延迟] {} LOGIN 数={} PLAY 数={}",
                        name, loginMods != null ? loginMods.size() : 0, playMods.size());
                kickForMod(player, "__MOD_LIST_MISMATCH__");
                return;
            }
        }

        Set<Identifier> channels = getAllChannels(player);

        String brand = state.getBrand();
        if (brand != null) {
            logInfo("[SerendiModGuarder] [Brand] {} 客户端标识: {}", name, brand);
            String lowerBrand = brand.toLowerCase(Locale.ROOT);
            for (String forbidden : config.getForbiddenBrands()) {
                if (forbidden == null || forbidden.isEmpty()) continue;
                if (lowerBrand.equals(forbidden.toLowerCase(Locale.ROOT))) {
                    logInfo("[SerendiModGuarder] ⛔ [延迟] {} 禁用客户端 brand: {}", name, brand);
                    kickForMod(player, "禁用客户端: " + brand);
                    return;
                }
            }
        }

        if (channels != null) {
            for (Identifier ch : channels) {
                String chStr = ch.toString().toLowerCase(Locale.ROOT);

                // ⚠ 用频道专用匹配（精确 + 命名空间），不复用 mod id 的中缀匹配。
                // 避免 forbiddenMods 里的词误伤 foo:xray-mode 这种无关频道。
                String hit = matchForbiddenChannel(chStr, config.getForbiddenMods());
                if (hit != null) {
                    logInfo("[SerendiModGuarder] ⛔ [延迟] {} 频道违禁: {} (匹配规则: {})", name, ch, hit);
                    kickForMod(player, "频道 " + chStr + " 含违禁关键词 '" + hit + "'");
                    return;
                }
                String knownHit = matchForbiddenChannel(chStr, config.getForbiddenChannels());
                if (knownHit != null) {
                    logInfo("[SerendiModGuarder] ⛔ [延迟] {} 禁用频道: {}", name, ch);
                    kickForMod(player, "禁用频道: " + chStr);
                    return;
                }
            }
        }

        int loginModCount = loginMods != null ? loginMods.size() : 0;
        int channelCount = channels != null ? channels.size() : 0;
        if (loginModCount > 20 && channelCount < 3) {
            logWarn("[SerendiModGuarder] [延迟] {} Mod({})与频道({})不匹配", name, loginModCount, channelCount);
        }

        state.setDelayedVerified(true);
    }

    /**
     * 比较两份 mod 列表是否相同。
     *
     * <p>{@link PlayerVerifyState#setLoginMods} 和 {@link PlayerVerifyState#setPlayMods}
     * 都在存入前做了排序，所以这里直接 {@code equals} 即可，无需再次排序。
     * 用 {@code size} 快速短路，避免小差异也要构造 ArrayList。
     */
    private static boolean modListsEqual(List<String> a, List<String> b) {
        if (a == null) a = Collections.emptyList();
        if (b == null) b = Collections.emptyList();
        if (a.size() != b.size()) return false;
        return a.equals(b);
    }

    private Set<Identifier> getAllChannels(ServerPlayer player) {
        Set<Identifier> all = new HashSet<>();
        try {
            Set<Identifier> received = ServerPlayNetworking.getReceived(player);
            if (received != null) all.addAll(received);
        } catch (Throwable ignored) {}
        try {
            Set<Identifier> sendable = ServerPlayNetworking.getSendable(player);
            if (sendable != null) all.addAll(sendable);
        } catch (Throwable ignored) {}
        return all;
    }

    /**
     * Mixin 回调入口。必须 static，因为 Mixin 不能安全地持有 ModInitializer 实例。
     */
    public static void onBrandCaptured(ServerPlayer player, String brand) {
        if (player == null || brand == null) return;
        String name = player.getName().getString().toLowerCase(Locale.ROOT);
        PlayerVerifyState state = VERIFIED_PLAYERS.get(name);
        if (state == null) {
            state = new PlayerVerifyState(name);
            VERIFIED_PLAYERS.put(name, state);
        }
        state.setBrand(brand);

        // ⚠ 只在 debug 模式，或 brand 不是常见默认值时打日志。
        // 否则每个玩家连进来都会刷一行 "[Brand] xxx → vanilla"。
        if (SerendiModGuarderConfig.getInstance().isDebugMode()
                || !isCommonBrand(brand)) {
            logInfo("[SerendiModGuarder] [Brand] {} → {}", name, brand);
        }
    }

    /**
     * 判断是否是原版/常见 mod 加载器默认的 brand 值，用于过滤日志噪音。
     * 这些值对反作弊没有诊断价值。
     */
    private static boolean isCommonBrand(String brand) {
        String b = brand.toLowerCase(Locale.ROOT);
        return b.equals("vanilla")
                || b.equals("fabric")
                || b.equals("fabric-loader")
                || b.equals("neoforge")
                || b.equals("forge")
                || b.equals("quilt")
                || b.equals("lunarclient")
                || b.equals("lunar");
    }

    private void checkPlayerModsPlayPhase(ServerPlayer player) {
        if (player == null || player.hasDisconnected()) return;
        SerendiModGuarderConfig config = SerendiModGuarderConfig.getInstance();
        if (!config.isEnabled()) return;

        Set<Identifier> channels = getAllChannels(player);
        if (channels == null || channels.isEmpty()) return;

        for (Identifier ch : channels) {
            String lower = ch.toString().toLowerCase(Locale.ROOT);
            // ⚠ 同样用频道专用匹配
            String hit = matchForbiddenChannel(lower, config.getForbiddenChannels());
            if (hit != null) {
                kickForMod(player, "禁用频道: " + ch);
                return;
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  指令
    // ═══════════════════════════════════════════════════════════════════

private static boolean hasCommandPermission(CommandSourceStack src, int level) {
    try {
        // 26.2 原生权限检查：checkPermission(Identifier, 默认布尔值)
        // level >= 2 表示需要 OP 等级 2 以上的权限
        return src.checkPermission(
            net.minecraft.resources.Identifier.fromNamespaceAndPath(
                "serendimodguarder", "command"
            ),
            level >= 2
        );
    } catch (Throwable t) {
        logError("[SerendiModGuarder] 权限检查异常: {}", t.getMessage());
        return false;
    }
}

    private void registerCommands() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(Commands.literal("serendimodguarder")
                    .requires(src -> hasCommandPermission(src, 2))
                    .then(Commands.literal("reload")
                            .executes(context -> {
                                SerendiModGuarderConfig.getInstance().load();
                                context.getSource().sendSuccess(
                                        () -> Component.literal("§9[SerendiModGuarder] §a配置已重载！"), true);
                                logInfo("[SerendiModGuarder] 配置已重载");
                                return Command.SINGLE_SUCCESS;
                            }))
                    .then(Commands.literal("check")
                            .then(Commands.argument("player", StringArgumentType.word())
                                    .suggests((context, builder) -> {
                                        if (server != null) {
                                            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                                                builder.suggest(p.getName().getString());
                                            }
                                        }
                                        return builder.buildFuture();
                                    })
                                    .executes(context -> {
                                        CommandSourceStack source = context.getSource();
                                        String name = StringArgumentType.getString(context, "player");
                                        if (server == null) {
                                            source.sendFailure(Component.literal("§c服务器尚未就绪"));
                                            return 0;
                                        }
                                        ServerPlayer target = server.getPlayerList().getPlayerByName(name);
                                        if (target == null) {
                                            source.sendFailure(Component.literal("§c找不到在线玩家: " + name));
                                            return 0;
                                        }
                                        doCheckPlayer(target, source);
                                        return Command.SINGLE_SUCCESS;
                                    }))));
        });
    }

    private void doCheckPlayer(ServerPlayer target, CommandSourceStack source) {
        String name = target.getName().getString();
        String key = name.toLowerCase(Locale.ROOT);
        PlayerVerifyState state = VERIFIED_PLAYERS.get(key);

        source.sendSystemMessage(Component.literal("§9[SerendiModGuarder] §b" + name + " §7验证状态:"));
        if (state != null) {
            source.sendSystemMessage(Component.literal("  §7登录SHA256: " + (state.isLoginVerified() ? "§a✅" : "§c❌")));
            if (state.getExpectedHash() != null) {
                source.sendSystemMessage(Component.literal("    §7期望Hash: §f" + state.getExpectedHash()));
            }
            if (state.getClientHash() != null) {
                boolean ok = state.getExpectedHash() != null
                        && state.getExpectedHash().equals(state.getClientHash());
                source.sendSystemMessage(Component.literal("    §7客户端Hash: " + (ok ? "§a" : "§c") + state.getClientHash()));
            }
            if (state.hasNonce()) {
                source.sendSystemMessage(Component.literal("    §7Nonce: §f" + state.getLoginNonce()));
            }
            if (state.getLoginMods() != null && !state.getLoginMods().isEmpty()) {
                List<String> loginPreview = state.getLoginMods().subList(0,
                        Math.min(5, state.getLoginMods().size()));
                source.sendSystemMessage(Component.literal("    §7登录Mod数: §f" + state.getLoginMods().size()
                        + " §7前5: §f" + String.join(", ", loginPreview)));
            }
            source.sendSystemMessage(Component.literal("  §7JOIN一致性: " + (state.isJoinVerified() ? "§a✅" : "§c❌")));
            source.sendSystemMessage(Component.literal("  §7延迟验证: " + (state.isDelayedVerified() ? "§a✅" : "§c❌")));
            boolean hasPlay = state.getPlayMods() != null && !state.getPlayMods().isEmpty();
            source.sendSystemMessage(Component.literal("  §7PLAY推送: " + (hasPlay ? "§a✅ (" + state.getPlayMods().size() + "个)" : "§7(未收到)")));
            if (hasPlay) {
                List<String> playPreview = state.getPlayMods().subList(0,
                        Math.min(5, state.getPlayMods().size()));
                source.sendSystemMessage(Component.literal("    §7前5: §f" + String.join(", ", playPreview)));
            }
            String brand = state.getBrand();
            source.sendSystemMessage(Component.literal("  §7客户端brand: " + (brand != null ? "§b" + brand : "§c未捕获")));
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
                // ⚠ 与踢出判定保持一致，用频道专用匹配
                String hit = matchForbiddenChannel(lower, config.getForbiddenChannels());
                if (hit != null && !matchedCh.contains(hit)) matchedCh.add(hit);
            }
        }

        source.sendSystemMessage(Component.literal("§7频道 (" + channelList.size() + "):"));
        for (String ch : channelList) {
            boolean isMatched = matchedCh.stream().anyMatch(m -> ch.toLowerCase(Locale.ROOT).contains(m.toLowerCase(Locale.ROOT)));
            source.sendSystemMessage(Component.literal((isMatched ? "  §c⚠ " : "  §7- ") + ch));
        }
        if (channelList.isEmpty()) source.sendSystemMessage(Component.literal("§7  (无频道)"));
        source.sendSystemMessage(Component.literal(""));
        source.sendSystemMessage(Component.literal(matchedCh.isEmpty()
                ? "§a未检测到违禁频道"
                : "§c检测到违禁频道: §e" + String.join(", ", matchedCh)));
    }

    // ═══════════════════════════════════════════════════════════════════
    //  踢出
    // ═══════════════════════════════════════════════════════════════════

    private static boolean isInternalCode(String code) {
        return code != null && code.startsWith("__") && code.endsWith("__");
    }

    private void kickForMod(ServerPlayer player, String detectedMod) {
        if (player == null || player.hasDisconnected()) return;
        SerendiModGuarderConfig config = SerendiModGuarderConfig.getInstance();
        String reason;
        String msg;
        if ("__NO_MOD__".equals(detectedMod)) {
            reason = "未安装 SerendiModGuarder";
            msg = config.getMustInstallMessage();
        } else if ("__TAMPER__".equals(detectedMod)) {
            reason = "Mod 列表签名验证失败";
            msg = config.getTamperMessage();
        } else if ("__STATE_LOST__".equals(detectedMod)) {
            reason = "登录验证状态丢失";
            msg = config.getTamperMessage();
        } else if ("__NO_JOIN_VERIFY__".equals(detectedMod)) {
            reason = "缺少 JOIN 阶段验证";
            msg = config.getTamperMessage();
        } else if ("__NO_PLAY_PUSH__".equals(detectedMod)) {
            reason = "PLAY 阶段未收到 mod 列表（可能被拦截）";
            msg = config.getTamperMessage();
        } else if ("__MOD_LIST_MISMATCH__".equals(detectedMod)) {
            reason = "登录与 PLAY 阶段 mod 列表不一致";
            msg = config.getTamperMessage();
        } else if (isInternalCode(detectedMod)) {
            reason = "登录验证异常（代码: " + detectedMod + "）";
            msg = config.getTamperMessage();
        } else {
            reason = "使用违规Mod: " + detectedMod;
            msg = config.getKickMessage().replace("%mod%", detectedMod)
                    .replace("%player%", player.getName().getString()).replace("%reason%", reason);
        }
        logInfo("[SerendiModGuarder] ⛔ 踢出 {} (原因: {})", player.getName().getString(), reason);
        try {
            player.connection.disconnect(Component.literal(msg));
        } catch (Throwable ignored) {}
    }

    private static List<String> readModList(FriendlyByteBuf buf) {
        if (buf == null || !buf.isReadable()) return Collections.emptyList();
        int size = buf.readVarInt();
        if (size <= 0 || size > MAX_MOD_COUNT) return Collections.emptyList();
        List<String> mods = new ArrayList<>(size);
        for (int i = 0; i < size; i++) mods.add(buf.readUtf(MAX_MOD_ID_LENGTH));
        return mods;
    }

    public static void logInfo(String message, Object... args) {
        submitLog(() -> LOGGER.info(message, args));
    }
    public static void logWarn(String message, Object... args) {
        submitLog(() -> LOGGER.warn(message, args));
    }
    public static void logError(String message, Object... args) {
        submitLog(() -> LOGGER.error(message, args));
    }
    private static void submitLog(Runnable task) {
        if (LOG_EXECUTOR.isShutdown()) return;
        try {
            LOG_EXECUTOR.execute(task);
        } catch (RejectedExecutionException ignored) {}
    }

    private static class LoginCheck {
        final CompletableFuture<Void> future = new CompletableFuture<>();
        final long nonce;
        LoginCheck(long nonce) { this.nonce = nonce; }
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