package dev.yikuo.youcommuter.observation

import android.content.Context
import java.util.UUID

/** Field instrumentation is excluded from release builds. */
@Suppress("UNUSED_PARAMETER")
fun borrowObservationLauncher(context: Context): ((UUID?) -> Unit)? = null
