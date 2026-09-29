import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;

/** Exports the vanilla item-to-block relationship, including one item that places multiple block types. */
public final class RegistryPlacementExporter {
    private RegistryPlacementExporter() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            throw new IllegalArgumentException("Expected one output path");
        }

        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        Map<String, ArrayList<String>> mappings = new TreeMap<>();

        for (Item item : BuiltInRegistries.ITEM) {
            if (!(item instanceof BlockItem blockItem)) {
                continue;
            }

            Identifier itemId = BuiltInRegistries.ITEM.getKey(item);
            Set<String> blockIds = new TreeSet<>();
            blockIds.add(BuiltInRegistries.BLOCK.getKey(blockItem.getBlock()).toString());

            for (Class<?> type = item.getClass(); type != null && Item.class.isAssignableFrom(type); type = type.getSuperclass()) {
                for (Field field : type.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers()) || !Block.class.isAssignableFrom(field.getType())) {
                        continue;
                    }

                    try {
                        field.setAccessible(true);
                        Block block = (Block) field.get(item);
                        if (block != null) {
                            blockIds.add(BuiltInRegistries.BLOCK.getKey(block).toString());
                        }
                    } catch (ReflectiveOperationException exception) {
                        throw new IllegalStateException("Failed to read block field " + field, exception);
                    }
                }
            }

            mappings.put(itemId.toString(), new ArrayList<>(blockIds));
        }

        StringBuilder json = new StringBuilder();
        json.append("{\n  \"minecraft_version\": \"26.3\",\n  \"mappings\": {\n");
        int index = 0;
        for (Map.Entry<String, ArrayList<String>> entry : mappings.entrySet()) {
            entry.getValue().sort(String::compareTo);
            json.append("    \"").append(entry.getKey()).append("\": [");
            for (int blockIndex = 0; blockIndex < entry.getValue().size(); blockIndex++) {
                if (blockIndex > 0) {
                    json.append(", ");
                }
                json.append("\"").append(entry.getValue().get(blockIndex)).append("\"");
            }
            json.append("]");
            if (++index < mappings.size()) {
                json.append(',');
            }
            json.append('\n');
        }
        json.append("  }\n}\n");

        Path output = Path.of(args[0]);
        Files.createDirectories(output.getParent());
        Files.writeString(output, json, StandardCharsets.UTF_8);
        System.out.printf("Exported %d placement item mappings%n", mappings.size());
    }
}
