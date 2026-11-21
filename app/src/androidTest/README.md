# TV 快照对账设备测试

仅在新建的独立 Android TV 模拟器上执行。测试使用唯一前缀的临时 SQLite 数据库和偏好设置，并注入模拟快照页面；不连接 Hub，也不访问用户数据库。

在仓库根目录构建（需要 Android SDK 和 JDK 17）：

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest --offline
```

确认目标序列号是专门创建的隔离模拟器，以下以 `emulator-5562` 为例：

```sh
adb -s emulator-5562 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5562 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5562 shell am instrument -w -r -e class com.nova.tv.WatchSnapshotInstrumentedTest com.nova.tv.test/androidx.test.runner.AndroidJUnitRunner
```

检查输出中 `OK (5 tests)`；不要只依赖 adb 退出码。模拟响应覆盖快照重建逻辑及 SQLite，未覆盖真实 HTTP、设备绑定、真实 Hub 服务或播放器。

# 版本检查设备测试

同样只使用独立 TV 模拟器；安装上述两个 APK 后运行：

```sh
adb -s <独立模拟器序列号> shell am instrument -w -r -e class com.nova.tv.ReleaseUpdateInstrumentedTest com.nova.tv.test/androidx.test.runner.AndroidJUnitRunner
```

检查输出 `OK (8 tests)`。测试在设备内启动本地 HTTP 服务，验证启动请求、顶层红点以及设置页焦点移动；另用 Compose 页面验证红点、遥控确认及失败提示。系统打开网页的 Intent 由测试监视器拦截并核对，无浏览器异常通过回调注入；不验证浏览器实际页面或真实 GitHub 网络。
