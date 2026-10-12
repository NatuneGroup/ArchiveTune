# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.kts.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# Move all classes that R8 is allowed to move into a single 'r8' package.
# This shortens class name strings in the DEX constant pool and removes
# per-package directory entries, reducing DEX size by ~1–3%. It does NOT
# rename or move any class covered by a -keep rule (media3, tdlib, kuromoji,
# jaudiotagger, newpipe.extractor, ktor, guava, Glance widgets, queue
# persistence models, @Serializable companions). Reflection by system class
# name (e.g. Class.forName("android.os.SystemProperties")) is unaffected.
# Using a non-empty package name ('r8') avoids edge cases with the default
# package that some class loaders trip over.
-repackageclasses 'r8'

## Kotlin Serialization
# Keep `Companion` object fields of serializable classes.
# This avoids serializer lookup through `getDeclaredClasses` as done for named companion objects.
-if @kotlinx.serialization.Serializable class **
-keepclasseswithmembers class <1> {
    static <1>$Companion Companion;
}

# Keep `serializer()` on companion objects (both default and named) of serializable classes.
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclasseswithmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep `INSTANCE.serializer()` of serializable objects.
-if @kotlinx.serialization.Serializable class ** {
    public static ** INSTANCE;
}
-keepclasseswithmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

# @Serializable and @Polymorphic are used at runtime for polymorphic serialization.
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault

## Markwon — optional GIF support (android-gif-drawable) not bundled
-dontwarn pl.droidsonroids.gif.**

-dontwarn javax.servlet.ServletContainerInitializer
-dontwarn org.bouncycastle.jsse.BCSSLParameters
-dontwarn org.bouncycastle.jsse.BCSSLSocket
-dontwarn org.bouncycastle.jsse.provider.BouncyCastleJsseProvider
-dontwarn org.conscrypt.Conscrypt$Version
-dontwarn org.conscrypt.Conscrypt
-dontwarn org.conscrypt.ConscryptHostnameVerifier
-dontwarn org.openjsse.javax.net.ssl.SSLParameters
-dontwarn org.openjsse.javax.net.ssl.SSLSocket
-dontwarn org.openjsse.net.ssl.OpenJSSE
-dontwarn org.slf4j.impl.StaticLoggerBinder

## Rules for NewPipeExtractor
# The MetrolistExtractor artifact contains no Class.forName / field or method
# reflection at all (verified against the shipped jar), so it needs no blanket
# keep and shrinks/obfuscates like any other library. The two targeted keeps
# below stay: the YouTube protos are protobuf-javalite generated code whose
# RawMessageInfo resolves field names reflectively at first use, and the timeago
# pattern classes are loaded by dynamically built class names from PatternsManager.
# (ServiceList's enum constants reference every shipped service's constructor, so
# the non-YouTube services stay reachable through the enum either way.)
-keep class org.schabi.newpipe.extractor.services.youtube.protos.** { *; }
-keep class org.schabi.newpipe.extractor.timeago.patterns.** { *; }
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.javascript.engine.** { *; }
-keep class org.mozilla.classfile.ClassFileWriter
-dontwarn org.mozilla.javascript.JavaToJSONConverters
-dontwarn org.mozilla.javascript.tools.**
# org.mozilla.javascript.ObjToIntMap was removed in Rhino 1.9.x.
# MetrolistExtractor's org.schabi.newpipe.extractor.utils.jsextractor.TokenStream
# still references it, but that jsextractor path is dead code at runtime — the
# morideobfuscator's RhinoTransformExecutor performs all JS execution using the
# modern Rhino API directly. Suppress the R8 "Missing class" error so the
# release minify task doesn't fail.
-dontwarn org.mozilla.javascript.ObjToIntMap
-keep class javax.script.** { *; }
-dontwarn javax.script.**
-keep class jdk.dynalink.** { *; }
-dontwarn jdk.dynalink.**

## Essential for reflection/deserialization
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes EnclosingMethod
-keepattributes InnerClasses

## Logging (does not affect Timber)
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    ## Leave in release builds
    #public static int i(...);
    #public static int w(...);
    #public static int e(...);
}

# Generated automatically by the Android Gradle plugin.
-dontwarn java.beans.BeanDescriptor
-dontwarn java.beans.BeanInfo
-dontwarn java.beans.IntrospectionException
-dontwarn java.beans.Introspector
-dontwarn java.beans.PropertyDescriptor
-dontwarn java.lang.management.**

# Keep all classes within the kuromoji package
-keep class com.atilika.kuromoji.** { *; }

## Queue Persistence Rules
# Keep queue-related classes to prevent serialization issues in release builds
-keep class moe.rukamori.archivetune.models.PersistQueue { *; }
-keep class moe.rukamori.archivetune.models.PersistPlayerState { *; }
-keep class moe.rukamori.archivetune.models.QueueData { *; }
-keep class moe.rukamori.archivetune.models.QueueType { *; }
-keep class moe.rukamori.archivetune.playback.queues.** { *; }

