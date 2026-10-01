package com.nextup.tiers;

import com.google.gson.*;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.text.Text;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.text.Normalizer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Pattern;

public final class TierManager {

    /** Modalidades do site (nomes normalizados). */
    public static final List<String> MODES = List.of(
            "overall", "sword", "mace", "uhc", "smp", "cpvp",
            "cart", "axe", "nethpot", "diapot", "spearmace");

    // Nomes de campos que o parser tenta reconhecer no JSON do site.
    private static final String[] NICK_KEYS = {"nick", "nickname", "username", "minecraft_name",
            "minecraft_nick", "player_name", "playername", "player", "ign", "name"};
    private static final String[] MODE_KEYS = {"gamemode", "game_mode", "mode", "category",
            "modality", "modalidade", "kit"};
    private static final String[] TIER_KEYS = {"tier", "rank"};
    private static final String[] POINT_KEYS = {"points", "pts", "total_points", "overall_points",
            "score", "total"};

    private static final Pattern TIER_PATTERN = Pattern.compile("^(HT|LT)[1-5]$", Pattern.CASE_INSENSITIVE);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("nextuptiers.json");

    public static final String DEFAULT_URL = "https://tierlist-mercenariox.base44.app/";

    public static class Config {
        /** Link do site (https://...base44.app) OU link direto da API. */
        public String apiUrl = DEFAULT_URL;
        /** Enderecos de dados descobertos automaticamente a partir do site. */
        public List<String> discovered = new ArrayList<>();
        /** Opcional: alguns sites Base44 exigem uma api_key. */
        public String apiKey = "";
        public String mode = "overall";
        public int refreshMinutes = 5;
        /** Aceita nicks parecidos (ex: acento, underline, 1 letra diferente). */
        public boolean fuzzyMatch = true;
    }

    private static Config config = new Config();
    private static volatile Map<String, PlayerData> data = Map.of();
    private static final Map<String, Optional<PlayerData>> lookupCache = new ConcurrentHashMap<>();
    private static volatile String status = "Ainda nao carregou.";

    private TierManager() {}

