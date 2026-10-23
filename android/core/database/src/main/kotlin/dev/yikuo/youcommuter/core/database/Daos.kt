package dev.yikuo.youcommuter.core.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
internal interface RouteDao {
    @Query("SELECT * FROM routes ORDER BY name COLLATE NOCASE, id")
    fun observeAll(): Flow<List<RouteEntity>>

    @Upsert
    suspend fun upsert(route: RouteEntity)

    @Query("DELETE FROM routes WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
internal interface SessionDao {
    @Query("SELECT * FROM commute_session WHERE singletonId = 1")
    fun observe(): Flow<SessionEntity?>

    @Query("SELECT * FROM commute_session WHERE singletonId = 1")
    suspend fun get(): SessionEntity?

    @Upsert
    suspend fun upsert(session: SessionEntity)

    @Query("DELETE FROM commute_session WHERE singletonId = 1")
    suspend fun clear()
}
