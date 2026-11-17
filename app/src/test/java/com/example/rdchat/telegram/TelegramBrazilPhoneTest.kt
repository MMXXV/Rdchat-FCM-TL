package com.example.rdchat.telegram

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TelegramBrazilPhoneTest {
    @Test
    fun nationalMobileNumberGetsBrazilCountryCode() {
        assertEquals("+5511987654321", TelegramManager.normalizeBrazilianPhone("(11) 98765-4321"))
    }

    @Test
    fun nationalLandlineGetsBrazilCountryCode() {
        assertEquals("+551134567890", TelegramManager.normalizeBrazilianPhone("11 3456-7890"))
    }

    @Test
    fun alreadyPrefixedBrazilianNumberIsAccepted() {
        assertEquals("+5511987654321", TelegramManager.normalizeBrazilianPhone("+55 (11) 98765-4321"))
    }

    @Test
    fun dddFiftyFiveIsNotMistakenForCountryCodeWithoutCountryLength() {
        assertEquals("+5555991234567", TelegramManager.normalizeBrazilianPhone("55 99123-4567"))
    }

    @Test
    fun rejectsMissingDddOrInvalidLength() {
        assertNull(TelegramManager.normalizeBrazilianPhone("98765-4321"))
        assertNull(TelegramManager.normalizeBrazilianPhone("119876543"))
    }
}
