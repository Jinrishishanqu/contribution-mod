package cn.contribution.shop;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record ShopSnapshotPayload(String json) implements CustomPacketPayload {
    public static final Type<ShopSnapshotPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("contribution", "shop_snapshot"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ShopSnapshotPayload> CODEC = StreamCodec.of(
            (buffer, payload) -> buffer.writeUtf(payload.json, 65535),
            buffer -> new ShopSnapshotPayload(buffer.readUtf(65535)));
    @Override public Type<ShopSnapshotPayload> type() { return TYPE; }
}
