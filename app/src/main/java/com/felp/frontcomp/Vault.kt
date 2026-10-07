package com.felp.frontcomp

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Las credenciales de las fuentes de arte, cifradas con una clave que no sale del aparato.
 *
 * La clave es AES de 256 bits y vive en el almacén de claves de Android: se genera dentro, se
 * usa dentro y no hay forma de exportarla. En las preferencias queda solo el texto cifrado, así
 * que no sirve de nada copiar los ficheros de la aplicación, sacarlos en una copia de seguridad
 * ni leerlos con `run-as` en una versión de depuración: sin la clave, que se queda en este
 * aparato, son bytes. Por lo mismo, una copia restaurada en otro aparato no se puede abrir y la
 * fuente pide las credenciales otra vez, que es lo que debe pasar.
 *
 * Lo que no puede evitar es que las lea código que se ejecute COMO esta aplicación en este
 * aparato: para usar la clave hay que tenerla en claro en memoria. Ese es el límite de lo que una
 * aplicación puede proteger por sí misma.
 */
internal object Vault {
    /** Con el nombre de antes a propósito: con otro alias, lo ya cifrado no se podría abrir. */
    private const val ALIAS = "frontcomp.credentials"

    /** Marca lo cifrado, para distinguirlo de lo que guardó en claro una versión anterior. */
    private const val SEALED = "v1:"

    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    // De uno en uno: dos hilos a la vez la primera vez generarian dos claves con el mismo alias,
    // la segunda pisaria a la primera, y lo cifrado con la primera ya no se podria abrir.
    @Synchronized
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    fun isSealed(stored: String): Boolean = stored.startsWith(SEALED)

    /** Cifra un valor. El vector de arranque lo elige el almacén y va delante del texto. */
    fun seal(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val out = c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return SEALED + Base64.encodeToString(c.iv + out, Base64.NO_WRAP)
    }

    /**
     * Descifra lo que dejó [seal]. Null si no se puede: otra clave (una copia de otro aparato,
     * o la clave borrada al restablecer el aparato) o un valor dañado.
     */
    fun open(stored: String): String? {
        if (!isSealed(stored)) return null
        return runCatching {
            val raw = Base64.decode(stored.removePrefix(SEALED), Base64.NO_WRAP)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, raw, 0, IV_BYTES))
            String(c.doFinal(raw, IV_BYTES, raw.size - IV_BYTES), Charsets.UTF_8)
        }.getOrNull()
    }
}
