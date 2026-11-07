package app.callgate.android.modules.ustabulduk

import android.os.Build
import android.telecom.Call
import android.telecom.CallScreeningService
import android.util.Log
import androidx.annotation.RequiresApi
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * UstaBulduk: çağrı süzme. Gelen arama siteye sorulur; numara "arayarak doğrula" bekliyorsa site kodu verir ve
 * arama ÇALMADAN reddedilir (arayana ücret yansımaz, telefon çalmaz). Diğer aramalar her zamanki gibi çalar.
 * Çalışması için uygulamaya Android'in "arama süzme" (call screening) rolü verilmelidir (ana ekranda ONLINE'a basınca sorulur).
 */
@RequiresApi(Build.VERSION_CODES.N)
class UbCallScreeningService : CallScreeningService(), KoinComponent {
    private val link: UbSiteLink by inject()

    override fun onScreenCall(callDetails: Call.Details) {
        val allow = CallResponse.Builder().build()

        val incoming = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                || callDetails.callDirection == Call.Details.DIRECTION_INCOMING
        val number = callDetails.handle?.schemeSpecificPart
        if (!incoming || number.isNullOrBlank()) {
            respondSafely(callDetails, allow)
            return
        }

        Thread {
            val reject = try {
                link.screen(number)
            } catch (e: Throwable) {
                Log.w(TAG, "Süzme sorgusu başarısız; arama normal çalacak", e)
                false
            }
            val response = if (reject) {
                CallResponse.Builder()
                    .setDisallowCall(true)
                    .setRejectCall(true)
                    .setSkipNotification(true)
                    .setSkipCallLog(false)
                    .build()
            } else {
                allow
            }
            respondSafely(callDetails, response)
        }.start()
    }

    private fun respondSafely(details: Call.Details, response: CallResponse) {
        try {
            respondToCall(details, response)
        } catch (e: Throwable) {
            Log.w(TAG, "respondToCall başarısız", e)
        }
    }

    companion object {
        private const val TAG = "UbCallScreening"
    }
}
