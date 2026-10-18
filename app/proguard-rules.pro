# R8 rules for release builds. kotlinx.serialization ships its own consumer rules;
# the codec works on JsonElement trees, so no model classes need keeping.
# Keep line numbers for readable crash traces.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
