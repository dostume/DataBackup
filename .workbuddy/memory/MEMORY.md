# 项目长期记忆

- 项目：dostume/DataBackup（XayahSuSuSu/Android-DataBackup 的 fork），本地路径 `D:\代码\opencode\backupapk\repo`，源码在 `source/` 子目录，git 仓库根在 `repo/`。
- 构建：GitHub Actions `.github/workflows/build-apk.yml`，产物为 arm64 FOSS release APK，用 gradle 8.13（工作树无 gradle-wrapper.jar，勿依赖 ./gradlew）。
- push 时必须绕过 helper-selector：`git -c credential.helper= -c credential.helper=manager push origin main`（凭证存在 GCM，用户 dostume）。
- fork 自研功能：volume 分卷备份（busybox split + 流式上传），勿当上游代码比对。
