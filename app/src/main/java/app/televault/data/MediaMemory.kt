package app.televault.data

import android.app.ActivityManager
import android.content.Context

/** Budgets decoded pixels separately from native buffers; never weakens the recovery KDF. */
internal object MediaMemory {
    fun budget(context: Context, normalMb: Int, lowMb: Int): Int {
        val manager = context.getSystemService(ActivityManager::class.java)
        return (if (manager.isLowRamDevice || manager.memoryClass < 192) lowMb else normalMb) * 1024 * 1024
    }
}
