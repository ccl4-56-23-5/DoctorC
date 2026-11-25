package local.bookdoctorc

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

internal object ClmPassphrase {
    private const val ALIAS = "mook.clm.passphrase.v1"
    private const val IV_SIZE = 12

    private fun file(context: Context) = AtomicFile(File(context.noBackupFilesDir, "clm-passphrase"))
    private fun keys() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun cipher() = Cipher.getInstance("AES/GCM/NoPadding")

    @JvmStatic
    @Synchronized
    fun read(context: Context): String? = runCatching {
        val data = file(context).readFully()
        val key = keys().getKey(ALIAS, null) as? SecretKey ?: return null
        require(data.size > IV_SIZE + 16)
        val cipher = cipher()
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, data, 0, IV_SIZE))
        val value = cipher.doFinal(data, IV_SIZE, data.size - IV_SIZE)
        try { value.toString(Charsets.UTF_8) } finally { value.fill(0) }
    }.getOrNull()

    @JvmStatic
    @Synchronized
    fun save(context: Context, password: String) {
        require(password.isNotEmpty())
        val key = keys().getKey(ALIAS, null) as? SecretKey
            ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
                init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(256)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build())
                generateKey()
            }
        val cipher = cipher()
        cipher.init(Cipher.ENCRYPT_MODE, key)
        require(cipher.iv.size == IV_SIZE)
        val value = password.toByteArray(Charsets.UTF_8)
        val data = try { cipher.iv + cipher.doFinal(value) } finally { value.fill(0) }
        val file = file(context)
        val output = file.startWrite()
        try {
            output.write(data)
            file.finishWrite(output)
        } catch (error: Exception) {
            file.failWrite(output)
            throw error
        }
    }

    @JvmStatic
    @Synchronized
    fun clear(context: Context) {
        keys().deleteEntry(ALIAS)
        file(context).delete()
    }
}
