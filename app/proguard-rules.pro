# CameraX 的入口（沿用早期的规则，minify 打开后它是真需要的）
-keep class androidx.camera.** { *; }

# --- kotlinx-serialization -------------------------------------------------
# 一瞬配方走的是 `Json.encodeToString(YishunRecipe)`，序列化器是编译器为每个 @Serializable
# 生成的 `类名$$serializer`，**代码里没有任何一处显式引用它**——R8 眼里的「死代码」。
# 被削掉的后果不是崩溃，而是运行时 `SerializationException`：导出成功但配方写不进去，
# 换台手机/换个端就打不开——正是产品最核心的承诺「配方随照片走」。
# 所以这几条是功能正确性规则，不是保险丝。
-dontnote kotlinx.serialization.**
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.suipai.**$$serializer { *; }
-keepclassmembers class com.suipai.** {
    *** Companion;
}
-keepclasseswithmembers class com.suipai.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep class com.suipai.core.engine.YishunRecipe { *; }
-keep class com.suipai.core.sync.** { *; }

# app 模块自己的 @Serializable（自定义配方，data/CustomLooks.kt）。
# 与上面 shared 那几条的区别：这里的类在**应用模块**里，字段名就是 JSON 的键名，
# 被 R8 改掉之后写出去的文件读不回来 —— 症状是"存了但没了"，release 独有的静默失效。
# 注意只有 CustomLook 走 kotlinx-serialization：回收站/收藏夹/搜索索引各自手写了
# TinyJson/SearchJson（为了坏文件容错），**不要**给它们加 keep，白丢混淆收益。
-keep class com.suipai.app.data.CustomLook { *; }
-keep class com.suipai.app.data.CustomLooks$Store { *; }

# --- 反射入口 --------------------------------------------------------------
# FileProvider 的授权路径表、以及 manifest 里直接点名的 Application/Activity
# 由 AGP 自动生成 keep 规则，这里只补一处：互通层用 java.net 手写 socket，
# 帧类型枚举一旦改名会让对端解不出来（协议是跨实现的，见 SYNC.md）。
-keepclassmembers enum com.suipai.core.sync.** { *; }
