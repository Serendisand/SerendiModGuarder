package com.srendi.guarder.client;

import com.srendi.guarder.SerendiModGuarderMod;
import com.srendi.guarder.network.PlayModListPayload;
import com.srendi.guarder.util.ModListHasher;
import io.netty.channel.ChannelFutureListener;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientLoginNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.FriendlyByteBufs;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientHandshakePacketListenerImpl;
import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public class SerendiModGuarderClient implements ClientModInitializer {

    // static：客户端只有一个实例，但保持与主端一致的写法避免坑
    private static volatile List<String> allModIds = Collections.emptyList();

    private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "SerendiModGuarder-Client-Timer");
        t.setDaemon(true);
        return t;
    });

    @Override
    public void onInitializeClient() {
        List<String> mods = new ArrayList<>();
        for (ModContainer container : FabricLoader.getInstance().getAllMods()) {
            mods.add(container.getMetadata().getId());
        }
        Collections.sort(mods);
        allModIds = Collections.unmodifiableList(mods);

        // ⚠ 不要在客户端注册 PayloadTypeRegistry.playC2S()。
        // 主 mod 的 onInitialize() 已经注册过，客户端再注册会抛 Already registered。
        registerLoginModlistResponder();
        registerPlayModlistPush();
    }

    private void registerLoginModlistResponder() {
        ClientLoginNetworking.registerGlobalReceiver(
                SerendiModGuarderMod.MODLIST_CHANNEL,
                (Minecraft client,
                 ClientHandshakePacketListenerImpl handler,
                 FriendlyByteBuf buf,
                 Consumer<ChannelFutureListener> listener) -> {

                    long nonce = buf.readLong();

                    List<String> snapshot = allModIds;
                    List<String> sentModIds = snapshot.size() > SerendiModGuarderMod.MAX_MOD_COUNT
                            ? new ArrayList<>(snapshot.subList(0, SerendiModGuarderMod.MAX_MOD_COUNT))
                            : new ArrayList<>(snapshot);

                    FriendlyByteBuf response = FriendlyByteBufs.create();
                    response.writeVarInt(sentModIds.size());
                    for (String modId : sentModIds) {
                        response.writeUtf(modId, SerendiModGuarderMod.MAX_MOD_ID_LENGTH);
                    }
                    // 与主 mod 共享同一份哈希工具，确保两端一致
                    response.writeUtf(ModListHasher.compute(nonce, sentModIds));

                    return CompletableFuture.completedFuture(response);
                });
    }

    private void registerPlayModlistPush() {
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            TIMER.schedule(() -> {
                client.execute(() -> {
                    if (client.getConnection() == null) return;
                    try {
                        ClientPlayNetworking.send(new PlayModListPayload(allModIds));
                    } catch (Throwable t) {
                        SerendiModGuarderMod.logWarn("[SerendiModGuarder] PLAY 阶段推送失败: {}", t.getMessage());
                    }
                });
            }, 2, TimeUnit.SECONDS);
        });
    }
}