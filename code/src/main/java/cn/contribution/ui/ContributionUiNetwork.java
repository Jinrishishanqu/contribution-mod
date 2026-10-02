package cn.contribution.ui;

import com.google.gson.Gson;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

/** Sends already-queried account views to clients that opted into the richer UI. */
public final class ContributionUiNetwork {
    private static final Gson JSON = new Gson();
    private ContributionUiNetwork() { }

    public static boolean available(ServerPlayer player) {
        return ServerPlayNetworking.canSend(player, ContributionSnapshotPayload.TYPE);
    }

    public static void send(ServerPlayer player, Snapshot snapshot) {
        ServerPlayNetworking.send(player, new ContributionSnapshotPayload(JSON.toJson(snapshot)));
    }

    public record Action(String label, String command) { }
    public record Field(String key, String label, String value, int maxLength) { }
    public record Snapshot(String title, String view, List<String> headers, List<List<String>> rows,
                           List<Action> actions, String note, List<Field> fields, List<String> rowCommands) {
        public Snapshot(String title, String view, List<String> headers, List<List<String>> rows,
                        List<Action> actions, String note) {
            this(title, view, headers, rows, actions, note, List.of(), List.of());
        }
    }
}
