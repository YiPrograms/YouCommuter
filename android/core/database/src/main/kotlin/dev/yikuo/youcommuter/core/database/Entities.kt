package dev.yikuo.youcommuter.core.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "routes")
internal data class RouteEntity(
    @PrimaryKey val id: String,
    val name: String,
    val payload: String,
)

@Entity(tableName = "commute_session")
internal data class SessionEntity(
    @PrimaryKey val singletonId: Int = 1,
    val id: String,
    val payload: String,
    val lastCommandId: String?,
)
