package com.nextup.tiers;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;

public class TiersClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        TierManager.init();
        ClientCommandRegistrationCallback.EVENT.register(
                (dispatcher, registryAccess) -> TierCommand.register(dispatcher));
    }
}
