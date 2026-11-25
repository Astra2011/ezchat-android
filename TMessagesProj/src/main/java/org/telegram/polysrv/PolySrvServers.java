package org.telegram.polysrv;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

import org.json.JSONArray;
import org.json.JSONObject;
import android.text.TextUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildConfig;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class PolySrvServers {

    private static final String PREFS_NAME = "polysrv_servers";
    private static final String KEY_CUSTOM_SERVERS = "custom_servers";
    private static final String KEY_ACCOUNT_SERVER = "account_server_";

    public static final String ID_TELEGRAM = "telegram";

    // Официальный Telegram (multi-DC, стоковый ключ).
    static final String TELEGRAM_RSA_KEY =
        "-----BEGIN RSA PUBLIC KEY-----\n" +
        "MIIBCgKCAQEAnE5VnML5MLp0/oVqvdoAC0OxNIz9idmJ+n2LAXbbvILO+OKYxFo3\n" +
        "aNoBhnW0QEekPYpUvDqRaHLL3yt92aWxUdeFhLmeabdFU85YSUmZ+Awz7BiKamIA\n" +
        "93m1w8tI9AttQ/Xv5dtHiQaVA8+zuAQOPZmxF6capvqP59r9NOTrvtLWChxSZIMl\n" +
        "l8zW5S+G5QZ/vMf1ox8LSitpUI/vWSYSmEDX2BBpi3s4v6hBS+9Jk5J30DS12NjT\n" +
        "QAf5wOvO+SCPeNiXm5W8JD8vCwZirtPHk+WP9DpMoCdnDJixFYlXd9RjA+3dMBOb\n" +
        "odXuj4QDR8YO4dM8IL73cLuLYmtw4Z24cwIDAQAB\n" +
        "-----END RSA PUBLIC KEY-----";
    static final long TELEGRAM_RSA_FINGERPRINT = 0xec924abfc719228bL;

    // --- Конфиг серверов (CDN) ---

    private static final String KEY_CONFIG_CACHE = "servers_config_json";
    private static final String KEY_CONFIG_TIME = "servers_config_time";
    private static final List<PolySrvServer> configServers = new ArrayList<>();
    private static boolean configLoaded;

    /**
     * Список серверов приходит не из кода, а из конфига: сначала берётся встроенный
     * assets/servers.json (офлайн-фолбэк), потом обновляется с CDN (адрес — в
     * servers.env -> BuildConfig.SERVERS_CONFIG_URL). Так адреса DC меняются без
     * пересборки приложения.
     */
    public static synchronized void loadConfig() {
        configLoaded = true;
    }

    /** Обновляет конфиг с CDN (в фоне). Пробует основной URL, затем запасной. */
    public static void refreshConfigFromNetwork(final Runnable onDone) {
        if (onDone != null) {
            AndroidUtilities.runOnUIThread(onDone);
        }
    }

    /**
     * Информация о последней сборке: с сайта (update.json).
     * Хранится в кэше, чтобы показать ссылку на APK даже офлайн.
     */
    public static final String KEY_UPDATE_CACHE = "servers_update_json";

    public static JSONObject getUpdateInfo() {
        return null;
    }

    /** Ссылка на APK последней сборки (если сервер её отдаёт). */
    public static String getLatestApkUrl() {
        return null;
    }

    /** true, если на сайте есть сборка новее установленной. */
    public static boolean hasNewerVersion() {
        return false;
    }

    /**
     * Периодически обновляет список серверов с сайта, пока приложение живо.
     * Список на экране обновляется сразу после очередного успешного запроса.
     */
    public static void startConfigPolling(final java.util.concurrent.atomic.AtomicLong lastUpdate) {
    }

    /** Скачивает update.json с сайта (в фоне) и кэширует. */
    public static void refreshUpdateInfo() {
    }

    /**
     * Достаёт JSON конфига из ответа: либо файл сам является JSON, либо это .env
     * с переменной SERVERS_JSON=... (значение может быть в кавычках).
     */
    private static String extractConfigJson(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        if (trimmed.startsWith("{")) {
            return trimmed;
        }
        for (String line : text.split("\\r?\\n")) {
            String l = line.trim();
            if (l.startsWith("SERVERS_JSON=")) {
                String value = l.substring("SERVERS_JSON=".length()).trim();
                if (value.length() > 1 && value.startsWith("\"") && value.endsWith("\"")) {
                    value = value.substring(1, value.length() - 1);
                }
                value = value.replace("\\\"", "\"");
                return value.trim();
            }
        }
        return null;
    }

    /**
     * Разбирает конфиг. Поддерживаются два формата:
     *  - обычный JSON (начинается с "{");
     *  - .env-файл, где список серверов лежит в переменной SERVERS_JSON.
     */
    private static synchronized boolean parseConfig(String text) {
        try {
            String json = extractConfigJson(text);
            if (json == null) {
                return false;
            }
            JSONObject root = new JSONObject(json);
            JSONArray servers = root.optJSONArray("servers");
            if (servers == null || servers.length() == 0) {
                return false;
            }
            List<PolySrvServer> parsed = new ArrayList<>();
            for (int i = 0; i < servers.length(); i++) {
                JSONObject o = servers.getJSONObject(i);
                PolySrvServer s = new PolySrvServer();
                s.id = o.optString("id", "server" + i);
                s.name = o.optString("name", s.id);
                s.description = o.optString("description", "");
                s.host = o.optString("host", "");
                s.port = o.optInt("port", 443);
                s.isTelegram = o.optBoolean("isTelegram", false);
                s.multiDc = o.optBoolean("multiDc", s.isTelegram);
                s.mainDcId = o.optInt("mainDcId", s.isTelegram ? 2 : 1);
                s.rsaPublicKey = o.optString("rsaPublicKey", "");
                s.rsaKeyFingerprint = o.optLong("rsaKeyFingerprint", 0);
                s.isOfficial = true;
                JSONArray dc = o.optJSONArray("dc");
                if (dc != null) {
                    for (int a = 0; a < dc.length(); a++) {
                        s.dc.add(PolySrvServer.Dc.fromJson(dc.getJSONObject(a)));
                    }
                }
                parsed.add(s);
            }
            configServers.clear();
            configServers.addAll(parsed);
            return true;
        } catch (Exception e) {
            FileLog.e(e);
            return false;
        }
    }

    /** Серверы из конфига (CDN/assets). */
    public static synchronized List<PolySrvServer> getConfigServers() {
        loadConfig();
        return new ArrayList<>(configServers);
    }

    // --- Built-in servers ---

    /** Официальный Telegram (multi-DC). Адреса DC и RSA-ключ — из конфига (CDN). */
    public static PolySrvServer telegramServer() {
        PolySrvServer s = new PolySrvServer();
        s.id                 = "ezchat";
        s.name               = "EzChat";
        s.description        = "EzChat messenger, connected to ezchat.im.";
        s.host               = "ezchat.im";
        s.port               = 2398;
        s.isOfficial         = true;
        s.isTelegram         = false;
        s.multiDc            = false;
        s.mainDcId           = 1;
        s.rsaPublicKey       = TELEGRAM_RSA_KEY;
        s.rsaKeyFingerprint  = TELEGRAM_RSA_FINGERPRINT;
        return s;
    }

    // --- Server list ---

    public static List<PolySrvServer> listServers() {
        List<PolySrvServer> result = new ArrayList<>();
        result.add(telegramServer());
        return result;
    }

    public static PolySrvServer getServerById(String id) {
        if (id == null) return null;
        for (PolySrvServer s : listServers()) {
            if (id.equals(s.id)) return s;
        }
        return null;
    }

    // --- Custom server persistence ---

    public static List<PolySrvServer> loadCustomServers() {
        List<PolySrvServer> result = new ArrayList<>();
        try {
            String json = getPrefs().getString(KEY_CUSTOM_SERVERS, "[]");
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                result.add(PolySrvServer.fromJson(array.getJSONObject(i)));
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return result;
    }

    private static void saveCustomServers(List<PolySrvServer> customs) {
        try {
            JSONArray array = new JSONArray();
            for (PolySrvServer s : customs) {
                array.put(s.toJson());
            }
            getPrefs().edit().putString(KEY_CUSTOM_SERVERS, array.toString()).apply();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    public static void addCustomServer(PolySrvServer server) {
        if (server.id == null || server.id.isEmpty()) {
            server.id = UUID.randomUUID().toString();
        }
        server.isOfficial = false;
        List<PolySrvServer> customs = loadCustomServers();
        customs.add(server);
        saveCustomServers(customs);
    }

    public static void updateCustomServer(PolySrvServer updated) {
        List<PolySrvServer> customs = loadCustomServers();
        for (int i = 0; i < customs.size(); i++) {
            if (updated.id.equals(customs.get(i).id)) {
                customs.set(i, updated);
                saveCustomServers(customs);
                return;
            }
        }
    }

    public static void removeCustomServer(String id) {
        List<PolySrvServer> customs = loadCustomServers();
        for (int i = 0; i < customs.size(); i++) {
            if (id.equals(customs.get(i).id)) {
                customs.remove(i);
                saveCustomServers(customs);
                return;
            }
        }
    }

    // --- Account - server binding ---

    public static String getServerIdForAccount(int accountNum) {
        return "ezchat";
    }

    public static void setServerForAccount(String serverId, int accountNum) {
    }

    public static PolySrvServer getServerForAccount(int accountNum) {
        return telegramServer();
    }

    public static boolean hasServerForAccount(int accountNum) {
        return true;
    }

    /**
     * Stable key for grouping per-server local device state (currently: recent/frequently-used
     * emoji, see Emoji.java) by backend rather than by device. Two different custom servers do
     * NOT share a document-id namespace — a custom-emoji document id cached while using one
     * never resolves on another (or on official Telegram), so state keyed only by device would
     * leak cross-server ids into every account's UI, permanently unresolvable there (see
     * AnimatedEmojiDrawable.EmojiDocumentFetcher — a not-found custom emoji has no negative
     * cache/timeout, it just stays broken forever). Official Telegram, and anything we can't
     * identify a server for, get "" (the original, unsuffixed storage keys) so existing
     * installs' state is preserved unchanged. Host, not server id, is the actual identity that
     * matters here: the same physical backend added twice under different local ids should
     * still share one scope.
     */
    public static String serverScopeKeyForAccount(int accountNum) {
        return "ezchat.im";
    }




    /**
     * True when two account slots target the same server. Used so the duplicate
     * login check (same phone / same user id) only triggers within one server —
     * the same phone number on a different server is a different account.
     * Matches by server id, or by resolved endpoint (host:port + Telegram flag)
     * so two profiles pointing at the same machine are still treated as one.
     */
    public static boolean sameAccountServer(int accountA, int accountB) {
        return true;
    }

    // --- DC application ---

    /**
     * Applies the selected server to the given account, atomically on the network
     * thread. Mirrors desktop ApplyServerToDcOptions + RestoreServerToAccount:
     *   - Telegram:       restore real Telegram DC 1-5, unlock, Telegram RSA key
     *   - Single-server:  map DC 1-5 all to one host:port, lock, server RSA key
     *
     * resetKeys=true clears auth keys (user picked a NEW server -> force re-handshake);
     * resetKeys=false keeps them (startup restore -> reuse the existing session).
     */
    public static void applyServerToAccount(PolySrvServer server, int accountNum) {
        applyServerToAccount(server, accountNum, true);
    }

    public static void applyServerToAccount(PolySrvServer server, int accountNum, boolean resetKeys) {
        PolySrvServer s = telegramServer();
        ConnectionsManager.native_applyServerConfig(
                accountNum,
                s.host,
                s.port,
                s.isTelegram,
                s.mainDcId,
                s.rsaPublicKey,
                s.rsaKeyFingerprint,
                resetKeys);
    }

    /**
     * Called from ApplicationLoader after each account's ConnectionsManager is
     * initialized, to restore the previously selected server config. Does NOT
     * reset auth keys so an already-authorized session keeps working.
     */
    public static void applyStoredServerToAccount(int accountNum) {
        applyServerToAccount(telegramServer(), accountNum, false);
    }

    // --- Helpers ---

    private static SharedPreferences getPrefs() {
        return ApplicationLoader.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** Returns true when at least one active account uses a Telegram-official server. */
    public static boolean anyAccountIsTelegram() {
        return false;
    }




    /**
     * Display name of the server an account is on, or null if unknown. Used for
     * the main app header so it shows the current server instead of "Telegram".
     */
    public static String serverNameForAccount(int accountNum) {
        return "EzChat";
    }

    // --- Account limits (mirrors desktop: premium cap is Telegram-only) ---

    /** First unused account slot (any server), or -1 if all slots are taken. */
    public static int firstFreeAccountSlot() {
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (!UserConfig.getInstance(a).isClientActivated()) {
                return a;
            }
        }
        return -1;
    }

    /** Number of activated accounts on a Telegram-official server. */
    public static int countTelegramAccounts() {
        return 0;
    }

    // Telegram-official account caps, mirroring the desktop client:
    //   kMaxAccounts = 3 (free), kPremiumMaxAccounts = 6 (with premium).
    private static final int TELEGRAM_FREE_LIMIT = 3;
    private static final int TELEGRAM_PREMIUM_LIMIT = 6;

    /**
     * Telegram-account cap (premium raises it from 3 to 6). Only Telegram accounts
     * count toward this; custom/self-hosted accounts are limited solely by the hard
     * total slot count (MAX_ACCOUNT_COUNT).
     */
    public static int telegramAccountLimit() {
        int limit = UserConfig.hasPremiumOnAccounts()
                ? TELEGRAM_PREMIUM_LIMIT
                : TELEGRAM_FREE_LIMIT;
        return Math.min(limit, UserConfig.MAX_ACCOUNT_COUNT);
    }

    /** True if another Telegram account can still be added under the premium cap. */
    public static boolean canAddTelegramAccount() {
        return true;
    }
}
