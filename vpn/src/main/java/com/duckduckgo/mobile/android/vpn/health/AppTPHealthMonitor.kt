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
import com.duckduckgo.app.utils.ConflatedJob
import com.duckduckgo.di.scopes.VpnObjectGraph
import com.duckduckgo.mobile.android.vpn.di.VpnCoroutineScope
import com.duckduckgo.mobile.android.vpn.health.AppTPHealthMonitor.HealthState.*
import com.duckduckgo.mobile.android.vpn.health.SimpleEvent.Companion.REMOVE_FROM_DEVICE_TO_NETWORK_QUEUE
import com.duckduckgo.mobile.android.vpn.health.SimpleEvent.Companion.SOCKET_CHANNEL_CONNECT_EXCEPTION
import com.duckduckgo.mobile.android.vpn.health.SimpleEvent.Companion.SOCKET_CHANNEL_READ_EXCEPTION
import com.duckduckgo.mobile.android.vpn.health.SimpleEvent.Companion.SOCKET_CHANNEL_WRITE_EXCEPTION
import com.duckduckgo.mobile.android.vpn.health.SimpleEvent.Companion.TUN_READ
import com.duckduckgo.mobile.android.vpn.service.VpnQueues
import com.duckduckgo.mobile.android.vpn.service.VpnServiceCallbacks
import com.duckduckgo.mobile.android.vpn.service.VpnStopReason
import com.squareup.anvil.annotations.ContributesMultibinding
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
@ContributesMultibinding(VpnObjectGraph::class)
//        store raw metrics as they happen.
//            - sample the health regularly (every 10s?)
//            - every sample, get stats from last 10s, and produce a single RATE
//            - don't store the rate. but instead check if the rate is acceptable. if it is, all is well.
//            - if the rate is not acceptable, flag it but don't yet raise the alarm
//            - next health sample, check rate again. if it's fine now, remove the flag (false alarm)
//            - if the rate is still not good after N samples, the system is now in bad health. Raise the alarm.

