package io.embodit.insideorchestra.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.embodit.insideorchestra.platform.OrchLog

/**
 * Debug builds only. Forwards adb broadcasts to the running Stage (action `io.embodit.insideorchestra.DEBUG`);
 * see [DebugCommands] for the command list.
 *
 *   adb -s <serial> shell "am broadcast -a io.embodit.insideorchestra.DEBUG -p io.embodit.insideorchestra --es cmd 'place=auto play=1,pos=95 freeze=1'"
 *
 * Every extra is read as text (string, float, int and boolean extras alike).
 */
class DebugCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val bundle = intent.extras ?: return
        val extras = HashMap<String, String>()
        for (key in bundle.keySet()) {
            @Suppress("DEPRECATION") val v = bundle.get(key) ?: continue
            extras[key] = v.toString()
        }
        if (extras.isEmpty()) return
        OrchLog.i("CMD recv $extras")
        DebugCommands.run(context.applicationContext, extras)
    }
}
