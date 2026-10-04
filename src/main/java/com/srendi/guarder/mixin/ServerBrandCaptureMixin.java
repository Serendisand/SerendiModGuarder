package com.srendi.guarder.mixin;

import com.srendi.guarder.SerendiModGuarderMod;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.BrandPayload;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerBrandCaptureMixin {

    @Shadow
    public abstract ServerPlayer getPlayer();

    @Inject(method = "handleCustomPayload", at = @At("HEAD"), require = 0)
    private void serendimodguarder$captureBrand(ServerboundCustomPayloadPacket packet, CallbackInfo ci) {
        try {
            CustomPacketPayload payload = packet.payload();
            if (payload instanceof BrandPayload brandPayload) {
                String brand = brandPayload.brand();
                ServerPlayer player = getPlayer();
                if (player != null && brand != null) {
                    // ⚠ 现在调用的是 static 方法，指向唯一一份全局状态
                    SerendiModGuarderMod.onBrandCaptured(player, brand);
                }
            }
        } catch (Throwable t) {
            // 不再静默吞异常，避免 mixin 无声失效难以排查
            SerendiModGuarderMod.logWarn("[SerendiModGuarder] brand 捕获异常: {}", t.toString());
        }
    }
}