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

# Native OCR bridge and public Tesseract JNI entry points.
-keep class app.televault.ocr.OcrScript { *; }
-keep class com.googlecode.tesseract.android.** { *; }

# OCR also exposes Leptonica JNI wrapper names and a persisted WorkManager identity.
-keep class com.googlecode.leptonica.android.** { *; }
-keep,allowoptimization class app.televault.ocr.OcrModelsWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# ONNX JNI names and persistent model-download worker identity.
-keep class ai.onnxruntime.** { *; }
-keep,allowoptimization class app.televault.people.FaceModelsWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# Retain the optional on-device Memories model worker across upgrades.
-keep,allowoptimization class app.televault.memories.MemoryModelsWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
