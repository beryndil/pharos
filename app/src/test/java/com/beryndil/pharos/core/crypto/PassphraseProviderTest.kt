package com.beryndil.pharos.core.crypto

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PassphraseProviderTest {

    /**
     * [FixedPassphraseProvider] must return exactly [PassphraseProvider.PASSPHRASE_LENGTH] bytes.
     */
    @Test
    fun testPassphraseProviderReturns32Bytes() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val result = FixedPassphraseProvider.getOrCreatePassphrase(ctx)
        assertEquals(
            "Passphrase must be exactly ${PassphraseProvider.PASSPHRASE_LENGTH} bytes",
            PassphraseProvider.PASSPHRASE_LENGTH,
            result.size,
        )
    }

    /** Each call must return a distinct copy so the caller can zero it independently. */
    @Test
    fun testPassphraseReturnsCopyNotReference() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val a = FixedPassphraseProvider.getOrCreatePassphrase(ctx)
        val b = FixedPassphraseProvider.getOrCreatePassphrase(ctx)
        assertNotSame("Each call must return a distinct array copy", a, b)
    }
}
