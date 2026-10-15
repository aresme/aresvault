package com.ares.vault.core.autofill

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PublicSuffixListTest {
    private val psl = PublicSuffixList.default
    private fun r(host: String) = psl.registrableDomain(host)

    @Test fun spec() {
        assertThat(r("login.example.co.ke")).isEqualTo("example.co.ke")
        assertThat(r("example.co.ke")).isEqualTo("example.co.ke")
        assertThat(r("co.ke")).isNull()
    }

    // Subset of the official PSL test vectors (publicsuffix.org/list/test.txt).
    @Test fun officialVectors() {
        assertThat(r("com")).isNull()
        assertThat(r("example.com")).isEqualTo("example.com")
        assertThat(r("b.example.com")).isEqualTo("example.com")
        assertThat(r("a.b.example.com")).isEqualTo("example.com")
        assertThat(r("uk.com")).isNull()
        assertThat(r("example.uk.com")).isEqualTo("example.uk.com")
        assertThat(r("b.example.uk.com")).isEqualTo("example.uk.com")
        assertThat(r("test.ac")).isEqualTo("test.ac")
        assertThat(r("jp")).isNull()
        assertThat(r("test.jp")).isEqualTo("test.jp")
        assertThat(r("www.test.jp")).isEqualTo("test.jp")
        assertThat(r("ac.jp")).isNull()
        assertThat(r("test.ac.jp")).isEqualTo("test.ac.jp")
        assertThat(r("kyoto.jp")).isNull()
        assertThat(r("test.kyoto.jp")).isEqualTo("test.kyoto.jp")
        assertThat(r("ide.kyoto.jp")).isNull()
        assertThat(r("b.ide.kyoto.jp")).isEqualTo("b.ide.kyoto.jp")
        assertThat(r("c.kobe.jp")).isNull() // wildcard *.kobe.jp
        assertThat(r("b.c.kobe.jp")).isEqualTo("b.c.kobe.jp")
        assertThat(r("city.kobe.jp")).isEqualTo("city.kobe.jp") // exception !city.kobe.jp
        assertThat(r("www.city.kobe.jp")).isEqualTo("city.kobe.jp")
        assertThat(r("ck")).isNull()
        assertThat(r("test.ck")).isNull()
        assertThat(r("b.test.ck")).isEqualTo("b.test.ck")
        assertThat(r("www.ck")).isEqualTo("www.ck")
        assertThat(r("www.www.ck")).isEqualTo("www.ck")
        assertThat(r("us")).isNull()
        assertThat(r("test.us")).isEqualTo("test.us")
        assertThat(r("www.test.us")).isEqualTo("test.us")
        assertThat(r("ak.us")).isNull()
        assertThat(r("test.ak.us")).isEqualTo("test.ak.us")
        assertThat(r("k12.ak.us")).isNull()
        assertThat(r("test.k12.ak.us")).isEqualTo("test.k12.ak.us")
        assertThat(r("食狮.com.cn")).isEqualTo("xn--85x722f.com.cn")
        assertThat(r("食狮.公司.cn")).isEqualTo("xn--85x722f.xn--55qx5d.cn")
        assertThat(r("shishi.中国")).isEqualTo("shishi.xn--fiqs8s")
        assertThat(r("中国")).isNull()
    }

    @Test fun privateSectionSeparatesUserSites() {
        assertThat(r("alice.github.io")).isEqualTo("alice.github.io")
        assertThat(r("bob.github.io")).isNotEqualTo(r("alice.github.io"))
        assertThat(r("x.blogspot.com")).isEqualTo("x.blogspot.com")
    }

    @Test fun kenyanAndCommonSites() {
        assertThat(r("www.safaricom.co.ke")).isEqualTo("safaricom.co.ke")
        assertThat(r("accounts.google.com")).isEqualTo("google.com")
        assertThat(r("m.facebook.com")).isEqualTo("facebook.com")
        assertThat(r("www.ecitizen.go.ke")).isEqualTo("ecitizen.go.ke")
    }
}