# Java serialization writes the CLASS NAME and the FIELD NAMES into the stream, so R8 renaming
# either of them breaks reading a file written by an earlier build. That is what produced
#   W/MusicService: Failed to read persistent file: persistent_queue.data
#   java.io.InvalidClassException: r8.hg7; class invalid for deserialization
# on every update: the saved queue named a class by its obfuscated name, and in the new build that
# name belonged to something else. The queue was silently lost each time.
#
# SCOPED TO THIS APP'S OWN CLASSES, and it must stay that way. The first attempt at this used
# `class *`, and java.lang.Throwable implements Serializable — so it matched every exception class
# in the app and in every library it depends on. Keeping names and fields across that whole surface
# changed R8's naming and merging decisions enough that it emitted a class the ART verifier
# rejects outright:
#   java.lang.VerifyError: Verifier rejected class r8.my3 ...
#   'this' argument 'Uninitialized Reference: org.json.JSONException'
#       not instance of 'Reference: java.lang.RuntimeException'
# — two unrelated exception types horizontally merged into one class. The app died on launch.
#
# Only this app's models are ever written with Java serialization, so only they need the rule.
-keepnames class moe.rukamori.archivetune.** implements java.io.Serializable
-keepclassmembers class moe.rukamori.archivetune.** implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    !static !transient <fields>;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

## Guava (pulled in by Media3)
# No blanket keep: Guava is only reflected on internally, at field updaters whose
# field names must survive (AtomicReferenceFieldUpdater/Unsafe offsets). These are
# exactly the rules Guava publishes in META-INF/proguard of its -jre artifact; the
# -android variant this build resolves ships none, so they are inlined here.
# Everything else in Guava is free to shrink and obfuscate.
-keepclassmembers class com.google.common.** {
    volatile <fields>;
}
-keepclassmembernames class com.google.common.** {
    volatile <fields>;
}
-keepclassmembers class com.google.common.cache.Striped64 {
    *** base;
    *** busy;
}
-keepclassmembers class com.google.common.cache.Striped64$Cell {
    <fields>;
}
-dontwarn com.google.common.**

## Media3
# No blanket keep: every media3 AAR ships consumer rules covering the reflection
# media3 itself performs (decoder-extension renderer constructors, downloader
# factories, ExoPlayer scrubbing-mode members, Guava Function member names), and
# manifest-referenced media3 classes (PlatformScheduler$PlatformSchedulerService,
# MediaButtonReceiver) are pinned by the keep rules aapt2 generates from the
# manifest. The app's own reflective lookups
# (MusicService.updateAudioOffload, ExoPlayerExtensions.setOffloadEnabled) probe
# members by literal name and no-op silently when absent; R8's identifier-name-string
# handling pins literal getMethod/forName targets automatically.
-dontwarn androidx.media3.**

## JAudioTagger - suppress missing AWT/ImageIO classes (not available on Android)
-dontwarn java.awt.**
-dontwarn javax.imageio.**
-dontwarn javax.swing.**
-keep class org.jaudiotagger.** { *; }

## Jetpack Glance
# Keep ActionCallback implementations and their no-arg constructors
-keep class * implements androidx.glance.appwidget.action.ActionCallback {
    public <init>();
}
-keep class * implements androidx.glance.action.ActionCallback {
    public <init>();
}
# Keep GlanceAppWidget and its receiver
-keep class * extends androidx.glance.appwidget.GlanceAppWidget { *; }
-keep class * extends androidx.glance.appwidget.GlanceAppWidgetReceiver { *; }

# internal Ktor HTTP Client
# No blanket keep: these are the rules Ktor itself ships in
# ktor-utils META-INF/proguard/ktor.pro, inlined so shrinking of everything else
# in io.ktor is no longer suppressed. AtomicFU-backed volatile fields are updated
# by name and must not be renamed; client engines are discovered via ServiceLoader.
-keepclassmembers class io.ktor.** {
    volatile <fields>;
}
-keepclassmembernames class io.ktor.** {
    volatile <fields>;
}
-keep class io.ktor.client.engine.** implements io.ktor.client.HttpClientEngineContainer {
    public <init>();
}
-dontwarn io.ktor.**

# engine HTTP Android/OkHttp Ktor
-dontwarn kotlinx.coroutines.**

# TDLib (Telegram) — JNI bridges into these classes by reflection; must not be renamed/stripped
-keep class org.drinkless.tdlib.** { *; }
-dontwarn org.drinkless.tdlib.**

## Listen Together — protobuf-javalite wire protocol
# javalite 4.x generated code passes the message field NAMES as strings
# ("username_", "room_code_", ...) into RawMessageInfo, and the
# com.google.protobuf.MessageSchema runtime resolves each of them through
# Class.getDeclaredField() + Unsafe.objectFieldOffset() when the message class
# is first used. If R8 renames those fields, every protobuf encode fails with
#   Error encoding message -- create_room: Field username_ for r8.ox5 not found.
#   Known fields are [public static final r8.ox5 r8.ox5.c, ...]
# (r8.ox5 = CreateRoomPayload after -repackageclasses). The JSON codec is
# unaffected because kotlinx.serialization resolves fields at compile time,
# and debug builds are not minified — so this only reproduced in release/nightly
# APKs, where create_room/join_room could never be encoded and no room code
# ever arrived. Mirrors vivi-music's listentogether keep rules (proven against
# the metroserver / The Meowery instance).
-keep class moe.rukamori.archivetune.listentogether.** { *; }
-keepclassmembers class moe.rukamori.archivetune.listentogether.** { *; }
