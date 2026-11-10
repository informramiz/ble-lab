package com.codebyramiz.blelab.data

import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey

@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAtMillis: Long,
    val endedAtMillis: Long?,
    val deviceName: String?,
    val deviceAddress: String,
)

@Entity(
    tableName = "telemetry",
    foreignKeys = [ForeignKey(SessionEntity::class, parentColumns = ["id"], childColumns = ["sessionId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("sessionId")],
)
data class TelemetryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val tMillis: Long,
    val value: Float,
    val ledOn: Boolean,
)

@Entity(
    tableName = "motion",
    foreignKeys = [ForeignKey(SessionEntity::class, parentColumns = ["id"], childColumns = ["sessionId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("sessionId")],
)
data class MotionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val tMillis: Long,
    val ax: Float,
    val ay: Float,
    val az: Float,
    val gx: Float,
    val gy: Float,
    val gz: Float,
)

/** Session row joined with its sample counts. */
data class SessionSummaryRow(
    val id: Long,
    val startedAtMillis: Long,
    val endedAtMillis: Long?,
    val deviceName: String?,
    val deviceAddress: String,
    val telemetryCount: Int,
    val motionCount: Int,
)
