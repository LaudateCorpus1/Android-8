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

import android.content.Context
import androidx.room.Room
import com.duckduckgo.mobile.android.vpn.di.VpnCoroutineScope
import com.duckduckgo.mobile.android.vpn.health.SimpleEvent.Companion.ADD_TO_DEVICE_TO_NETWORK_QUEUE
import com.duckduckgo.mobile.android.vpn.health.SimpleEvent.Companion.REMOVE_FROM_DEVICE_TO_NETWORK_QUEUE
import com.duckduckgo.mobile.android.vpn.health.SimpleEvent.Companion.SOCKET_CHANNEL_CONNECT_EXCEPTION
import com.duckduckgo.mobile.android.vpn.health.SimpleEvent.Companion.SOCKET_CHANNEL_READ_EXCEPTION
import com.duckduckgo.mobile.android.vpn.health.SimpleEvent.Companion.SOCKET_CHANNEL_WRITE_EXCEPTION
import com.duckduckgo.mobile.android.vpn.health.SimpleEvent.Companion.TUN_READ
import com.duckduckgo.mobile.android.vpn.health.TracerPacketRegister.TracerSummary.Completed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import timber.log.Timber
import java.text.NumberFormat
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HealthMetricCounter @Inject constructor(
    val context: Context,
    @VpnCoroutineScope val coroutineScope: CoroutineScope,
    val tracerPacketRegister: TracerPacketRegister
) {

    private val db = Room.inMemoryDatabaseBuilder(context, HealthStatsDatabase::class.java).build()
    private val healthStatsDao = db.healthStatDao()
    private val databaseDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val numberFormat = NumberFormat.getNumberInstance().also { it.maximumFractionDigits = 2 }

    private val now: Long
        get() = System.currentTimeMillis()

    fun onTunPacketReceived() {
        coroutineScope.launch(databaseDispatcher) {
            healthStatsDao.insert(TUN_READ())
        }
    }

    fun onWrittenToDeviceToNetworkQueue() {
        coroutineScope.launch(databaseDispatcher) {
            healthStatsDao.insert(ADD_TO_DEVICE_TO_NETWORK_QUEUE())
        }
    }

    fun onReadFromDeviceToNetworkQueue() {
        coroutineScope.launch(databaseDispatcher) {
            healthStatsDao.insert(REMOVE_FROM_DEVICE_TO_NETWORK_QUEUE())
        }
    }

    fun onSocketChannelReadError() {
        coroutineScope.launch(databaseDispatcher) {
            healthStatsDao.insert(SOCKET_CHANNEL_READ_EXCEPTION())
        }
    }

    fun onSocketChannelWriteError() {
        coroutineScope.launch(databaseDispatcher) {
            healthStatsDao.insert(SOCKET_CHANNEL_WRITE_EXCEPTION())
        }
    }

    fun onSocketChannelConnectError() {
        coroutineScope.launch(databaseDispatcher) {
            healthStatsDao.insert(SOCKET_CHANNEL_CONNECT_EXCEPTION())
        }
    }

    fun printStats() {
        coroutineScope.launch(databaseDispatcher) {
            val sb = StringBuilder("Health Stats snapshot\n")

            sb.tunToQueueMetrics()
            sb.deviceToNetworkQueueAddsRemovesMetrics()
            sb.tracerPacketMetrics()
            sb.socketChannelReadExceptionMetrics()
            sb.socketChannelWriteExceptionMetrics()
            sb.socketChannelConnectExceptionMetrics()

            Timber.i(sb.toString())
        }
    }

    fun getStat(type: SimpleEvent, recentTimeThreshold: Long? = null): Long {
        val timeWindow = recentTimeThreshold ?: (now - WINDOW_DURATION_MS)
        return healthStatsDao.eventCount(type.type, timeWindow)
    }

//    fun getStatHistory(type: SimpleEvent, recentTimeThreshold: Long? = null): List<Long> {
//        val timeWindow = recentTimeThreshold ?: (now - WINDOW_DURATION_MS)
//        return healthStatsDao.eventCount(type.type, timeWindow)
//    }

    private fun StringBuilder.tunToQueueMetrics() {
        val recentTimeThreshold = now - WINDOW_DURATION_MS
        val numberReceivedFromTun = getStat(TUN_READ(), recentTimeThreshold)
        val numberWrittenToQueue = getStat(ADD_TO_DEVICE_TO_NETWORK_QUEUE(), recentTimeThreshold)
        append(
            String.format(
                "\nTUN packets received: %d\tWritten to queue: %d\tPassthrough rate: %s",
                numberReceivedFromTun,
                numberWrittenToQueue,
                calculatePercentage(numberWrittenToQueue, numberReceivedFromTun)
            )
        )
    }

    private fun StringBuilder.deviceToNetworkQueueAddsRemovesMetrics() {
        val recentTimeThreshold = now - WINDOW_DURATION_MS
        val numberRead = getStat(REMOVE_FROM_DEVICE_TO_NETWORK_QUEUE(), recentTimeThreshold)
        val numberWritten = getStat(ADD_TO_DEVICE_TO_NETWORK_QUEUE(), recentTimeThreshold)
        append(
            String.format(
                "\nWritten to device-to-network queue: %d\tRead from device-to-network-queue: %d\tPassthrough rate: %s",
                numberWritten,
                numberRead,
                calculatePercentage(numberRead, numberWritten)
            )
        )
    }

    private fun StringBuilder.tracerPacketMetrics() {
        val traces = tracerPacketRegister.getAllTraces()
        val successes = traces.count { it is Completed }

        append(
            String.format(
                "\nTracer packets sent: %d\tTracer packets completed: %d\tSuccess rate: %s",
                traces.size,
                successes,
                calculatePercentage(successes.toLong(), traces.size.toLong())
            )
        )
    }

    private fun StringBuilder.socketChannelReadExceptionMetrics() {
        val recentTimeThreshold = now - WINDOW_DURATION_MS
        val stat = getStat(SOCKET_CHANNEL_READ_EXCEPTION(), recentTimeThreshold)
        append(String.format("Socket read exceptions: %d", stat))
    }

    private fun StringBuilder.socketChannelWriteExceptionMetrics() {
        val recentTimeThreshold = now - WINDOW_DURATION_MS
        val stat = getStat(SOCKET_CHANNEL_WRITE_EXCEPTION(), recentTimeThreshold)
        append(String.format("Socket write exceptions: %d", stat))
    }

    private fun StringBuilder.socketChannelConnectExceptionMetrics() {
        val recentTimeThreshold = now - WINDOW_DURATION_MS
        val stat = getStat(SOCKET_CHANNEL_CONNECT_EXCEPTION(), recentTimeThreshold)
        append(String.format("Socket connect exceptions: %d", stat))
    }

    private fun calculatePercentage(numerator: Long, denominator: Long): String {
        if (denominator == 0L) return "0%"
        return String.format("%s%%", numberFormat.format(numerator.toDouble() / denominator * 100))
    }

    companion object {
        private val WINDOW_DURATION_MS = TimeUnit.SECONDS.toMillis(10)
    }
}
