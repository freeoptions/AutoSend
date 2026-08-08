# AutoTG 项目专属规则

通用规则见：`E:\@imFile-Download\AI-Useful-Prompt\通用开发工作规则.md`。

- 本项目是 Android Gradle Kotlin 项目，应用模块位于 `app`，使用项目自带 Gradle Wrapper。
- 修改 Android 代码、资源或 Gradle 配置后，默认只做静态检查或代码审查，不替我运行 Android Studio/Gradle 构建。
- 完成修改后提醒：“已经修改完，可以去 as 构建了”。
- 不要把 `local.properties`、签名文件、密钥或本地账号配置提交到仓库。
