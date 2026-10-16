# Retain mapped code locations without disabling production obfuscation.
-keepattributes SourceFile,LineNumberTable

# Native TDLib looks these names up through JNI, including its nested log callback.
-keep class org.drinkless.tdlib.JsonClient { *; }
-keep class org.drinkless.tdlib.JsonClient$* { *; }

# Room loads generated database implementations by class name. Consumer rules
# cover its annotations and converters; preserve this app's generated entry point.
-keep class app.televault.data.DriveDatabase_Impl { *; }

# WorkManager persists class names in its database across app upgrades. Keep every app
# worker's identity and reflection constructor stable even in obfuscated production builds.
-keep,allowoptimization class app.televault.data.** extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
