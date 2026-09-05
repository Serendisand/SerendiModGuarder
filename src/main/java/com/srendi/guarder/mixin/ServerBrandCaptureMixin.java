package com.srendi.guarder.mixin;

import com.srendi.guarder.SerendiModGuarderMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Method;

/**
 * 拦截 vanilla brand 协议 channel=minecraft:brand。
 * <p>
 * 该通道走 vanilla 自定义包协议，不在 Fabric {@code ServerPlayNetworking.getReceived}
 * 追踪范围内，必须从协议栈入口处直接捕获。
 * <p>
 * 用反射而非静态方法引用以兼容 1.20.6 ~ 26.2 之间的方法名差异
 * （{@code getChannel/channel} 与 {@code getIdentifier/identifier}）。
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerBrandCaptureMixin {

    @Shadow
    public abstract ServerPlayer getPlayer();

    @Inject(method = "handleCustomPayload", at = @At("HEAD"))
    private void serendimodguarder$captureBrand(Object packet, CallbackInfo ci) {
        try {
            Identifier channel = extractChannel(packet);
            if (channel == null || !"minecraft:brand".equals(channel.toString())) return;

            FriendlyByteBuf data = extractData(packet);
            if (data == null) return;

            String brand = data.readUtf();
            ServerPlayer player = getPlayer();
            if (player != null) {
                SerendiModGuarderMod.INSTANCE.onBrandCaptured(player, brand);
            }
        } catch (Throwable ignored) {
        }
    }

    private static Identifier extractChannel(Object packet) {
        for (String name : new String[]{"getChannel", "channel"}) {
            try {
                Method m = packet.getClass().getMethod(name);
                Object r = m.invoke(packet);
                if (r instanceof Identifier) return (Identifier) r;
            } catch (Throwable ignored) {
            }
        }
        for (String name : new String[]{"getIdentifier", "identifier"}) {
            try {
                Method m = packet.getClass().getMethod(name);
                Object r = m.invoke(packet);
                if (r instanceof Identifier) return (Identifier) r;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static FriendlyByteBuf extractData(Object packet) {
        for (String name : new String[]{"getData", "data", "getBuffer", "buffer"}) {
            try {
                Method m = packet.getClass().getMethod(name);
                Object r = m.invoke(packet);
                if (r instanceof FriendlyByteBuf) return (FriendlyByteBuf) r;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }
}
