package dev.yikuo.youcommuter.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import dev.yikuo.youcommuter.core.domain.CommuteSessionRepository
import dev.yikuo.youcommuter.core.domain.RouteRepository

@Database(
    entities = [RouteEntity::class, SessionEntity::class],
    version = 1,
    exportSchema = true,
)
internal abstract class YouCommuterRoomDatabase : RoomDatabase() {
    abstract fun routes(): RouteDao
    abstract fun sessions(): SessionDao
}

data class LocalRepositorySet(
    val routes: RouteRepository,
    val sessions: CommuteSessionRepository,
)

object YouCommuterDatabase {
    fun create(context: Context): LocalRepositorySet {
        val database = Room.databaseBuilder(
            context.applicationContext,
            YouCommuterRoomDatabase::class.java,
            "youcommuter.db",
        ).build()
        val mapping = PersistenceMapping()
        return LocalRepositorySet(
            routes = LocalRouteRepository(database.routes(), mapping),
            sessions = LocalCommuteSessionRepository(database, database.sessions(), mapping),
        )
    }
}
