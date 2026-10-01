package com.nextup.tiers.mixin;

import com.nextup.tiers.TierManager;
import net.minecraft.client.gui.hud.PlayerListHud;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Coloca o tier na frente do nick na lista do TAB. */
@Mixin(PlayerListHud.class)
public abstract class PlayerListHudMixin {
    @Inject(method = "getPlayerName", at = @At("RETURN"), cancellable = true)
    private void tiers$addPrefix(PlayerListEntry entry, CallbackInfoReturnable<Text> cir) {
        Text prefix = TierManager.prefixFor(entry.getProfile().name());
        if (prefix != null) {
            cir.setReturnValue(Text.empty().append(prefix).append(cir.getReturnValue()));
        }
    }
}
