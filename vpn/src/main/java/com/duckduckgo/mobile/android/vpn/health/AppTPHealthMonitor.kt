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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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

    companion object {
        private const val NUMBER_OF_SAMPLES_TO_WAIT_FOR_ALERT = 5
        private const val SLIDING_WINDOW_DURATION_MS: Long = 10_000
        private const val MONITORING_INTERVAL_MS: Long = 1_000 // todo: make longer; 10s ?
        private const val TRACER_INJECTION_FREQUENCY_MS: Long = 5_000

        const val BAD_HEALTH_NOTIFICATION_ID = 9890
    }

    private val now: Long
        get() = System.currentTimeMillis()

    private val _healthState = MutableStateFlow<HealthState>(Initializing)
    val healthState: StateFlow<HealthState> = _healthState

    private val monitoringJob = ConflatedJob()
    private val tracerInjectionJob = ConflatedJob()

    private var simulatedGoodHealth: Boolean? = null

    private val healthRules = mutableListOf<HealthRule>()

    private val tunReadAlerts = object : HealthRule(5) {}.also { healthRules.add(it) }
    private val socketReadExceptionAlerts = object : HealthRule(5) {}.also { healthRules.add(it) }
    private val socketWriteExceptionAlerts = object : HealthRule(5) {}.also { healthRules.add(it) }
    private val socketConnectExceptionAlerts = object : HealthRule(5) {}.also { healthRules.add(it) }
    private val tracerPacketsAlerts = object : HealthRule(5) {}.also { healthRules.add(it) }

    abstract class HealthRule(open var samplesToWaitBeforeAlerting: Int) {
        var badHealthSampleCount: Int = 0

        fun recordBadHealthSample() {
            badHealthSampleCount++
        }

        fun resetBadHealthSampleCount() {
            badHealthSampleCount = 0
        }

        fun shouldAlertBadHealth(): Boolean {
            if (badHealthSampleCount == 0) return false
            return badHealthSampleCount >= samplesToWaitBeforeAlerting
        }
    }

    private val badHealthNotificationManager = HealthNotificationManager(applicationContext)

    private suspend fun checkCurrentHealth() {
        // val vpnState = vpnStateCollector.collectVpnState(applicationContext.packageName)
        // Timber.v("VPNSTATE:\n%s", vpnState.toString(4))

        val timeWindow = now - SLIDING_WINDOW_DURATION_MS

        sampleTunReadQueueReadRate(timeWindow, tunReadAlerts)
        sampleTracerPackets(timeWindow, tracerPacketsAlerts)
        sampleSocketReadExceptions(timeWindow, socketReadExceptionAlerts)
        sampleSocketWriteExceptions(timeWindow, socketWriteExceptionAlerts)
        sampleSocketConnectExceptions(timeWindow, socketConnectExceptionAlerts)

        /*
         * temporary hack; remove once development done
         */
        temporarySimulatedHealthCheck()

        val overallState = determineOverallState()

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

    private fun determineOverallState(): HealthState {
        return if (1 == 3) {
            Initializing
        } else if (isInProlongedBadHealth()) {
            BadHealth
        } else {
            GoodHealth
        }
    }

    private fun sampleTracerPackets(timeWindow: Long, healthAlerts: HealthRule) {
        val allTraces = tracerPacketRegister.getAllTraces(timeWindow)
        val successfulTraces = allTraces.count { it is TracerPacketRegister.TracerSummary.Completed }
        val state = healthClassifier.determineHealthTracerPackets(allTraces.size, successfulTraces)
        healthAlerts.updateAlert(state)
    }

    private fun sampleTunReadQueueReadRate(timeWindow: Long, healthAlerts: HealthRule) {
        val tunReads = healthMetricCounter.getStat(TUN_READ(), timeWindow)
        val readFromNetworkQueue = healthMetricCounter.getStat(REMOVE_FROM_DEVICE_TO_NETWORK_QUEUE(), timeWindow)

        val state = healthClassifier.determineHealthTunInputQueueReadRatio(tunReads, readFromNetworkQueue)
        healthAlerts.updateAlert(state)
    }

    private fun sampleSocketReadExceptions(timeWindow: Long, healthAlerts: HealthRule) {
        val readExceptions = healthMetricCounter.getStat(SOCKET_CHANNEL_READ_EXCEPTION(), timeWindow)
        val state = healthClassifier.determineHealthSocketChannelReadExceptions(readExceptions)
        healthAlerts.updateAlert(state)
    }

    private fun sampleSocketWriteExceptions(timeWindow: Long, healthAlerts: HealthRule) {
        val writeExceptions = healthMetricCounter.getStat(SOCKET_CHANNEL_WRITE_EXCEPTION(), timeWindow)
        val state = healthClassifier.determineHealthSocketChannelWriteExceptions(writeExceptions)
        healthAlerts.updateAlert(state)
    }

    private fun sampleSocketConnectExceptions(timeWindow: Long, healthAlerts: HealthRule) {
        val connectExceptions = healthMetricCounter.getStat(SOCKET_CHANNEL_CONNECT_EXCEPTION(), timeWindow)
        val state = healthClassifier.determineHealthSocketChannelConnectExceptions(connectExceptions)
        healthAlerts.updateAlert(state)
    }

    private fun HealthRule.updateAlert(healthState: HealthState) {
        when (healthState) {
            is BadHealth -> recordBadHealthSample()
            else -> resetBadHealthSampleCount()
        }
    }

    private fun isInProlongedBadHealth(): Boolean {
        var badHealthFlag = false

        healthRules.forEach {
            if (it.shouldAlertBadHealth()) {
                badHealthFlag = true
            }
        }

        return badHealthFlag
    }

    private fun temporarySimulatedHealthCheck() {
        if (simulatedGoodHealth == true) {
            Timber.i("Pretending good health")
            tunReadAlerts.resetBadHealthSampleCount()
        } else if (simulatedGoodHealth == false) {
            Timber.i("Pretending bad health")
            for (i in 0..40) {
                tunReadAlerts.recordBadHealthSample()
            }
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
