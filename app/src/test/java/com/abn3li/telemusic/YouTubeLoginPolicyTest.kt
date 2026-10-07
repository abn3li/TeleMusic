package com.abn3li.telemusic

import com.abn3li.telemusic.data.youtube.YouTubeLoginPolicy
import org.junit.Assert.*
import org.junit.Test

class YouTubeLoginPolicyTest {
    @Test fun onlyTheExactHttpsMusicOriginCanConfirmASession() {
        assertTrue(YouTubeLoginPolicy.isMusicPage("https://music.youtube.com/library"))
        assertFalse(YouTubeLoginPolicy.isMusicPage("https://music.youtube.com.attacker.example/"))
        assertFalse(YouTubeLoginPolicy.isMusicPage("http://music.youtube.com/"))
        assertFalse(YouTubeLoginPolicy.isMusicPage("https://music.youtube.com:8443/"))
        assertFalse(YouTubeLoginPolicy.isMusicPage("https://attacker@music.youtube.com/"))
        assertFalse(YouTubeLoginPolicy.isMusicPage(null))
    }
    @Test fun detectsGoogleRejectionWithoutTreatingNormalLoginAsBlocked() {
        assertTrue(YouTubeLoginPolicy.isGoogleRejection("https://accounts.google.com/v3/signin/rejected"))
        assertFalse(YouTubeLoginPolicy.isGoogleRejection(YouTubeLoginPolicy.SIGN_IN_URL))
        assertFalse(YouTubeLoginPolicy.isGoogleRejection("https://attacker.example/rejected"))
    }
    @Test fun acceptsTheSupportedSigningCookieVariantsButRejectsAnonymousSessions() {
        assertEquals("primary", YouTubeLoginPolicy.signingSecret("SAPISID=primary; __Secure-3PAPISID=third"))
        assertEquals("third", YouTubeLoginPolicy.signingSecret("SAPISID=; __Secure-3PAPISID=third"))
        assertEquals("first", YouTubeLoginPolicy.signingSecret("__Secure-1PAPISID=first"))
        assertNull(YouTubeLoginPolicy.signingSecret("PREF=gl=US; SAPISID="))
    }
    @Test fun selectedGoogleAccountIndexIsKeptAndMalformedValuesAreRejected() {
        assertEquals("2", YouTubeLoginPolicy.normalizeAuthUser("2"))
        assertEquals("0", YouTubeLoginPolicy.normalizeAuthUser("-1"))
        assertEquals("0", YouTubeLoginPolicy.normalizeAuthUser("2x"))
    }
}
