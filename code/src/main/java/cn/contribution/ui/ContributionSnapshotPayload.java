package cn.contribution.ui;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Optional client UI data; vanilla clients never receive this payload. */
public record ContributionSnapshotPayload(String json) implements CustomPacketPayload {
    public static final Type<ContributionSnapshotPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath("contribution", "contribution_snapshot"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ContributionSnapshotPayload> CODEC = StreamCodec.of(
            (buffer, payload) -> buffer.writeUtf(payload.json, 65535),
            buffer -> new ContributionSnapshotPayload(buffer.readUtf(65535)));

    @Override public Type<ContributionSnapshotPayload> type() { return TYPE; }
}
