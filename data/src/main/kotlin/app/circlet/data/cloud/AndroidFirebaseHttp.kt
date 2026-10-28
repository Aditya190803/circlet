package app.circlet.data.cloud

import android.content.Context
import android.content.pm.PackageManager
import java.security.MessageDigest

/** Identify the installed Android app when the Firebase API key has Android restrictions. */
internal class AndroidFirebaseHttp(private val context: Context) : CloudHttp {
    private val identity by lazy {
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val certificate = checkNotNull(info.signingInfo?.apkContentsSigners?.singleOrNull()) {
            "The installed app must have one signing certificate"
        }
        androidFirebaseHeaders(context.packageName, certificate.toByteArray())
    }

    override fun request(url: String, method: String, body: String?): CloudHttpResponse =
        FirebaseHttp.request(url, method, body, identity)
}

internal fun androidFirebaseHeaders(packageName: String, certificate: ByteArray): Map<String, String> = mapOf(
    "X-Android-Package" to packageName,
    "X-Android-Cert" to MessageDigest.getInstance("SHA-1").digest(certificate).joinToString("") { "%02X".format(it) },
)
