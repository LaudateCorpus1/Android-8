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

package com.duckduckgo.mobile.android.vpn.health

import com.duckduckgo.mobile.android.vpn.health.AppTPHealthMonitor.HealthState
import com.duckduckgo.mobile.android.vpn.health.AppTPHealthMonitor.HealthState.*
import com.duckduckgo.mobile.android.vpn.health.HealthClassifier.Companion.percentage
import timber.log.Timber
import javax.inject.Inject

class HealthClassifier @Inject constructor() {

    private val tunInputQueueReadHealthRule = TunInputQueueReadHealthRule()
    private val socketChannelReadExceptionRule = SocketChannelWriteExceptionRule()
    private val socketChannelWriteExceptionRule = SocketChannelReadExceptionRule()
    private val socketChannelConnectExceptionRule = SocketChannelConnectExceptionRule()

    fun determineHealthTunInputQueueReadRatio(tunInputs: Long, queueReads: Long): HealthState {
        return tunInputQueueReadHealthRule.healthStatus(tunInputs, queueReads)
    }

    fun determineHealthSocketChannelReadExceptions(readExceptions: Long, openedConnections: Int): HealthState {
        return socketChannelReadExceptionRule.healthStatus(readExceptions, openedConnections)
    }

    fun determineHealthSocketChannelWriteExceptions(writeExceptions: Long, openedConnections: Int): HealthState {
        return socketChannelWriteExceptionRule.healthStatus(writeExceptions, openedConnections)
    }

    fun determineHealthSocketChannelConnectExceptions(connectExceptions: Long, openedConnections: Int): HealthState {
        return socketChannelConnectExceptionRule.healthStatus(connectExceptions, openedConnections)
    }

    companion object {
        fun percentage(numerator: Long, denominator: Long): Double {
            if (denominator == 0L) return 0.0
            return numerator.toDouble() / denominator * 100
        }
    }
}

private class TunInputQueueReadHealthRule {
    fun healthStatus(tunInputs: Long, queueReads: Long): HealthState {
        if (tunInputs < 100) return Initializing
        return if (percentage(queueReads, tunInputs) >= 70) GoodHealth else BadHealth
    }
}

private class SocketChannelReadExceptionRule {
    fun healthStatus(exceptions: Long, numberConnection: Int): HealthState {
        Timber.v("There were %d socket read exceptions recently. %d open", exceptions, numberConnection)
        return if (exceptions >= 20) BadHealth else GoodHealth
    }
}

private class SocketChannelWriteExceptionRule {
    fun healthStatus(exceptions: Long, numberConnection: Int): HealthState {
        Timber.v("There were %d socket write exceptions recently. %d open", exceptions, numberConnection)
        return if (exceptions >= 20) BadHealth else GoodHealth
    }
}

private class SocketChannelConnectExceptionRule {
    fun healthStatus(exceptions: Long, numberConnection: Int): HealthState {
        Timber.v("There were %d socket connect exceptions recently. %d open", exceptions, numberConnection)
        return if (exceptions >= 20) BadHealth else GoodHealth
    }
}
