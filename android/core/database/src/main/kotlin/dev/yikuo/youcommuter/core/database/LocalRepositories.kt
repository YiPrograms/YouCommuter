package dev.yikuo.youcommuter.core.database

import androidx.room.withTransaction
import dev.yikuo.youcommuter.core.domain.CommuteCommand
import dev.yikuo.youcommuter.core.domain.CommuteReducer
import dev.yikuo.youcommuter.core.domain.CommuteSessionRepository
import dev.yikuo.youcommuter.core.domain.InvalidCommuteTransition
import dev.yikuo.youcommuter.core.domain.RouteRepository
import dev.yikuo.youcommuter.core.domain.StaleCommuteCommand
import dev.yikuo.youcommuter.core.model.CommuteRoute
import dev.yikuo.youcommuter.core.model.CommuteSession
import dev.yikuo.youcommuter.core.model.isTerminal
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

internal class LocalRouteRepository(
    private val dao: RouteDao,
    private val mapping: PersistenceMapping,
) : RouteRepository {
    override fun observeRoutes(): Flow<List<CommuteRoute>> =
        dao.observeAll().map { routes -> routes.map(mapping::routeFromEntity) }

    override suspend fun save(route: CommuteRoute) = dao.upsert(mapping.routeToEntity(route))

    override suspend fun delete(id: UUID) = dao.delete(id.toString())
}

internal class LocalCommuteSessionRepository(
    private val database: YouCommuterRoomDatabase,
    private val dao: SessionDao,
    private val mapping: PersistenceMapping,
) : CommuteSessionRepository {
    override fun observeSession(): Flow<CommuteSession?> =
        dao.observe().map { it?.let(mapping::sessionFromEntity) }

    override suspend fun start(route: CommuteRoute, at: Instant): CommuteSession = database.withTransaction {
        val current = dao.get()?.let(mapping::sessionFromEntity)
        if (current != null && !current.phase.isTerminal) {
            throw InvalidCommuteTransition("Finish or cancel the active commute first")
        }
        CommuteReducer.start(route, UUID.randomUUID(), at).also {
            dao.upsert(mapping.sessionToEntity(it, lastCommandId = null))
        }
    }

    override suspend fun dispatch(sessionId: UUID, command: CommuteCommand): CommuteSession = database.withTransaction {
        val entity = dao.get() ?: throw InvalidCommuteTransition("There is no active commute")
        val current = mapping.sessionFromEntity(entity)
        if (current.id != sessionId) throw StaleCommuteCommand()
        if (entity.lastCommandId == command.id.toString()) return@withTransaction current
        CommuteReducer.reduce(current, sessionId, command).also {
            dao.upsert(mapping.sessionToEntity(it, lastCommandId = command.id))
        }
    }

    override suspend fun clearTerminal() = database.withTransaction {
        val current = dao.get()?.let(mapping::sessionFromEntity) ?: return@withTransaction
        if (!current.phase.isTerminal) throw InvalidCommuteTransition("Cancel the active commute first")
        dao.clear()
    }
}
