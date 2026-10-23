package dev.yikuo.youcommuter

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dev.yikuo.youcommuter.core.data.validatePredictionConfiguration
import dev.yikuo.youcommuter.core.domain.PredictionSettings
import dev.yikuo.youcommuter.core.model.PredictionServiceConfiguration
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class PredictionPreferences(context: Context) : PredictionSettings {
    private val preferences = context.applicationContext.getSharedPreferences("prediction_service", Context.MODE_PRIVATE)
    @Volatile private var current = PredictionServiceConfiguration(preferences.getString("endpoint", "").orEmpty(),
        runCatching { preferences.getString("key", null)?.let(::decrypt).orEmpty() }.getOrDefault(""))
    @Volatile override var revision = preferences.getLong("revision", 0)
        private set
    override fun load() = current

    override suspend fun save(configuration: PredictionServiceConfiguration) = withContext(Dispatchers.IO) {
        val validated = validatePredictionConfiguration(configuration, BuildConfig.DEBUG)
        val encrypted = validated.apiKey.takeIf { it.isNotEmpty() }?.let(::encrypt)
        synchronized(this@PredictionPreferences) {
            if (validated != current) {
                val next = revision + 1
                check(preferences.edit().putString("endpoint", validated.endpoint).putString("key", encrypted).putLong("revision", next).commit())
                current = validated
                revision = next
            }
        }
    }

    fun withRevision(expected: Long, action: () -> Unit): Boolean = synchronized(this) {
        if (revision != expected) false else { action(); true }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey("prediction_service", null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("prediction_service", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        return Base64.encodeToString(cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }
    private fun decrypt(value: String): String {
        val bytes = Base64.decode(value, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12))) }
        return String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
    }
}