    // ---------------------------------------------------------------- init
    public static void init() {
        loadConfig();
        ScheduledExecutorService ses = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "nextuptiers-refresh");
            t.setDaemon(true);
            return t;
        });
        int mins = Math.max(1, config.refreshMinutes);
        ses.scheduleAtFixedRate(() -> refresh(null), 0, mins, TimeUnit.MINUTES);
    }

    // ------------------------------------------------------------ config
    private static void loadConfig() {
        try {
            if (Files.exists(CONFIG_PATH)) {
                Config c = GSON.fromJson(Files.readString(CONFIG_PATH), Config.class);
                if (c != null) config = c;
            }
            if (config.apiUrl == null || config.apiUrl.startsWith("COLOQUE")) {
                config.apiUrl = new Config().apiUrl;
            }
            if (config.discovered == null) config.discovered = new ArrayList<>();
            config.mode = normalize(config.mode);
            if (!MODES.contains(config.mode)) config.mode = "overall";
            saveConfig();
        } catch (Exception e) {
            System.err.println("[NextUp Tiers] erro lendo config: " + e);
        }
    }

    private static void saveConfig() {
        try {
            Files.writeString(CONFIG_PATH, GSON.toJson(config));
        } catch (Exception e) {
            System.err.println("[NextUp Tiers] erro salvando config: " + e);
        }
    }

    public static String getMode() { return config.mode; }
    public static String getStatus() { return status; }
    public static int getLoadedCount() { return data.size(); }

    public static void setMode(String mode) {
        config.mode = mode;
        saveConfig();
    }

    public static boolean isFuzzy() { return config.fuzzyMatch; }

    public static void setFuzzy(boolean on) {
        config.fuzzyMatch = on;
        lookupCache.clear();
        saveConfig();
    }

    public static String getUrl() { return config.apiUrl; }

    public static void setUrl(String url) {
        config.apiUrl = url;
        config.discovered = new ArrayList<>(); // forca descobrir de novo
        saveConfig();
    }

    public static Path getDebugPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("nextuptiers-debug.txt");
    }

    // ----------------------------------------------------------- refresh
    private static final AtomicBoolean running = new AtomicBoolean(false);
    private static final List<String> debugLog = new ArrayList<>();
    private static final Pattern APP_ID_IN_URL = Pattern.compile("/api/apps/([0-9a-fA-F]{24})/");

    /** Busca o site em segundo plano (nao trava o jogo). */
    public static void refresh(Consumer<String> done) {
        if (!running.compareAndSet(false, true)) {
            if (done != null) done.accept("Ja esta atualizando, aguarde...");
            return;
        }
        CompletableFuture.runAsync(() -> {
            try {
                doRefresh();
            } catch (Exception e) {
                status = "Erro: " + e;
                dbg("EXCECAO: " + e);
            } finally {
                writeDebug();
                running.set(false);
                if (done != null) done.accept(status);
            }
        });
    }

    private static void doRefresh() throws Exception {
        synchronized (debugLog) { debugLog.clear(); }
        String url = config.apiUrl;
        dbg("URL configurada: " + url);
        if (url == null || !url.startsWith("http")) {
            status = "URL nao configurada. Use /tier url <link do site>";
            return;
        }

        List<String> endpoints;
        if (url.contains("/api/")) {
            endpoints = List.of(url);                 // link direto da API
        } else {
            if (config.discovered == null || config.discovered.isEmpty()) {
                status = "Procurando de onde o site tira os dados...";
                config.discovered = discover(url);
                saveConfig();
            }
            endpoints = config.discovered;
        }
        if (endpoints.isEmpty()) {
            status = "Nao consegui descobrir a API desse site. Rode /tier debug e me mande o arquivo.";
            return;
        }

        Map<String, PlayerData> all = new HashMap<>();
        int authErrors = 0;
        for (String ep : endpoints) {
            try {
                HttpResponse<String> r = http(ep);
                String body = r.body() == null ? "" : r.body();
                dbg("GET " + ep + " -> HTTP " + r.statusCode() + " | " + body.substring(0, Math.min(400, body.length())).replace('\n', ' '));
                if (r.statusCode() == 401 || r.statusCode() == 403) authErrors++;
                if (r.statusCode() == 200) mergeInto(all, parse(body));
            } catch (Exception e) {
                dbg("GET " + ep + " -> ERRO " + e);
            }
        }

        if (all.isEmpty()) {
            if (!url.contains("/api/")) config.discovered = new ArrayList<>(); // tenta descobrir de novo depois
            status = authErrors > 0
                    ? "O site pede login/chave pra liberar os dados (HTTP 401/403). Rode /tier debug."
                    : "Achei enderecos, mas nao consegui ler jogadores (formato diferente?). Rode /tier debug.";
        } else {
            data = all;
            lookupCache.clear();
            status = "OK: " + all.size() + " jogadores carregados.";
        }
    }

    /** Le a pagina do site e os arquivos .js dela, procurando o ID do app Base44 e os nomes das tabelas. */
    private static List<String> discover(String siteUrl) throws Exception {
        Pattern appIdP1 = Pattern.compile("/api/apps/([0-9a-fA-F]{24})");
        Pattern appIdP2 = Pattern.compile("app_?[iI]d[\"']?\\s*[:=]\\s*[\"']([0-9a-fA-F]{24})[\"']");
        Pattern serverP = Pattern.compile("serverUrl[\"']?\\s*[:=]\\s*[\"'](https?://[^\"']+)[\"']");
        Pattern entP1 = Pattern.compile("entities\\.([A-Za-z_$][\\w$]*)\\.(?:list|filter|get|schema)");
        Pattern entP2 = Pattern.compile("entities\\[[\"']([\\w-]+)[\"']\\]");
        Pattern entP3 = Pattern.compile("/entities/([A-Za-z][\\w-]*)");
        Pattern jsRef = Pattern.compile("[\"'(]((?:\\.{0,2}/)?(?:assets/)?[\\w\\-.]+\\.js)[\"')]");
        Pattern scriptTag = Pattern.compile("(?:src|href)=[\"']([^\"']+\\.js[^\"']*)[\"']");

        HttpResponse<String> page = http(siteUrl);
        dbg("Pagina do site -> HTTP " + page.statusCode());
        String html = page.body() == null ? "" : page.body();

        Deque<String> queue = new ArrayDeque<>();
        Set<String> seen = new HashSet<>();
        Matcher m = scriptTag.matcher(html);
        while (m.find()) {
            String u = URI.create(siteUrl).resolve(m.group(1)).toString();
            if (seen.add(u)) queue.add(u);
        }

        String appId = null, server = null;
        Set<String> entities = new LinkedHashSet<>();
        String text = html;
        int fetched = 0;
        while (true) {
            m = appIdP1.matcher(text);
            if (appId == null && m.find()) appId = m.group(1);
            m = appIdP2.matcher(text);
            if (appId == null && m.find()) appId = m.group(1);
            m = serverP.matcher(text);
            if (server == null && m.find()) server = m.group(1);
            for (Pattern ep : new Pattern[]{entP1, entP2, entP3}) {
                m = ep.matcher(text);
                while (m.find()) entities.add(m.group(1));
            }
            // arquivos js "preguicosos" citados dentro do js
            if (fetched > 0 && fetched < 25) {
                m = jsRef.matcher(text);
                while (m.find()) {
                    try {
                        String u = URI.create(currentJs).resolve(m.group(1)).toString();
                        if (u.startsWith("http") && seen.add(u)) queue.add(u);
                    } catch (Exception ignored) {}
                }
            }
            if (queue.isEmpty() || fetched >= 25 || (appId != null && !entities.isEmpty() && fetched >= 3)) break;
            currentJs = queue.poll();
            fetched++;
            try {
                HttpResponse<String> js = http(currentJs);
                text = js.statusCode() == 200 && js.body() != null ? js.body() : "";
                dbg("JS " + currentJs + " -> HTTP " + js.statusCode() + " (" + text.length() + " chars)");
            } catch (Exception e) {
                text = "";
                dbg("JS " + currentJs + " -> ERRO " + e);
            }
        }

        entities.remove("User");
        if (server == null) server = "https://app.base44.com";
        server = server.replaceAll("/+$", "");
        dbg("appId=" + appId + " | server=" + server + " | tabelas=" + entities);

        List<String> out = new ArrayList<>();
        if (appId != null) {
            for (String e : entities) {
                out.add(server + "/api/apps/" + appId + "/entities/" + e + "?limit=1000");
            }
        }
        return out;
    }

    private static String currentJs = "";

    private static HttpResponse<String> http(String url) throws Exception {
        HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("Accept", "application/json, text/html, */*")
                .header("User-Agent", "NextUpTiers/1.0");
        if (config.apiKey != null && !config.apiKey.isBlank()) rb.header("api_key", config.apiKey);
        Matcher m = APP_ID_IN_URL.matcher(url);
        if (m.find()) rb.header("X-App-Id", m.group(1));
        return HTTP.send(rb.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static void mergeInto(Map<String, PlayerData> into, Map<String, PlayerData> from) {
        for (Map.Entry<String, PlayerData> e : from.entrySet()) {
            PlayerData t = into.computeIfAbsent(e.getKey(), k -> new PlayerData());
            t.points = Math.max(t.points, e.getValue().points);
            t.tiers.putAll(e.getValue().tiers);
        }
    }

    private static void dbg(String line) {
        synchronized (debugLog) { debugLog.add(line); }
    }

    private static void writeDebug() {
        try {
            synchronized (debugLog) {
                Files.writeString(getDebugPath(), String.join("\n", debugLog) + "\n\nSTATUS: " + status + "\n");
            }
        } catch (Exception ignored) {}
    }

    // ------------------------------------------------------------ parser
    private static Map<String, PlayerData> parse(String body) {
        Map<String, PlayerData> out = new HashMap<>();
        JsonArray arr = findArray(JsonParser.parseString(body), 0);
        if (arr == null) return out;

        for (JsonElement el : arr) {
            if (!el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            String nick = str(o, NICK_KEYS);
            if (nick == null || nick.isBlank()) continue;

            PlayerData pd = out.computeIfAbsent(nick.toLowerCase(Locale.ROOT), k -> new PlayerData());

            Integer pts = num(o, POINT_KEYS);
            if (pts != null) pd.points = Math.max(pd.points, pts);

            // Formato "uma linha por (jogador, modalidade)"
            String mode = str(o, MODE_KEYS);
            String tier = str(o, TIER_KEYS);
            if (mode != null && tier != null && TIER_PATTERN.matcher(tier).matches()) {
                pd.tiers.put(normalize(mode), tier.toUpperCase(Locale.ROOT));
            }
            // Formato "uma linha por jogador, com campos por modalidade"
            collectTiers(o, pd, 0);
        }
        return out;
    }

    private static void collectTiers(JsonObject o, PlayerData pd, int depth) {
        for (Map.Entry<String, JsonElement> e : o.entrySet()) {
            String key = normalize(e.getKey());
            JsonElement v = e.getValue();
            if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isString()) {
                String s = v.getAsString().trim();
                if (TIER_PATTERN.matcher(s).matches() && !Arrays.asList(TIER_KEYS).contains(key)) {
                    pd.tiers.put(key, s.toUpperCase(Locale.ROOT));
                }
            } else if (v.isJsonObject() && depth < 2) {
                collectTiers(v.getAsJsonObject(), pd, depth + 1);
            }
        }
    }

    private static JsonArray findArray(JsonElement e, int depth) {
        if (e.isJsonArray()) return e.getAsJsonArray();
        if (e.isJsonObject() && depth < 3) {
            for (Map.Entry<String, JsonElement> en : e.getAsJsonObject().entrySet()) {
                if (en.getValue().isJsonArray()) return en.getValue().getAsJsonArray();
            }
            for (Map.Entry<String, JsonElement> en : e.getAsJsonObject().entrySet()) {
                JsonArray a = findArray(en.getValue(), depth + 1);
                if (a != null) return a;
            }
        }
        return null;
    }

    private static JsonElement get(JsonObject o, String... keys) {
        for (String k : keys) {
            String nk = normalize(k);
            for (Map.Entry<String, JsonElement> e : o.entrySet()) {
                if (normalize(e.getKey()).equals(nk) && !e.getValue().isJsonNull()) return e.getValue();
            }
        }
        return null;
    }

    private static String str(JsonObject o, String... keys) {
        JsonElement e = get(o, keys);
        return (e != null && e.isJsonPrimitive()) ? e.getAsString() : null;
    }

    private static Integer num(JsonObject o, String... keys) {
        JsonElement e = get(o, keys);
        if (e == null || !e.isJsonPrimitive()) return null;
        try { return (int) Math.round(e.getAsDouble()); } catch (Exception ex) { return null; }
    }

    /** "Neth Pot" -> "nethpot", "Spear_Mace" -> "spearmace" */
    public static String normalize(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    // ----------------------------------------------------------- display
    /** Texto que vai na frente do nick (ou null se o jogador nao tem tier). */
    public static Text prefixFor(String nick) {
        PlayerData pd = lookup(nick);
        if (pd == null) return null;

        if (config.mode.equals("overall")) {
            if (pd.points < 0) return null;
            return Text.literal("[" + pd.points + " pts] ").styled(s -> s.withColor(0xFACC15));
        }
        String tier = pd.tiers.get(config.mode);
        if (tier == null) return null;
        int color = colorFor(tier);
        return Text.literal("[" + tier + "] ").styled(s -> s.withColor(color));
    }

    // ------------------------------------------------- busca de jogador
    /** Acha o jogador: primeiro nick exato, depois (se ligado) nick bem parecido. */
    private static PlayerData lookup(String nick) {
        String key = nick.toLowerCase(Locale.ROOT);
        PlayerData exact = data.get(key);
        if (exact != null) return exact;
        if (!config.fuzzyMatch) return null;
        return lookupCache.computeIfAbsent(key, k -> Optional.ofNullable(fuzzyFind(k))).orElse(null);
    }

    /**
     * Regras (pra so aceitar quando e obvio):
     *  - ignora acento, maiusculas, "_", "-" e "."
     *  - nick com menos de 5 letras: so vale se ficar igual depois de ignorar esses sinais
     *  - 5 a 7 letras: ate 1 letra diferente; 8+ letras: ate 2 letras diferentes
     *  - os NUMEROS precisam ser iguais (the_m1 nao vira the_m2)
     *  - so vale se houver UM unico candidato mais proximo (empate = ignora)
     */
    private static PlayerData fuzzyFind(String key) {
        String q = simplify(key);
        if (q.length() < 3) return null;
        int max = q.length() < 5 ? 0 : (q.length() < 8 ? 1 : 2);
        String qDigits = q.replaceAll("[^0-9]", "");

        PlayerData best = null;
        int bestDist = Integer.MAX_VALUE, secondDist = Integer.MAX_VALUE;

        for (Map.Entry<String, PlayerData> e : data.entrySet()) {
            String c = simplify(e.getKey());
            if (Math.abs(c.length() - q.length()) > max) continue;
            if (!c.replaceAll("[^0-9]", "").equals(qDigits)) continue;
            int d = c.equals(q) ? 0 : (max == 0 ? Integer.MAX_VALUE : levenshtein(q, c));
            if (d > max) continue;
            if (d < bestDist) {
                secondDist = bestDist;
                bestDist = d;
                best = e.getValue();
            } else if (d < secondDist) {
                secondDist = d;
            }
        }
        return (best != null && bestDist < secondDist) ? best : null;
    }

    private static String simplify(String s) {
        String n = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return n.toLowerCase(Locale.ROOT).replaceAll("[_\\-.\\s]", "");
    }

    private static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1], cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev; prev = cur; cur = t;
        }
        return prev[b.length()];
    }

    private static int colorFor(String tier) {
        return switch (tier.charAt(2)) {
            case '1' -> 0xFACC15; // dourado
            case '2' -> 0xE5E7EB; // branco/prata
            case '3' -> 0xF97316; // laranja
            case '4' -> 0x94A3B8; // cinza
            default -> 0xFB7185;  // rosa (tier 5)
        };
    }
}