class AppTPHealthMonitor @Inject constructor(
    @VpnCoroutineScope private val coroutineScope: CoroutineScope,
    private val healthMetricCounter: HealthMetricCounter,
    private val healthClassifier: HealthClassifier,
    private val applicationContext: Context,
    private val tracerPacketBuilder: TracerPacketBuilder,
    private val tracerPacketRegister: TracerPacketRegister,
    // private val vpnStateCollector: VpnStateCollector,
    private val vpnQueues: VpnQueues
) :
    VpnServiceCallbacks {

    private val _healthState = MutableStateFlow<HealthState>(Initializing)
    val healthState: StateFlow<HealthState> = _healthState

    private val monitoringJob = ConflatedJob()
    private val tracerInjectionJob = ConflatedJob()

    private var simulatedGoodHealth: Boolean? = null

    private var tunReadQueueReadAlertDuration: Int = 0
    private var socketChannelReadExceptionAlertDuration = 0
    private var socketChannelWriteExceptionAlertDuration = 0
    private var socketChannelConnectExceptionAlertDuration = 0

    private val badHealthNotificationManager = HealthNotificationManager(applicationContext)

    private suspend fun checkCurrentHealth() {

//        val vpnState = vpnStateCollector.collectVpnState(applicationContext.packageName)
//        Timber.v("VPNSTATE:\n%s", vpnState.toString(4))

        sampleTunReadQueueReadRate()

        with(numberOpenTcpConnections()) {
            sampleSocketReadExceptions(this)
            sampleSocketWriteExceptions(this)
            sampleSocketConnectExceptions(this)
        }

        /*
         * temporary hack; remove once development done
         */
        temporarySimulatedHealthCheck()

        if (isInProlongedBadHealth()) {
            Timber.i("App health check caught some problem(s)")
            _healthState.emit(BadHealth)
            badHealthNotificationManager.showBadHealthNotification()
        } else {
            Timber.i("App health check is good")
            _healthState.emit(GoodHealth)
            badHealthNotificationManager.hideBadHealthNotification()
        }
    }

    private fun sampleTunReadQueueReadRate() {
        val tunReads = healthMetricCounter.getStat(TUN_READ())
        val readFromNetworkQueue = healthMetricCounter.getStat(REMOVE_FROM_DEVICE_TO_NETWORK_QUEUE())

        when (healthClassifier.determineHealthTunInputQueueReadRatio(tunReads, readFromNetworkQueue)) {
            is GoodHealth -> tunReadQueueReadAlertDuration = 0
            is BadHealth -> tunReadQueueReadAlertDuration++
            else -> tunReadQueueReadAlertDuration = 0
        }
    }

    private fun sampleSocketReadExceptions(openConnections: Int) {
        val readExceptions = healthMetricCounter.getStat(SOCKET_CHANNEL_READ_EXCEPTION())

        when (healthClassifier.determineHealthSocketChannelReadExceptions(readExceptions, openConnections)) {
            is GoodHealth -> socketChannelReadExceptionAlertDuration = 0
            is BadHealth -> socketChannelReadExceptionAlertDuration++
            else -> socketChannelReadExceptionAlertDuration = 0
        }
    }

    private fun sampleSocketWriteExceptions(openConnections: Int) {
        val writeExceptions = healthMetricCounter.getStat(SOCKET_CHANNEL_WRITE_EXCEPTION())

        when (healthClassifier.determineHealthSocketChannelWriteExceptions(writeExceptions, openConnections)) {
            is GoodHealth -> socketChannelWriteExceptionAlertDuration = 0
            is BadHealth -> socketChannelWriteExceptionAlertDuration++
            else -> socketChannelWriteExceptionAlertDuration = 0
        }
    }

    private fun sampleSocketConnectExceptions(openConnections: Int) {
        val connectExceptions = healthMetricCounter.getStat(SOCKET_CHANNEL_CONNECT_EXCEPTION())

        when (healthClassifier.determineHealthSocketChannelWriteExceptions(connectExceptions, openConnections)) {
            is GoodHealth -> socketChannelConnectExceptionAlertDuration = 0
            is BadHealth -> socketChannelConnectExceptionAlertDuration++
            else -> socketChannelConnectExceptionAlertDuration = 0
        }
    }

    private fun isInProlongedBadHealth(): Boolean {
        var badHealthFlag = false

        if (tunReadQueueReadAlertDuration >= 5) {
            badHealthFlag = true
        }

        if (socketChannelReadExceptionAlertDuration >= 5) {
            badHealthFlag = true
        }

        if (socketChannelWriteExceptionAlertDuration >= 5) {
            badHealthFlag = true
        }

        if (socketChannelConnectExceptionAlertDuration >= 5) {
            badHealthFlag = true
        }

        return badHealthFlag
    }

    private fun numberOpenTcpConnections(): Int = 0

    private fun temporarySimulatedHealthCheck() {
        if (simulatedGoodHealth == true) {
            Timber.i("Pretending good health")
            tunReadQueueReadAlertDuration = 0
        } else if (simulatedGoodHealth == false) {
            Timber.i("Pretending bad health")
            tunReadQueueReadAlertDuration = 40
        }
    }

    fun startMonitoring() {
        Timber.v("AppTp Health - start monitoring")

        monitoringJob += coroutineScope.launch {
            while (isActive) {
                checkCurrentHealth()
                delay(MONITORING_INTERVAL_MS)
            }
        }

        tracerInjectionJob += coroutineScope.launch {
            while (isActive) {
                injectTracerPacket()
                delay(TRACER_INJECTION_FREQUENCY_MS)
            }
        }
    }

    private fun injectTracerPacket() {
        val packet = tracerPacketBuilder.build()
        tracerPacketRegister.logEvent(TracerEvent(packet.tracerId, TracedState.CREATED))
        tracerPacketRegister.logEvent(TracerEvent(packet.tracerId, TracedState.ADDED_TO_NETWORK_TO_DEVICE_QUEUE))
        vpnQueues.tcpDeviceToNetwork.offer(packet)
    }

    fun stopMonitoring() {
        Timber.v("AppTp Health - stop monitoring")

        monitoringJob.cancel()
        tracerInjectionJob.cancel()
    }

    companion object {
        private const val MONITORING_INTERVAL_MS: Long = 1_000
        private const val TRACER_INJECTION_FREQUENCY_MS: Long = 5_000

        const val BAD_HEALTH_NOTIFICATION_ID = 9890
    }

    sealed class HealthState {
        object Initializing : HealthState()
        object GoodHealth : HealthState()
        object BadHealth : HealthState()
    }

    override fun onVpnStarted(coroutineScope: CoroutineScope) {
        startMonitoring()
    }

    override fun onVpnStopped(coroutineScope: CoroutineScope, vpnStopReason: VpnStopReason) {
        stopMonitoring()
    }

    fun simulateHealthState(goodHealth: Boolean?) {
        this.simulatedGoodHealth = goodHealth
    }

    fun toggleNotifications(shouldShowNotifications: Boolean) {
        badHealthNotificationManager.shouldShowNotifications = shouldShowNotifications
    }

}
