package com.srendi.guarder.network;

import com.srendi.guarder.SerendiModGuarderMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 客户端 → 服务端 Mod 列表推送 Payload（PLAY 阶段）
 */
public record PlayModListPayload(List<String> mods) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<PlayModListPayload> TYPE =
            new CustomPacketPayload.Type<>(SerendiModGuarderMod.PLAY_MODLIST_CHANNEL);

    public static final StreamCodec<FriendlyByteBuf, PlayModListPayload> CODEC =
            StreamCodec.of(
                    PlayModListPayload::encode,
                    PlayModListPayload::decode
            );

    private static void encode(FriendlyByteBuf buf, PlayModListPayload payload) {
        buf.writeVarInt(payload.mods.size());
        for (String s : payload.mods) {
            buf.writeUtf(s, 32767);
        }
    }

    private static PlayModListPayload decode(FriendlyByteBuf buf) {
        int size = buf.readVarInt();
        if (size <= 0 || size > 5000) return new PlayModListPayload(Collections.emptyList());
        List<String> mods = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            mods.add(buf.readUtf(32767));
        }
        return new PlayModListPayload(mods);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
