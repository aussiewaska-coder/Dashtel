package dev.dialer.voice.data

import android.content.Context
import android.net.ConnectivityManager
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

internal enum class DnsRoute {
    ActiveNetwork,
    SecureFallback,
}

/**
 * Uses Android DNS first, then the active Network resolver, then encrypted DNS.
 * The last stage has fixed bootstrap addresses so it still works when the phone's
 * configured DNS server cannot resolve the DoH provider itself.
 */
internal class ResilientDns(
    private val systemLookup: (String) -> List<InetAddress>,
    private val activeNetworkLookup: (String) -> List<InetAddress>,
    private val secureLookup: (String) -> List<InetAddress>,
    private val onFallback: (DnsRoute, String) -> Unit = { _, _ -> },
) : Dns {

    override fun lookup(hostname: String): List<InetAddress> {
        lookupOrEmpty(systemLookup, hostname).takeIf { it.isNotEmpty() }?.let { return it }
        lookupOrEmpty(activeNetworkLookup, hostname).takeIf { it.isNotEmpty() }?.let {
            onFallback(DnsRoute.ActiveNetwork, hostname)
            return it
        }
        lookupOrEmpty(secureLookup, hostname).takeIf { it.isNotEmpty() }?.let {
            onFallback(DnsRoute.SecureFallback, hostname)
            return it
        }
        throw UnknownHostException("Unable to resolve $hostname with Android or secure DNS")
    }

    private fun lookupOrEmpty(
        lookup: (String) -> List<InetAddress>,
        hostname: String,
    ): List<InetAddress> = try {
        lookup(hostname)
    } catch (_: Exception) {
        emptyList()
    }

    companion object {
        fun create(
            context: Context,
            onFallback: (DnsRoute, String) -> Unit = { _, _ -> },
        ): ResilientDns {
            val connectivity = context.getSystemService(ConnectivityManager::class.java)
            val secure = SecureDnsLookup()
            return ResilientDns(
                systemLookup = Dns.SYSTEM::lookup,
                activeNetworkLookup = { hostname ->
                    connectivity.activeNetwork?.getAllByName(hostname)?.toList().orEmpty()
                },
                secureLookup = secure::lookup,
                onFallback = onFallback,
            )
        }
    }
}

/** Minimal JSON DoH client kept separate from the Twilio client to avoid DNS recursion. */
private class SecureDnsLookup {

    private data class Provider(val endpoint: String, val hostname: String)

    private val providers = listOf(
        Provider("https://cloudflare-dns.com/dns-query", "cloudflare-dns.com"),
        Provider("https://dns.google/resolve", "dns.google"),
    )
    private val bootstrap = mapOf(
        "cloudflare-dns.com" to listOf(ipv4(1, 1, 1, 1), ipv4(1, 0, 0, 1)),
        "dns.google" to listOf(ipv4(8, 8, 8, 8), ipv4(8, 8, 4, 4)),
    )
    private val http = OkHttpClient.Builder()
        .dns(
            Dns { hostname ->
                bootstrap[hostname] ?: Dns.SYSTEM.lookup(hostname)
            },
        )
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(7, TimeUnit.SECONDS)
        .build()

    fun lookup(hostname: String): List<InetAddress> {
        providers.forEach { provider ->
            val result = runCatching { query(provider, hostname) }.getOrDefault(emptyList())
            if (result.isNotEmpty()) return result
        }
        throw UnknownHostException("Secure DNS could not resolve $hostname")
    }

    private fun query(provider: Provider, hostname: String): List<InetAddress> {
        val addresses = LinkedHashSet<InetAddress>()
        listOf("A", "AAAA").forEach { type ->
            val url = provider.endpoint.toHttpUrl().newBuilder()
                .addQueryParameter("name", hostname)
                .addQueryParameter("type", type)
                .build()
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/dns-json")
                .get()
                .build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use
                val json = JSONObject(response.body?.string().orEmpty())
                if (json.optInt("Status", -1) != 0) return@use
                val answers = json.optJSONArray("Answer") ?: return@use
                for (index in 0 until answers.length()) {
                    val answer = answers.optJSONObject(index) ?: continue
                    if (answer.optInt("type") !in setOf(1, 28)) continue
                    parseNumericAddress(answer.optString("data"))?.let(addresses::add)
                }
            }
        }
        return addresses.toList()
    }

    private fun parseNumericAddress(value: String): InetAddress? {
        val numeric = value.trim()
        val looksLikeIpv4 = numeric.matches(Regex("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}"))
        val looksLikeIpv6 = ':' in numeric && numeric.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' || it == ':' || it == '.' }
        if (!looksLikeIpv4 && !looksLikeIpv6) return null
        return runCatching { InetAddress.getByName(numeric) }.getOrNull()
    }

    companion object {
        private fun ipv4(a: Int, b: Int, c: Int, d: Int): InetAddress =
            InetAddress.getByAddress(byteArrayOf(a.toByte(), b.toByte(), c.toByte(), d.toByte()))
    }
}
