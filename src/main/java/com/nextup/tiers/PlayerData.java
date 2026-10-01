package com.nextup.tiers;

import java.util.HashMap;
import java.util.Map;

/** Dados de um jogador vindos do site. */
public class PlayerData {
    /** Pontos gerais (Overall). -1 = site nao informou. */
    public int points = -1;
    /** modalidade normalizada (ex: "sword", "nethpot") -> tier (ex: "HT3") */
    public final Map<String, String> tiers = new HashMap<>();
}
