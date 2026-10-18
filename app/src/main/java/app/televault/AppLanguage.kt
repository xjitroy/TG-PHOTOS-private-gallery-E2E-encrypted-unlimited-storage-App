package app.televault
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale
internal object AppLanguage {
    fun context(base: Context): Context {
        val explicit = if (Build.VERSION.SDK_INT >= 33) (base.getSystemService(android.app.LocaleManager::class.java)?.applicationLocales ?: LocaleList.getEmptyLocaleList()) else LocaleList.getEmptyLocaleList()
        val region = base.resources.configuration.locales[0].country
        val english = if (region.length == 2) Locale.Builder().setLanguage("en").setRegion(region).build() else Locale.ENGLISH
        val locales = if (explicit.isEmpty) LocaleList(english) else explicit
        Locale.setDefault(locales[0])
        return base.createConfigurationContext(Configuration(base.resources.configuration).apply { setLocales(locales) })
    }
}
