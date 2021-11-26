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

import androidx.collection.LruCache
import com.duckduckgo.mobile.android.vpn.health.TracerPacketRegister.TracerSummary.Completed
import com.duckduckgo.mobile.android.vpn.health.TracerPacketRegister.TracerSummary.Invalid
import timber.log.Timber
import java.text.NumberFormat
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TracerPacketRegister @Inject constructor() {

    private val tracers: LruCache<String, Tracer> = LruCache(1000)

    fun logEvent(event: TracerEvent) {
        Timber.v("Registering %s for tracer %s", event.event, event.tracerId)

        val id = event.tracerId
        addEvent(id, event)

        Timber.e("state for tracer packet %s\n\n%s", id, getTrace(id))
    }

    @Synchronized
    private fun addEvent(id: String, event: TracerEvent) {
        val tracer = getTracer(id)
        val events = mutableListOf<TracerEvent>().also {
            it.addAll(tracer.events)
            it.add(event)
        }
        tracers.put(id, tracer.copy(events = events))
        // tracers[id] = events
    }

    private fun getTracer(id: String): Tracer {
        val existing = tracers[id]
        if (existing != null) return existing

        Timber.i("no existing list exists for tracer %s, creating new one", id)

        val tracer = Tracer(tracerId = id, timestampNanos = System.nanoTime(), events = emptyList())
        tracers.put(id, tracer)
        return tracer
    }

    fun getAllTraces(timeWindowNanos: Long): List<TracerSummary> {
        return tracers.snapshot()
            .map {
                categorize(it.value, it.key)
            }
            .filter { it.timestampNanos >= timeWindowNanos }
    }

    fun getTrace(tracerId: String): TracerSummary {
        val tracer = tracers[tracerId] ?: return Invalid(tracerId, 0, "Not found")
        return categorize(tracer, tracerId)
    }

    private fun categorize(tracer: Tracer, tracerId: String): TracerSummary {
        val startTime = tracer.timestampNanos
        val firstEvent = tracer.events.firstOrNull() ?: return Invalid(tracerId, startTime, "No CREATED timestamp available; invalid")

        if (firstEvent.event != TracedState.CREATED) {
            return Invalid(
                tracerId,
                startTime,
                String.format("First event for tracer %s is not CREATED; it is %s", tracerId, firstEvent.event)
            )
        }

        // todo do we need to validate if it successfully reached the "end" ?

        val totalTime = tracer.events.last().timestampNanos - startTime
        return Completed(tracerId, startTime, totalTime, tracer.events)
    }

    fun deleteAll() {
        tracers.evictAll()
    }

    sealed class TracerSummary(open val tracerId: String, open val timestampNanos: Long) {
        data class Completed(
            override val tracerId: String,
            override val timestampNanos: Long,
            val timeToCompleteNanos: Long,
            val events: List<TracerEvent>
        ) : TracerSummary(tracerId, timestampNanos) {
            override fun toString(): String {

                return StringBuilder(
                    String.format(
                        "Detailing tracer flow for %s. %d steps in flow. Total time: %s (%s)\n",
                        tracerId,
                        events.size,
                        formatNanosAsMillis(timeToCompleteNanos),
                        formatNanos(timeToCompleteNanos)
                    )
                ).also { sb ->
                    sb.append(String.format("---> CREATED at %d", timestampNanos))

                    events
                        .filter { it.event != TracedState.CREATED }
                        .forEach {
                            val durationFromCreation = it.timestampNanos - timestampNanos

                            sb.append("\n")

                            sb.append(
                                String.format(
                                    "---> %s happened %s (%s) after it was first created (absolute time=%d)",
                                    it.event,
                                    formatNanosAsMillis(durationFromCreation),
                                    formatNanos(durationFromCreation),
                                    it.timestampNanos
                                )
                            )
                        }

                    sb.append("\n\n")
                }.toString()
            }
        }

        data class Invalid(override val tracerId: String, override val timestampNanos: Long, val reason: String) : TracerSummary(tracerId, timestampNanos) {
            override fun toString(): String {
                return String.format("TracerSummary for %s. Invalid: %s", tracerId, reason)
            }
        }
    }

    companion object {
        private val numberFormatter = NumberFormat.getNumberInstance().also { it.maximumFractionDigits = 2 }

        private fun formatNanosAsMillis(durationNanos: Long): String {
            return String.format("%s ms", numberFormatter.format(durationNanos / 1_000_000L.toDouble()))
        }

        private fun formatNanos(durationNanos: Long): String {
            return String.format("%s ns", numberFormatter.format(durationNanos))
        }
    }
}
