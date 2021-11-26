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
import timber.log.Timber
import javax.inject.Inject

class HealthClassifier @Inject constructor() {

    fun determineHealthTunInputQueueReadRatio(tunInputs: Long, queueReads: Long): HealthState {
        if (tunInputs < 100) return Initializing
        return if (percentage(queueReads, tunInputs) >= 70) GoodHealth else BadHealth
    }

    fun determineHealthSocketChannelReadExceptions(readExceptions: Long): HealthState {
        Timber.v("There were %d socket write exceptions recently.", readExceptions)
        return if (readExceptions >= 20) BadHealth else GoodHealth
    }

    fun determineHealthSocketChannelWriteExceptions(writeExceptions: Long): HealthState {
        Timber.v("There were %d socket read exceptions recently.", writeExceptions)
        return if (writeExceptions >= 20) BadHealth else GoodHealth
    }

    fun determineHealthSocketChannelConnectExceptions(connectExceptions: Long): HealthState {
        Timber.v("There were %d socket connect exceptions recently.", connectExceptions)
        return if (connectExceptions >= 20) BadHealth else GoodHealth
    }

    fun determineHealthTracerPackets(totalTraces: Int, successfulTraces: Int): HealthState {
        if (totalTraces < 10) return Initializing
        return if (percentage(successfulTraces.toLong(), totalTraces.toLong()) >= 95) GoodHealth else BadHealth
    }

    companion object {
        fun percentage(numerator: Long, denominator: Long): Double {
            if (denominator == 0L) return 0.0
            return numerator.toDouble() / denominator * 100
        }
    }
}
