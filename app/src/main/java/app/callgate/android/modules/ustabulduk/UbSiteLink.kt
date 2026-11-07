package app.callgate.android.modules.ustabulduk

import app.callgate.android.modules.webhooks.WebhooksSettings
import app.callgate.android.modules.webhooks.db.WebHooksDao
import app.callgate.android.modules.webhooks.domain.WebHookEvent
import app.callgate.android.modules.webhooks.plugins.generateSignature
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * UstaBulduk sitesiyle doğrudan bağlantı.
 * Adres ve gizli anahtar, telefona kayıtlı web kancasından (https://site/callgate/olay?k=ANAHTAR) türetilir;
 * ayrıca ayar girmek gerekmez. İstekler web kancalarıyla aynı imza anahtarıyla (Settings › Webhooks › Signing Key) imzalanır.
 */
class UbSiteLink(
    private val dao: WebHooksDao,
    private val settings: WebhooksSettings,
) {
    /** ("https://site/callgate", "ANAHTAR") ya da kayıtlı UstaBulduk kancası yoksa null */
    fun target(): Pair<String, String>? {
        val url = WebHookEvent.entries.asSequence()
            .flatMap { dao.selectByEvent(it).asSequence() }
            .map { it.url }
            .firstOrNull { it.contains("/callgate/olay") } ?: return null
        val base = url.substringBefore("/olay")
        val key = Regex("[?&]k=([^&#]+)").find(url)?.groupValues?.get(1) ?: return null
        return base to key
    }

    /**
     * Gelen arama doğrulama araması mı? Site, bu numara için "arayarak doğrula" bekleyişi varsa kodu verir ve true döner.
     * Çağrı süzme 5 sn içinde yanıt vermek zorunda olduğu için zaman aşımları kısa tutulur; hata olursa arama normal çalar.
     */
    fun screen(phone: String): Boolean {
        val (base, key) = target() ?: return false
        val body = JSONObject().put("phoneNumber", phone).toString()
        val ts = (System.currentTimeMillis() / 1000).toString()
        val conn = (URL("$base/eslesme?k=$key").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 2000
            readTimeout = 2500
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("X-Timestamp", ts)
            setRequestProperty("X-Signature", generateSignature("HmacSHA256", settings.signingKey, body + ts))
        }
        try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode != 200) return false
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            return JSONObject(text).optBoolean("reject", false)
        } finally {
            conn.disconnect()
        }
    }

    /** Bekleyen komutlar (uzun yoklama: site en çok 25 sn bekletir) */
    fun pull(): List<JSONObject> {
        val (base, key) = target() ?: return emptyList()
        val conn = (URL("$base/komut?k=$key&w=25&src=app").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 40_000
        }
        try {
            if (conn.responseCode == 429) {
                Thread.sleep(60_000)
                return emptyList()
            }
            if (conn.responseCode != 200) return emptyList()
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            val arr: JSONArray = JSONObject(text).optJSONArray("cmds") ?: return emptyList()
            return (0 until arr.length()).map { arr.getJSONObject(it) }
        } finally {
            conn.disconnect()
        }
    }

    /** Komut sonucunu siteye bildir */
    fun result(id: Int, code: Int, message: String) {
        val (base, key) = target() ?: return
        val form = listOf(
            "id" to id.toString(),
            "code" to code.toString(),
            "body" to JSONObject().put("message", message).toString(),
            "rc" to "0",
        ).joinToString("&") { (k, v) -> k + "=" + URLEncoder.encode(v, "UTF-8") }
        val conn = (URL("$base/komut/sonuc?k=$key").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 10_000
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }
        try {
            conn.outputStream.use { it.write(form.toByteArray(Charsets.UTF_8)) }
            conn.responseCode
        } finally {
            conn.disconnect()
        }
    }
}
