package cn.contribution.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** A bounded server version greeting, independent of UI packet formats. */
public record VersionPayload(String modVersion, String gameVersion) implements CustomPacketPayload {
    public static final Type<VersionPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("contribution", "version"));
    public static final StreamCodec<RegistryFriendlyByteBuf, VersionPayload> CODEC = StreamCodec.of(
            (buffer, payload) -> { buffer.writeUtf(payload.modVersion(), 64); buffer.writeUtf(payload.gameVersion(), 64); },
            buffer -> new VersionPayload(buffer.readUtf(64), buffer.readUtf(64)));
    @Override public Type<VersionPayload> type() { return TYPE; }
}
