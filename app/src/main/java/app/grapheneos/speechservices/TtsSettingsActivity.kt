package app.grapheneos.speechservices

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.MenuItem
import android.widget.TextView
import android.widget.Toolbar

class TtsSettingsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.tts_settings_activity)

        val toolbar = findViewById<Toolbar>(R.id.action_bar)
        setActionBar(toolbar)
        actionBar?.apply {
            title = getString(R.string.app_name)
            setDisplayHomeAsUpEnabled(true)
            setHomeActionContentDescription(R.string.settings_navigate_up)
        }
        toolbar.navigationContentDescription = getString(R.string.settings_navigate_up)
        toolbar.setNavigationOnClickListener { finishAfterTransition() }

        findViewById<TextView>(R.id.settings_version_value).text =
            versionName().ifEmpty { getString(R.string.settings_version_unknown) }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finishAfterTransition()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onNavigateUp(): Boolean {
        finishAfterTransition()
        return true
    }
}

private fun Context.versionName(): String {
    val packageInfo = packageManager.getPackageInfo(
        packageName,
        PackageManager.PackageInfoFlags.of(0),
    )
    return packageInfo.versionName.orEmpty()
}
