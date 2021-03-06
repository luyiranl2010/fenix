/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.search.telemetry.ads

import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import mozilla.components.browser.state.state.BrowserState
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.concept.engine.Engine
import mozilla.components.concept.engine.EngineSession
import mozilla.components.concept.engine.webextension.MessageHandler
import mozilla.components.concept.engine.webextension.WebExtension
import mozilla.components.lib.state.ext.flowScoped
import mozilla.components.support.base.log.logger.Logger
import mozilla.components.support.ktx.android.org.json.toList
import mozilla.components.support.ktx.kotlinx.coroutines.flow.filterChanged
import org.json.JSONObject
import org.mozilla.fenix.components.metrics.Event
import org.mozilla.fenix.components.metrics.MetricController
import org.mozilla.fenix.ext.containsAds
import org.mozilla.fenix.search.telemetry.SearchProviderCookie
import org.mozilla.fenix.search.telemetry.SearchProviderModel

class AdsTelemetry(private val metrics: MetricController) {

    @VisibleForTesting
    internal val providerList = listOf(
        SearchProviderModel(
            name = "google",
            regexp = "^https:\\/\\/www\\.google\\.(?:.+)\\/search",
            queryParam = "q",
            codeParam = "client",
            codePrefixes = listOf("firefox"),
            followOnParams = listOf("oq", "ved", "ei"),
            extraAdServersRegexps = listOf("^https?:\\/\\/www\\.google(?:adservices)?\\.com\\/(?:pagead\\/)?aclk")
        ),
        SearchProviderModel(
            name = "duckduckgo",
            regexp = "^https:\\/\\/duckduckgo\\.com\\/",
            queryParam = "q",
            codeParam = "t",
            codePrefixes = listOf("ff"),
            followOnParams = listOf("oq", "ved", "ei"),
            extraAdServersRegexps = listOf(
                "^https:\\/\\/duckduckgo.com\\/y\\.js",
                "^https:\\/\\/www\\.amazon\\.(?:[a-z.]{2,24}).*(?:tag=duckduckgo-)"
            )
        ),
        SearchProviderModel(
            name = "yahoo",
            regexp = "^https:\\/\\/(?:.*)search\\.yahoo\\.com\\/search",
            queryParam = "p"
        ),
        SearchProviderModel(
            name = "baidu",
            regexp = "^https:\\/\\/www\\.baidu\\.com\\/from=844b\\/(?:s|baidu)",
            queryParam = "wd",
            codeParam = "tn",
            codePrefixes = listOf("34046034_", "monline_"),
            followOnParams = listOf("oq")
        ),
        SearchProviderModel(
            name = "bing",
            regexp = "^https:\\/\\/www\\.bing\\.com\\/search",
            queryParam = "q",
            codeParam = "pc",
            codePrefixes = listOf("MOZ", "MZ"),
            followOnParams = listOf("oq", "ved", "ei"),
            followOnCookies = listOf(
                SearchProviderCookie(
                    extraCodeParam = "form",
                    extraCodePrefixes = listOf("QBRE"),
                    host = "www.bing.com",
                    name = "SRCHS",
                    codeParam = "PC",
                    codePrefixes = listOf("MOZ", "MZ")
                )
            ),
            extraAdServersRegexps = listOf(
                "^https:\\/\\/www\\.bing\\.com\\/acli?c?k",
                "^https:\\/\\/www\\.bing\\.com\\/fd\\/ls\\/GLinkPingPost\\.aspx.*acli?c?k"
            )
        )
    )

    fun install(
        engine: Engine,
        store: BrowserStore
    ) {
        engine.installWebExtension(
            id = ADS_EXTENSION_ID,
            url = ADS_EXTENSION_RESOURCE_URL,
            allowContentMessaging = true,
            onSuccess = { extension ->
                Logger.debug("Installed ads extension")

                store.flowScoped { flow -> subscribeToUpdates(flow, extension) }
            },
            onError = { _, throwable ->
                Logger.error("Could not install ads extension", throwable)
            })
    }

    fun trackAdClickedMetric(sessionUrl: String?, urlPath: List<String>) {
        if (sessionUrl == null) {
            return
        }
        val provider = getProviderForUrl(sessionUrl)
        provider?.let {
            if (it.containsAds(urlPath)) {
                metrics.track(Event.SearchAdClicked(it.name))
            }
        }
    }

    @VisibleForTesting
    internal fun getProviderForUrl(url: String): SearchProviderModel? {
        for (provider in providerList) {
            if (Regex(provider.regexp).containsMatchIn(url)) {
                return provider
            }
        }
        return null
    }

    private suspend fun subscribeToUpdates(flow: Flow<BrowserState>, extension: WebExtension) {
        // Whenever we see a new EngineSession in the store then we register our content message
        // handler if it has not been added yet.
        flow.map { it.tabs }
            .filterChanged { it.engineState.engineSession }
            .collect { state ->
                val engineSession = state.engineState.engineSession ?: return@collect

                if (extension.hasContentMessageHandler(engineSession, ADS_MESSAGE_ID)) {
                    return@collect
                }

                val messageHandler = AdsTelemetryContentMessageHandler()
                extension.registerContentMessageHandler(
                    engineSession,
                    ADS_MESSAGE_ID,
                    messageHandler
                )
            }
    }

    @VisibleForTesting
    internal inner class AdsTelemetryContentMessageHandler : MessageHandler {

        override fun onMessage(message: Any, source: EngineSession?): Any? {
            if (message is JSONObject) {
                val urls = getDocumentUrlList(message)
                val provider = getProviderForUrl(message.getString(ADS_MESSAGE_SESSION_URL_KEY))
                provider?.let {
                    if (it.containsAds(urls)) {
                        metrics.track(Event.SearchWithAds(it.name))
                    }
                }
            } else {
                throw IllegalStateException("Received unexpected message: $message")
            }

            // Needs to return something that is not null and not Unit:
            // https://github.com/mozilla-mobile/android-components/issues/2969
            return ""
        }

        private fun getDocumentUrlList(message: JSONObject): List<String> {
            return message.getJSONArray(ADS_MESSAGE_DOCUMENT_URLS_KEY).toList()
        }
    }

    companion object {
        @VisibleForTesting
        internal const val ADS_EXTENSION_ID = "mozacBrowserAds"
        @VisibleForTesting
        internal const val ADS_EXTENSION_RESOURCE_URL = "resource://android/assets/extensions/ads/"
        @VisibleForTesting
        internal const val ADS_MESSAGE_SESSION_URL_KEY = "url"
        @VisibleForTesting
        internal const val ADS_MESSAGE_DOCUMENT_URLS_KEY = "urls"
        private const val ADS_MESSAGE_ID = "MozacBrowserAds"
    }
}
