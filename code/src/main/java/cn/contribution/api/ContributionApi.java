package cn.contribution.api;

import java.util.concurrent.CompletableFuture;

public abstract class ContributionApi {
    private static volatile ContributionApi instance;

    public static ContributionApi getInstance() {
        ContributionApi current = instance;
        if (current == null) {
            throw new IllegalStateException("Contribution server runtime is not ready");
        }
        return current;
    }

    public static void install(ContributionApi api) {
        instance = api;
    }

    public abstract CompletableFuture<BalanceChangeResult> changeBalance(BalanceChangeRequest request);
}
