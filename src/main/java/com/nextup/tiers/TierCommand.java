package com.nextup.tiers;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.MinecraftClient;
import net.minecraft.command.CommandSource;
import net.minecraft.text.Text;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

public final class TierCommand {
    private TierCommand() {}

    public static void register(CommandDispatcher<FabricClientCommandSource> d) {
        d.register(literal("tier")
                .then(literal("mode")
                        .executes(ctx -> {
                            msg(ctx, "Modalidade atual: " + TierManager.getMode()
                                    + "  |  Opcoes: " + String.join(", ", TierManager.MODES));
                            return 1;
                        })
                        .then(argument("modo", StringArgumentType.word())
                                .suggests((c, b) -> CommandSource.suggestMatching(TierManager.MODES, b))
                                .executes(ctx -> {
                                    String m = TierManager.normalize(StringArgumentType.getString(ctx, "modo"));
                                    if (!TierManager.MODES.contains(m)) {
                                        msg(ctx, "Modalidade invalida. Opcoes: " + String.join(", ", TierManager.MODES));
                                        return 0;
                                    }
                                    TierManager.setMode(m);
                                    msg(ctx, "Agora mostrando: " + m);
                                    return 1;
                                })))
                .then(literal("refresh").executes(ctx -> {
                    msg(ctx, "Atualizando...");
                    TierManager.refresh(s -> MinecraftClient.getInstance().execute(() -> msg(ctx, s)));
                    return 1;
                }))
                .then(literal("url")
                        .executes(ctx -> {
                            msg(ctx, "Link atual: " + TierManager.getUrl()
                                    + "  |  Trocar: /tier url <link>  |  Voltar ao padrao: /tier url reset");
                            return 1;
                        })
                        .then(argument("link", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    String link = StringArgumentType.getString(ctx, "link").trim();
                                    if (link.equalsIgnoreCase("reset")) link = TierManager.DEFAULT_URL;
                                    TierManager.setUrl(link);
                                    msg(ctx, "Link salvo: " + link + " - buscando os dados...");
                                    TierManager.refresh(s -> MinecraftClient.getInstance().execute(() -> msg(ctx, s)));
                                    return 1;
                                })))
                .then(literal("fuzzy")
                        .executes(ctx -> {
                            msg(ctx, "Nick parecido: " + (TierManager.isFuzzy() ? "LIGADO" : "DESLIGADO"));
                            return 1;
                        })
                        .then(literal("on").executes(ctx -> {
                            TierManager.setFuzzy(true);
                            msg(ctx, "Nick parecido LIGADO");
                            return 1;
                        }))
                        .then(literal("off").executes(ctx -> {
                            TierManager.setFuzzy(false);
                            msg(ctx, "Nick parecido DESLIGADO");
                            return 1;
                        })))
                .then(literal("debug").executes(ctx -> {
                    msg(ctx, TierManager.getStatus());
                    msg(ctx, "Detalhes salvos em: " + TierManager.getDebugPath());
                    return 1;
                }))
                .then(literal("status").executes(ctx -> {
                    msg(ctx, TierManager.getStatus() + " (modalidade: " + TierManager.getMode() + ")");
                    return 1;
                })));
    }

    private static void msg(CommandContext<FabricClientCommandSource> ctx, String s) {
        ctx.getSource().sendFeedback(Text.literal("[NextUp Tiers] " + s));
    }
}
