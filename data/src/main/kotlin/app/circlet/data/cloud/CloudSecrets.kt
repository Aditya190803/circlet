package app.circlet.data.cloud

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Refresh tokens are encrypted with a non-exportable Android Keystore key. */
internal class CloudSecrets(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "firebase-session"))
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("circlet-firebase", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("circlet-firebase", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    @Synchronized fun write(token: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encrypted = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        val output = file.startWrite()
        try {
            output.write(cipher.iv.size)
            output.write(cipher.iv)
            output.write(encrypted)
            file.finishWrite(output)
        } catch (failed: Exception) { file.failWrite(output); throw failed }
    }

    @Synchronized fun read(): String? {
        if (!file.baseFile.exists()) return null
        return file.openRead().use { input ->
            val ivLength = input.read()
            require(ivLength in 12..16)
            val iv = ByteArray(ivLength)
            java.io.DataInputStream(input).readFully(iv)
            val encrypted = input.readBytes()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv)) }
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        }
    }
}
