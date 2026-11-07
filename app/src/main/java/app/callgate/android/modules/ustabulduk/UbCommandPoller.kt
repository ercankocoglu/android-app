package app.callgate.android.modules.ustabulduk

import android.util.Log
import app.callgate.android.modules.calls.CallsService
import org.json.JSONObject

/**
 * UstaBulduk: uygulama içi köprü. Siteden komut çeker (Termux betiğinin yerine geçer):
 *  hangup → çalan / süren aramayı kapat, dial → numarayı ara, ping → bağlantı denemesi.
 * Telefon bağlantıyı kendisi açtığı için mobil veride de çalışır; port yönlendirme gerekmez.
 */
class UbCommandPoller(
    private val link: UbSiteLink,
    private val calls: CallsService,
) {
    @Volatile
    private var worker: Thread? = null

    fun start() {
        if (worker?.isAlive == true) return
        worker = Thread({ loop() }, "ub-command-poller").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        worker?.interrupt()
        worker = null
    }

    private fun loop() {
        while (!Thread.currentThread().isInterrupted) {
            try {
                if (link.target() == null) {
                    Thread.sleep(30_000)
                    continue
                }
                link.pull().forEach { handle(it) }
                Thread.sleep(1_000)
            } catch (e: InterruptedException) {
                return
            } catch (e: Throwable) {
                Log.w(TAG, "Komut alınamadı", e)
                try {
                    Thread.sleep(5_000)
                } catch (ie: InterruptedException) {
                    return
                }
            }
        }
    }

    private fun handle(cmd: JSONObject) {
        val id = cmd.optInt("id")
        val op = cmd.optString("op")
        val phone = if (cmd.isNull("phone")) "" else cmd.optString("phone")
        var code: Int
        var message = ""
        try {
            code = when (op) {
                "hangup" -> if (calls.endCall()) 204 else 500.also { message = "Failed to end call" }
                "dial" -> {
                    calls.startCall(phone)
                    200
                }
                "ping" -> 200
                else -> 400
            }
        } catch (e: Throwable) {
            code = 500
            message = e.message ?: e.javaClass.simpleName
        }
        try {
            link.result(id, code, message)
        } catch (e: Throwable) {
            Log.w(TAG, "Sonuç bildirilemedi", e)
        }
    }

    companion object {
        private const val TAG = "UbCommandPoller"
    }
}
