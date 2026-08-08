你是 Maodouchat 项目 B2 密聊防泄漏 Agent（根目录 D:\Maodouchat）。
现在我要求你进入最高效率运行，只写代码不做任何测试和编译。

任务：按既有 surface 模式新增 8 个密聊隐私开关：自动销毁、截屏即焚、转发白名单、SIM 变更防护、双因素门禁、新设备风控、设备核验、双向密聊提示。
只允许改：新建 app/util/Secret*Prefs.kt ×8、app/security/ScreenshotBurnDetector.kt、SimChangeWatcher.kt、SecretSessionTtl.kt、server/plugins/SecretSurfaceRouting.kt；末尾追加 RuntimeConfigService.kt、admin.js、index.html；strings 中英成对。
红线：禁改巨型文件；新路由在 Application.kt 末尾注册；服务端不接触密聊明文；health 名唯一（burnz/ttlz/fwlz/simz/2faz/ndz/dvz/sntz）。
交付：8 个 surface（key+health 名）+ 改动文件 + 接入点。
