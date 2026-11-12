# YiJi（一几）

基于 BitChord 改的 Android 音乐播放器，GPLv3。
上游：https://github.com/kushagrasinghx/BitChord
个人修改版，和原作者无关。

## 源码

发布页那个 APK 的源码就在这里。后端、网页、部署文档不放。

按 GPLv3 可以随便跑、改、再发。再发时保留许可和版权声明，注明改动，附上源码。
本仓库按 GPLv3 发布，改动看提交记录。

名字、图标、界面素材不在这个授权里。二维码和群号都是占位。

## 构建

Android Studio + JDK 17 + SDK 36。
根目录建 local.properties 写 sdk.dir，把 keystore.properties.example 复制成
keystore.properties 填上自己的签名，然后：

    ./gradlew :app:packageProdRelease

自己密钥打的包和发布版签名不同，要先卸载再装。密钥不在仓库里。

## 隐私

这个源码版统计是关的。UsageStats.kt 里地址是占位、证书 pin 为空，没配就不发请求。
应用内那份隐私政策写的是发布版行为。

更新检查每次启动最多问一次 GitHub 有没有新版，只比数字，点了用浏览器打开，
应用自己不下载也不安装。

## 发版

tag 用 v63 这种整数，同时把 build.gradle.kts 里的 bitchordReleaseRev 改成一样的数。
第三方声明见 NOTICE.md。
