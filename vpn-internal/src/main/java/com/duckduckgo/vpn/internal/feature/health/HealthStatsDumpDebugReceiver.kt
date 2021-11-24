/*
 * Copyright (c) 2021 DuckDuckGo
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.duckduckgo.vpn.internal.feature.health

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.duckduckgo.di.scopes.AppObjectGraph
import com.duckduckgo.mobile.android.vpn.health.HealthMetricCounter
import com.duckduckgo.mobile.android.vpn.service.VpnServiceCallbacks
import com.duckduckgo.mobile.android.vpn.service.VpnStopReason
import com.squareup.anvil.annotations.ContributesMultibinding
import kotlinx.coroutines.CoroutineScope
import timber.log.Timber
import javax.inject.Inject

/**
 * This receiver allows to dump the health stats to the console.
 *
 * adb shell am broadcast -a healthdump
 */
class HealthStatsDumpDebugReceiver(
    context: Context,
    intentAction: String = ACTION,
    private val receiver: (Intent) -> Unit
) : BroadcastReceiver() {

    init {
        kotlin.runCatching { context.unregisterReceiver(this) }
        context.registerReceiver(this, IntentFilter(intentAction))
    }

    override fun onReceive(context: Context, intent: Intent) {
        receiver(intent)
    }

    companion object {
        private const val ACTION = "healthdump"

        fun ruleIntent(): Intent {
            return Intent(ACTION)
        }
    }
}

@ContributesMultibinding(AppObjectGraph::class)
class HealthStatsDumpDebugReceiverRegister @Inject constructor(
    private val context: Context,
    private val healthMetricCounter: HealthMetricCounter
) : VpnServiceCallbacks {

    private fun execute() {
        healthMetricCounter.printStats()
    }

    override fun onVpnStarted(coroutineScope: CoroutineScope) {
        Timber.i("Debug receiver %s registered ", HealthStatsDumpDebugReceiver::class.java.simpleName)

        HealthStatsDumpDebugReceiver(context) {
            execute()
        }
    }

    override fun onVpnStopped(coroutineScope: CoroutineScope, vpnStopReason: VpnStopReason) {
        Timber.i("Debug receiver %s stopping", HealthStatsDumpDebugReceiver::class.java.simpleName)
    }
}
