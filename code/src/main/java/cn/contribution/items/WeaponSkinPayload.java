package cn.contribution.items;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStackTemplate;

import java.util.List;

/** Server-created preview snapshots; confirmation still uses the guarded command session. */
public record WeaponSkinPayload(String token, String title, List<ItemStackTemplate> choices)
        implements CustomPacketPayload {
    public WeaponSkinPayload {
        choices = List.copyOf(choices);
        if (choices.isEmpty() || choices.size() > 128)
            throw new IllegalArgumentException("Invalid skin count");
    }

    public static final Type<WeaponSkinPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath("contribution", "weapon_skins"));
    public static final StreamCodec<RegistryFriendlyByteBuf, WeaponSkinPayload> CODEC =
            StreamCodec.of(
                    (buffer, payload) -> {
                        buffer.writeUtf(payload.token(), 64);
                        buffer.writeUtf(payload.title(), 128);
                        buffer.writeVarInt(payload.choices().size());
                        for (var item : payload.choices())
                            ItemStackTemplate.STREAM_CODEC.encode(buffer, item);
                    },
                    buffer -> {
                        String token = buffer.readUtf(64), title = buffer.readUtf(128);
                        int size = buffer.readVarInt();
                        if (size < 1 || size > 128)
                            throw new IllegalArgumentException("Invalid skin count");
                        var items = new java.util.ArrayList<ItemStackTemplate>(size);
                        for (int i = 0; i < size; i++)
                            items.add(ItemStackTemplate.STREAM_CODEC.decode(buffer));
                        return new WeaponSkinPayload(token, title, items);
                    });

    @Override
    public Type<WeaponSkinPayload> type() {
        return TYPE;
    }
}
