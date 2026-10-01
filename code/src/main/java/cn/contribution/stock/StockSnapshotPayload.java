package cn.contribution.stock;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** A bounded, client-optional view snapshot. Trading authority never lives in this packet. */
public record StockSnapshotPayload(String json) implements CustomPacketPayload {
    public static final Type<StockSnapshotPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("contribution", "stock_snapshot"));
    public static final StreamCodec<RegistryFriendlyByteBuf, StockSnapshotPayload> CODEC = StreamCodec.of(
            (buffer, payload) -> buffer.writeUtf(payload.json, 65535),
            buffer -> new StockSnapshotPayload(buffer.readUtf(65535)));
    @Override public Type<StockSnapshotPayload> type() { return TYPE; }
}
