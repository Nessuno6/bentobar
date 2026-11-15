# kotlinx.serialization keeps its generated serializers via its own consumer rules.
# Keep the accessibility service (referenced from the manifest only). The tile services and the other manifest
# components are kept by the rules AGP generates from the manifest.
-keep class com.meridian11.books.bentobar.bar.BarService { *; }

# Run by name as root through app_process (items/RootTool): nothing in the app calls it.
-keep class com.meridian11.books.bentobar.items.RootTool { public static void main(java.lang.String[]); }

# Device batteries' link (items/Link): Google's Cross device SDK and Play services' parcelables, read by reflection.
-keep class com.google.ambient.crossdevice.** { *; }
-keep class com.google.android.gms.dtdi.** { *; }
-dontwarn com.google.ambient.crossdevice.**
