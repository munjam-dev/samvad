package com.samvad.core

import com.samvad.core.safety.FirstContact
import com.samvad.core.safety.RiskLevel
import com.samvad.core.safety.ScamReason
import com.samvad.core.safety.ScamShield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScamShieldTest {
  private fun link(url: String) = ScamShield.assessLink(url)
  private fun reasons(url: String) = link(url).reasons
  private fun msg(text: String) = ScamShield.assess(text)

  // ---- Extraction ----

  @Test fun `extracts scheme, www and bare links with positions`() {
    val text = "see https://example.com/a, then www.example.org and bit.ly/abc."
    val links = ScamShield.extractLinks(text)
    assertEquals(listOf("https://example.com/a", "www.example.org", "bit.ly/abc"), links.map { it.raw })
    links.forEach { assertEquals(it.raw, text.substring(it.start, it.end)) }
  }

  @Test fun `trailing punctuation is not part of the link`() {
    assertEquals(listOf("https://example.com/x"), ScamShield.extractLinks("(https://example.com/x)!").map { it.raw })
    assertEquals(listOf("https://example.com"), ScamShield.extractLinks("Go to https://example.com.").map { it.raw })
  }

  @Test fun `ordinary text with dots is not a link`() {
    assertTrue(ScamShield.extractLinks("Rs.500/month for file.txt/backup, i.e. fine. e.g.in a day").isEmpty())
    assertTrue(ScamShield.extractLinks("mail me at ravi@example.com/now").isEmpty())
    assertTrue(ScamShield.extractLinks("https:// nothing").isEmpty())
  }

  @Test fun `link count is capped`() {
    val text = (1..30).joinToString(" ") { "https://example.com/$it" }
    assertEquals(ScamShield.MAX_LINKS, ScamShield.extractLinks(text).size)
  }

  @Test fun `very long input is bounded`() {
    val text = "a".repeat(100_000) + " https://example.com"
    assertTrue(ScamShield.extractLinks(text).isEmpty())
    assertTrue(msg(text).isClear)
  }

  // ---- Plain links stay NONE ----

  @Test fun `a plain https link to a normal domain is NONE`() {
    listOf(
      "https://example.com",
      "https://www.example.com/path?q=1#frag",
      "https://en.wikipedia.org/wiki/India",
      "https://maps.google.com/?q=Delhi",
      "https://www.google.co.in/search?q=samvad",
      "https://accounts.google.com/signin",
      "https://netbanking.hdfcbank.com/",
      "https://support.apple.com/en-in",
      "https://www.amazon.in/dp/B000",
      "https://www.irctc.co.in/nget/train-search",
      "https://sub.domain.example.co.uk/page",
      "www.example.com",
      "example.com/page",
      "https://youtu.be/abc123",
      "https://wa.me/919999999999",
      "https://app.example.in:8443/x",
    ).forEach { assertEquals(it, RiskLevel.NONE, link(it).level) }
  }

  @Test fun `a message with a plain link and friendly text is clear`() {
    assertTrue(msg("Check this out https://example.com/photos, from the trip").isClear)
    assertTrue(msg("Dinner at 8? Menu: https://restaurant.example.in/menu").isClear)
  }

  @Test fun `link host is the real destination in ascii`() {
    assertEquals("example.com", link("https://Example.COM/Path").host)
    assertEquals("evil.com", link("https://bank.com@evil.com/login").host)
    assertEquals("https://www.example.com", link("www.example.com").openUrl)
    assertEquals("https://bit.ly/abc", link("bit.ly/abc").openUrl)
  }

  // ---- Link risks ----

  @Test fun `ip address hosts are a warning`() {
    listOf(
      "http://192.168.0.1/login",
      "https://203.0.113.9/verify",
      "https://3232235777/x",
      "http://0x7f.0.0.1/",
      "http://[2001:db8::1]/x",
      "http://203.0.113.9:8080/a",
    ).forEach {
      assertTrue(it, ScamReason.LINK_IP_ADDRESS in reasons(it))
      assertEquals(it, RiskLevel.WARNING, link(it).level)
    }
    assertTrue(ScamShield.extractLinks("pay at 203.0.113.9/pay now").isNotEmpty())
  }

  @Test fun `a domain that only contains digits among letters is not an ip`() {
    assertFalse(ScamReason.LINK_IP_ADDRESS in reasons("https://123.example.com"))
    assertFalse(ScamReason.LINK_IP_ADDRESS in reasons("https://web3.example.com"))
  }

  @Test fun `userinfo tricks are a warning and show the real host`() {
    val r = link("https://bank.com@evil.com/login")
    assertTrue(ScamReason.LINK_USERINFO in r.reasons)
    assertEquals(RiskLevel.WARNING, r.level)
    assertEquals("evil.com", r.host)
    assertTrue(ScamReason.LINK_USERINFO in reasons("https://www.sbi.co.in:login@203.0.113.9/"))
    assertTrue(ScamReason.LINK_USERINFO in reasons("https://user@evil.com"))
  }

  @Test fun `a backslash ends the host like a browser does`() {
    val r = link("https://bank.com\\@evil.com")
    assertEquals("bank.com", r.host)
    assertFalse(ScamReason.LINK_USERINFO in r.reasons)
  }

  @Test fun `digits standing in for letters are a lookalike warning`() {
    listOf(
      "https://paypa1.com/login",
      "https://g00gle.com",
      "https://amaz0n.in/offer",
      "https://faceb00k.com",
      "https://app1e.com/id",
      "https://www.micr0soft.com",
      "https://whatsapp-web.paypa1.net",
      "https://arnazon.com",
      "https://fl1pkart.com",
      "https://paytrn.com",
    ).forEach { url ->
      assertTrue(url, ScamReason.LINK_LOOKALIKE_HOST in reasons(url))
      assertEquals(url, RiskLevel.WARNING, link(url).level)
    }
  }

  @Test fun `a brand plus a security word on a wrong domain is a lookalike`() {
    assertTrue(ScamReason.LINK_LOOKALIKE_HOST in reasons("https://sbi-kyc-update.com"))
    assertTrue(ScamReason.LINK_LOOKALIKE_HOST in reasons("https://login-paypal.net"))
    assertTrue(ScamReason.LINK_LOOKALIKE_HOST in reasons("https://hdfcbank-secure-login.in"))
  }

  @Test fun `a brand address buried before another domain is a lookalike`() {
    assertTrue(ScamReason.LINK_LOOKALIKE_HOST in reasons("https://paypal.com.secure-pay.net/x"))
    assertTrue(ScamReason.LINK_LOOKALIKE_HOST in reasons("https://amazon.in.deals.xyz"))
  }

  @Test fun `brand on a risky ending is a lookalike`() {
    assertTrue(ScamReason.LINK_LOOKALIKE_HOST in reasons("https://paypal.top"))
  }

  @Test fun `genuine brand domains are not lookalikes`() {
    listOf(
      "https://paypal.com",
      "https://www.paypal.com/signin",
      "https://login.microsoft.com",
      "https://accounts.google.com",
      "https://www.amazon.co.uk",
      "https://onlinesbi.sbi",
      "https://www.flipkart.com/offers",
      "https://appleseed.example.com",
      "https://pineapple.com",
      "https://mygoogle.example.com",
    ).forEach { assertFalse(it, ScamReason.LINK_LOOKALIKE_HOST in reasons(it)) }
  }

  @Test fun `cyrillic lookalike letters are a homograph warning`() {
    // "аpple.com" with a Cyrillic a, and a fully Cyrillic label under .com.
    val mixed = "https://аpple.com"
    assertTrue(ScamReason.LINK_HOMOGRAPH in reasons(mixed))
    assertEquals(RiskLevel.WARNING, link(mixed).level)
    assertTrue(link(mixed).host.startsWith("xn--"))
    assertTrue(ScamReason.LINK_HOMOGRAPH in reasons("https://аррӏе.com"))
    assertTrue(ScamReason.LINK_HOMOGRAPH in reasons("https://xn--pple-43d.com"))
  }

  @Test fun `other international names are only a caution`() {
    val r = link("https://café.example.com")
    assertEquals(setOf(ScamReason.LINK_PUNYCODE), r.reasons)
    assertEquals(RiskLevel.CAUTION, r.level)
    // A Devanagari name is not a Latin lookalike.
    assertEquals(setOf(ScamReason.LINK_PUNYCODE), reasons("https://संवाद.example.in"))
  }

  @Test fun `shorteners are a caution`() {
    listOf("bit.ly/3abc", "https://tinyurl.com/xyz", "https://t.co/abc", "https://www.bit.ly/x", "cutt.ly/a1").forEach {
      assertEquals(it, RiskLevel.CAUTION, link(it).level)
      assertTrue(it, ScamReason.LINK_SHORTENER in reasons(it))
    }
  }

  @Test fun `plain http is a caution`() {
    val r = link("http://example.com/page")
    assertEquals(setOf(ScamReason.LINK_NOT_HTTPS), r.reasons)
    assertEquals(RiskLevel.CAUTION, r.level)
  }

  @Test fun `very long or deeply nested hosts are a caution`() {
    assertTrue(ScamReason.LINK_LONG_HOST in reasons("https://a.b.c.d.e.example.com/x"))
    assertTrue(ScamReason.LINK_LONG_HOST in reasons("https://" + "a".repeat(60) + ".example.com"))
    assertFalse(ScamReason.LINK_LONG_HOST in reasons("https://a.b.c.example.com/x"))
  }

  @Test fun `risky endings are a caution but common ones are not`() {
    listOf("tk", "ml", "top", "icu", "zip").forEach {
      assertTrue(it, ScamReason.LINK_RISKY_TLD in reasons("https://prize-draw.$it"))
    }
    listOf("com", "in", "org", "xyz", "online", "app").forEach {
      assertFalse(it, ScamReason.LINK_RISKY_TLD in reasons("https://mysite.$it"))
    }
    assertEquals(RiskLevel.CAUTION, link("https://mysite.top").level)
  }

  @Test fun `odd characters in a host are flagged`() {
    assertTrue(ScamReason.LINK_MALFORMED_HOST in reasons("https://%65vil.com/x"))
    assertTrue(ScamReason.LINK_MALFORMED_HOST in reasons("https:///path"))
    assertTrue(ScamReason.LINK_MALFORMED_HOST in reasons("https://ex!ample.com"))
  }

  @Test fun `assessLink never throws on junk`() {
    listOf("", "://", "http://", "https://@", "https://[", "https://:80", "https://a..b", "https://" + "x".repeat(300) + ".com", "\u0000").forEach {
      link(it)
    }
  }

  // ---- Text patterns ----

  @Test fun `asking for an otp or pin is a caution`() {
    listOf(
      "Please share your OTP with me",
      "send me the otp you received",
      "tell me the UPI PIN",
      "otp batao jaldi",
      "OTP bhej do",
      "otp do",
      "apna ATM pin bhejiye",
      "read out the verification code",
      "ओटीपी बताओ",
    ).forEach {
      val a = msg(it)
      assertTrue(it, ScamReason.TEXT_SHARE_CODE in a.reasons)
      assertTrue(it, a.level >= RiskLevel.CAUTION)
    }
    assertEquals(RiskLevel.CAUTION, msg("Please share your OTP with me").level)
  }

  @Test fun `advice not to share a code is not flagged`() {
    listOf(
      "Your OTP is 482913. Do not share it with anyone.",
      "123456 is your verification code. Never share this code.",
      "OTP kisi ko mat batao",
      "Don't share your OTP or PIN with anyone, even bank staff",
      "I will send you the otp when it comes",
      "Never tell anyone your UPI PIN",
    ).forEach { assertTrue(it, msg(it).isClear) }
  }

  @Test fun `pin in other senses is not a code request`() {
    assertTrue(msg("Send me the pin of the location").isClear)
    assertTrue(msg("Please pin this message and send the file").isClear)
    assertTrue(msg("What is your pin code? Send it, I will courier the gift").isClear)
  }

  @Test fun `kyc and account verification wording is a caution`() {
    listOf(
      "Your KYC is pending, update now",
      "kyc update karo warna account band ho jayega",
      "Please verify your account",
      "Your account has been blocked",
      "Update your KYC today",
      "केवाईसी अपडेट करें",
    ).forEach {
      assertTrue(it, ScamReason.TEXT_KYC_VERIFY in msg(it).reasons)
    }
  }

  @Test fun `kyc with urgency is a warning`() {
    val a = msg("URGENT: your KYC is pending. Update immediately or your account will be blocked")
    assertEquals(RiskLevel.WARNING, a.level)
  }

  @Test fun `prize wording needs a win and a prize`() {
    assertTrue(ScamReason.TEXT_PRIZE in msg("Congratulations! You have won a lottery of 25 lakh").reasons)
    assertTrue(ScamReason.TEXT_PRIZE in msg("You won an iPhone in our lucky draw").reasons)
    assertTrue(ScamReason.TEXT_PRIZE in msg("KBC lottery me aapne 25 lakh jeete").reasons || ScamReason.TEXT_PRIZE in msg("KBC winner! you won 25 lakh").reasons)
    assertTrue(msg("You won the match today, great game").isClear)
    assertTrue(msg("Winner winner chicken dinner").isClear)
    assertTrue(msg("I bought a lottery ticket").isClear)
  }

  @Test fun `prize with a fee is a warning`() {
    val a = msg("You have won a prize of 10 lakh. Pay a processing fee to claim")
    assertEquals(RiskLevel.WARNING, a.level)
    assertTrue(ScamReason.TEXT_FEE_REQUEST in a.reasons)
  }

  @Test fun `gift card requests are a caution`() {
    assertTrue(ScamReason.TEXT_GIFT_CARD in msg("Buy a Google Play gift card and send me the code").reasons)
    assertTrue(ScamReason.TEXT_GIFT_CARD in msg("scratch the amazon gift card and read out the numbers").reasons)
    assertTrue(msg("I got you a gift card for your birthday").isClear)
    assertTrue(msg("Gift cards are on sale at the mall").isClear)
  }

  @Test fun `work from home earnings offers are a caution`() {
    assertTrue(ScamReason.TEXT_WORK_FROM_HOME in msg("Work from home and earn Rs.5000 per day").reasons)
    assertTrue(ScamReason.TEXT_WORK_FROM_HOME in msg("Earn ₹5000 daily, no experience needed").reasons)
    assertTrue(ScamReason.TEXT_WORK_FROM_HOME in msg("ghar baithe kamao 2000 daily").reasons)
    assertTrue(msg("I work from home on Fridays").isClear)
    assertTrue(msg("Do you earn well in your new job?").isClear)
  }

  @Test fun `urgent money asks are a caution`() {
    val a = msg("Urgent! Please send 5000 rupees to this number")
    assertTrue(ScamReason.TEXT_URGENT_MONEY in a.reasons)
    assertEquals(RiskLevel.CAUTION, a.level)
    assertTrue(ScamReason.TEXT_URGENT_MONEY in msg("emergency, I need money right now").reasons)
    assertTrue(ScamReason.TEXT_URGENT_MONEY in msg("turant paise bhej do").reasons)
    assertTrue(msg("Urgent: team meeting at 5").isClear)
    assertTrue(msg("Can you send me the notes? Thanks").isClear)
    assertTrue(msg("abhi paise bhej dunga").isClear)
  }

  @Test fun `two scam signs make a warning`() {
    val a = msg("You won a lottery! Share your OTP to claim the prize")
    assertEquals(RiskLevel.WARNING, a.level)
    assertTrue(ScamReason.TEXT_PRIZE in a.reasons && ScamReason.TEXT_SHARE_CODE in a.reasons)
  }

  @Test fun `everyday messages are clear`() {
    listOf(
      "Good morning! How are you?",
      "Can you share the photos from the trip?",
      "I'll send you the address, it's near the bank",
      "Please send me the report by evening",
      "Happy birthday! Here is a gift for you",
      "The bank is closed today",
      "My account is on Instagram, follow me",
      "Mera OTP aa gaya, thanks",
      "Kal milte hain, jaldi aana",
      "Do verify the numbers in the sheet",
      "Transfer the file when you are free",
      "Have you completed the form?",
      "आप कैसे हैं? कल मिलते हैं।",
      "",
      "   ",
    ).forEach { assertTrue(it, msg(it).isClear) }
  }

  @Test fun `case and invisible characters do not hide a scam`() {
    assertTrue(ScamReason.TEXT_SHARE_CODE in msg("SHARE YOUR O​TP NOW").reasons)
    assertTrue(ScamReason.TEXT_KYC_VERIFY in msg("YOUR KYC IS PENDING").reasons)
  }

  // ---- Whole message ----

  @Test fun `scam wording turns a cautionable link into a warning`() {
    val a = msg("Your KYC is pending, update at bit.ly/kyc123")
    assertEquals(RiskLevel.WARNING, a.level)
    assertEquals(RiskLevel.WARNING, a.links.single().level)
  }

  @Test fun `a shortener alone stays a caution`() {
    val a = msg("look at this bit.ly/cats")
    assertEquals(RiskLevel.CAUTION, a.level)
    assertEquals(listOf(ScamReason.LINK_SHORTENER), a.reasons)
  }

  @Test fun `a clean link in scam wording is still not warned about as a link`() {
    val a = msg("Share your OTP at https://example.com/help")
    assertEquals(RiskLevel.NONE, a.links.single().level)
    assertTrue(a.reasons.all { !it.isLink })
    assertTrue(a.level >= RiskLevel.CAUTION)
  }

  @Test fun `link words do not count as scam wording`() {
    // The words in the address are judged by the link checks, not the text rules.
    val a = msg("https://example.com/kyc-update-pending")
    assertTrue(a.isClear)
  }

  @Test fun `link reasons come before text reasons`() {
    val a = msg("Win a lottery prize of 5 lakh: you have won! http://203.0.113.9/claim")
    assertEquals(ScamReason.LINK_IP_ADDRESS, a.reasons.first())
    assertEquals(RiskLevel.WARNING, a.level)
    assertTrue(a.hasLinkReason)
  }

  @Test fun `reasons are codes and never message text`() {
    val a = msg("share your otp https://paypa1.com/AAA-SECRET-123")
    a.reasons.forEach { assertTrue(it.name.startsWith("LINK_") || it.name.startsWith("TEXT_")) }
  }

  // ---- First contact ----

  private fun show(
    isGroup: Boolean = false,
    contactsKnown: Boolean = true,
    isSaved: Boolean = false,
    incoming: Int = 1,
    outgoing: Int = 0,
    allLoaded: Boolean = true,
    dismissed: Boolean? = false,
    blocked: Boolean = false,
  ) = FirstContact.shouldShow(isGroup, contactsKnown, isSaved, incoming, outgoing, allLoaded, dismissed, blocked)

  @Test fun `first contact banner shows for an unsaved sender with no reply`() {
    assertTrue(show())
  }

  @Test fun `first contact banner is hidden when it does not apply`() {
    assertFalse(show(isGroup = true))
    assertFalse(show(contactsKnown = false))
    assertFalse(show(isSaved = true))
    assertFalse(show(incoming = 0))
    assertFalse(show(outgoing = 1))
    assertFalse(show(allLoaded = false))
    assertFalse(show(dismissed = true))
    assertFalse(show(dismissed = null))
    assertFalse(show(blocked = true))
  }
}
