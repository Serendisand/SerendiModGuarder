package com.srendi.guarder.client;

import com.srendi.guarder.SerendiModGuarderMod;
import com.srendi.guarder.network.PlayModListPayload;
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
import io.netty.channel.ChannelFutureListener;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public class SerendiModGuarderClient implements ClientModInitializer {

    private volatile List<String> allModIds = Collections.emptyList();

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
        allModIds = Collections.unmodifiableList(mods);

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

            FriendlyByteBuf response = FriendlyByteBufs.create();
            response.writeVarInt(allModIds.size());
            for (String modId : allModIds) response.writeUtf(modId);
            response.writeUtf(SerendiModGuarderMod.computeModListHash(nonce, allModIds));

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
                    } catch (Exception ignored) {
                    }
                });
            }, 2, TimeUnit.SECONDS);
        });
    }
}
