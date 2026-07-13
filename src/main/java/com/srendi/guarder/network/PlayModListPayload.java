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
        int size = Math.min(payload.mods.size(), SerendiModGuarderMod.MAX_MOD_COUNT);
        buf.writeVarInt(size);
        for (int i = 0; i < size; i++) {
            buf.writeUtf(payload.mods.get(i), SerendiModGuarderMod.MAX_MOD_ID_LENGTH);
        }
    }

    private static PlayModListPayload decode(FriendlyByteBuf buf) {
        int size = buf.readVarInt();
        if (size <= 0 || size > SerendiModGuarderMod.MAX_MOD_COUNT) return new PlayModListPayload(Collections.emptyList());
        List<String> mods = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            mods.add(buf.readUtf(SerendiModGuarderMod.MAX_MOD_ID_LENGTH));
        }
        return new PlayModListPayload(mods);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
