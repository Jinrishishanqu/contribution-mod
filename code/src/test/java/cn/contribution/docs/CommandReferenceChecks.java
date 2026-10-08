package cn.contribution.docs;

import cn.contribution.command.ContributionCommands;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.ArgumentCommandNode;
import com.mojang.brigadier.tree.CommandNode;

import net.minecraft.SharedConstants;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.flag.FeatureFlags;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/** Exports the actual Brigadier tree, not a second manually maintained syntax list. */
public final class CommandReferenceChecks {
    private record Route(String syntax, String argumentTypes) {}

    public static void main(String[] args) throws Exception {
        Path root = Path.of("..").toAbsolutePath().normalize();
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        var lookup =
                VanillaRegistries.createReloadableLookup(VanillaRegistries.createWorldLookup());
        var dispatcher = new CommandDispatcher<CommandSourceStack>();
        ContributionCommands.register(
                dispatcher, CommandBuildContext.simple(lookup, FeatureFlags.DEFAULT_FLAGS));
        List<Route> routes = new ArrayList<>();
        visit(dispatcher.getRoot(), "", "", routes);
        routes.sort(Comparator.comparing(Route::syntax));
        var gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
        String version =
                Files.readAllLines(root.resolve("code/gradle.properties")).stream()
                        .filter(line -> line.startsWith("mod_version="))
                        .findFirst()
                        .orElseThrow()
                        .split("=", 2)[1];
        String digest = fingerprint(root);
        if (args.length > 0 && args[0].equals("verify")) {
            Path manifestPath = root.resolve("documentation/manifest.json");
            if (!Files.exists(manifestPath))
                throw new AssertionError("Run tools/update-documentation.ps1 first");
            JsonObject manifest =
                    JsonParser.parseString(Files.readString(manifestPath)).getAsJsonObject();
            if (!manifest.get("sourceDigest").getAsString().equals(digest))
                throw new AssertionError(
                        "Documentation is stale: regenerate HTML and XLSX together");
            for (var entry : manifest.getAsJsonObject("files").entrySet()) {
                if (!sha(Files.readAllBytes(root.resolve("documentation").resolve(entry.getKey())))
                        .equals(entry.getValue().getAsString()))
                    throw new AssertionError("Artifact changed: " + entry.getKey());
            }
            JsonObject website = manifest.getAsJsonObject("websiteFiles");
            if (website == null
                    || !website.has("index.html")
                    || !website.has("assets/wiki.css")
                    || !website.has("assets/site.js")
                    || !website.has("assets/search-index.js")) {
                throw new AssertionError("Wiki website manifest is incomplete");
            }
            Path websiteRoot = root.resolve("wiki").normalize();
            for (var entry : website.entrySet()) {
                Path artifact = websiteRoot.resolve(entry.getKey()).normalize();
                if (!artifact.startsWith(websiteRoot)
                        || !sha(Files.readAllBytes(artifact))
                                .equals(entry.getValue().getAsString())) {
                    throw new AssertionError("Wiki website artifact changed: " + entry.getKey());
                }
            }
            if (manifest.get("commandCount").getAsInt() != routes.size())
                throw new AssertionError("Command count changed");
            System.out.println(
                    "DOCUMENTATION_PASS: "
                            + routes.size()
                            + " executable command routes, HTML/XLSX checksums");
            return;
        }
        JsonObject export = new JsonObject();
        export.addProperty("version", version);
        export.addProperty("sourceDigest", digest);
        export.add("commands", gson.toJsonTree(routes));
        Path target = root.resolve("code/build/documentation/commands.json");
        Files.createDirectories(target.getParent());
        Files.writeString(target, gson.toJson(export), StandardCharsets.UTF_8);
        System.out.println("COMMAND_EXPORT: " + routes.size() + " routes -> " + target);
    }

    private static void visit(
            CommandNode<CommandSourceStack> node, String path, String types, List<Route> routes) {
        if (node.getCommand() != null) routes.add(new Route("/" + path.trim(), types.trim()));
        for (var child : node.getChildren()) {
            String name = child.getName();
            String childTypes = types;
            if (child instanceof ArgumentCommandNode<?, ?> argument) {
                name = "<" + name + ">";
                childTypes += argument.getName() + ": " + argumentType(argument.getType()) + "; ";
            }
            visit(child, (path + " " + name).trim(), childTypes, routes);
        }
    }

    private static String argumentType(com.mojang.brigadier.arguments.ArgumentType<?> type) {
        if (type instanceof com.mojang.brigadier.arguments.StringArgumentType text) {
            return switch (text.getType()) {
                case SINGLE_WORD -> "单词（不能含空格）";
                case QUOTABLE_PHRASE -> "字符串（空格需引号）";
                case GREEDY_PHRASE -> "尾部文本（可含空格）";
            };
        }
        if (type instanceof com.mojang.brigadier.arguments.BoolArgumentType) return "true|false";
        if (type instanceof net.minecraft.commands.arguments.item.ItemArgument) return "原版物品[数据组件]";
        if (type instanceof net.minecraft.commands.arguments.EntityArgument) return "玩家选择器";
        if (type instanceof com.mojang.brigadier.arguments.IntegerArgumentType
                || type instanceof com.mojang.brigadier.arguments.LongArgumentType)
            return type.toString();
        return type.getClass().getSimpleName();
    }

    private static String fingerprint(Path root) throws Exception {
        List<Path> files = new ArrayList<>();
        for (String directory :
                List.of(
                        "code/src",
                        "design",
                        "documentation/source",
                        "code/tools",
                        "wiki/content",
                        "wiki/assets")) {
            try (var paths = Files.walk(root.resolve(directory))) {
                files.addAll(
                        paths.filter(Files::isRegularFile)
                                .filter(
                                        path -> {
                                            String relative =
                                                    root.relativize(path)
                                                            .toString()
                                                            .replace('\\', '/');
                                            return !relative.contains("/node_modules/")
                                                    && !relative.equals(
                                                            "wiki/assets/search-index.js")
                                                    && !relative.startsWith("design/items/")
                                                    && !relative.startsWith("design/definitions/");
                                        })
                                .toList());
            }
        }
        files.add(root.resolve("code/gradle.properties"));
        files.add(root.resolve("code/build.gradle"));
        files.add(root.resolve("code/run-gradle.ps1"));
        files.add(root.resolve("code/install-built-mod.ps1"));
        files.add(root.resolve("AGENTS.md"));
        files.add(root.resolve(".editorconfig"));
        files.add(root.resolve("README.md"));
        files.add(root.resolve(".gitattributes"));
        files.add(root.resolve("code/README.md"));
        files.add(root.resolve("wiki/README.md"));
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        files.sort(
                Comparator.comparing(path -> root.relativize(path).toString().replace('\\', '/')));
        for (Path file : files) {
            digest.update(
                    root.relativize(file)
                            .toString()
                            .replace('\\', '/')
                            .getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(Files.readAllBytes(file));
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
