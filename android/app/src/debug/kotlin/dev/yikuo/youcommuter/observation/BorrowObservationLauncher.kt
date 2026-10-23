package dev.yikuo.youcommuter.observation

import android.content.Context
import android.content.Intent
import java.util.UUID

fun borrowObservationLauncher(context: Context): ((UUID?) -> Unit) = { sessionId ->
    context.startActivity(Intent(context, BorrowObservationActivity::class.java)
        .apply { sessionId?.let { putExtra(BorrowObservationService.ExtraSession, it.toString()) } })
}
