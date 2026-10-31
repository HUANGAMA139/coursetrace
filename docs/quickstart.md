# 本地启动

## 环境

- Windows、PowerShell 7、Docker Desktop。
- JDK 25 与 Maven 3.9：JAVA_HOME 指向 JDK，mvn 可从 PATH 找到；项目编译目标为 Java 21。
- Node.js 24、pnpm 11.25.0。
- 千问兼容接口的有效 Key 与对应区域地址。业务模型默认值见 .env.example。
- FFmpeg / ffprobe；生成合成语音演示数据时另外需要 Windows 中文语音。

PowerShell 7 用于本地初始化等脚本；IDEA 的共享启动器使用 Windows PowerShell。普通 Node/pnpm 安装即可运行，开发脚本的额外运行时回退路径不是必需依赖。

## 首次准备

在项目根目录运行：

~~~powershell
.\scripts\prepare-local.ps1
.\scripts\install-ffmpeg.ps1
~~~

prepare-local 生成被忽略的 .env.local，保存本地数据库和 MinIO 密码，并保留已有配置。随后编辑该文件，加入：

~~~dotenv
DASHSCOPE_API_KEY=填写自己的Key
~~~

其他模型、服务或媒体路径按 .env.example 的字段配置。使用 JAVA_HOME 时不必配置 COURSETRACE_JAVA_HOME；需要指定另一个 JDK 时，将该字段设为自己的实际路径。

FFmpeg 安装脚本会输出可执行文件所在目录。默认业务路径指向本项目固定便携版；如果实际下载版本不同，在 .env.local 中设置实际的 FFMPEG_PATH 和 FFPROBE_PATH。已有本地数据卷时应沿用与其匹配的密码。

## IDEA 一键启动

打开整个项目目录，而非只打开 server。Docker Desktop 处于可用状态后：

- 运行 **CourseTrace - Start**：准备 MySQL、Redis、MinIO、Kafka、Qdrant，检查媒体工具，准备前端依赖，构建后端，启动 Java 与 Vite。
- 运行 **CourseTrace - Stop**：停止本项目已记录的开发进程，保留数据库与对象数据。

共享配置位于 .run；IDEA 需要启用 Shell scripts 插件。启动脚本成功后退出，前后端继续在后台运行。重新运行 Start 会构建并重启本项目进程。

网页：http://127.0.0.1:5173。后端健康检查：http://127.0.0.1:8080/actuator/health。

测试账号 student、student2，密码均为 CourseTrace@123。Session 在进程内保存，重启后重新登录。

## 手动启动

~~~powershell
.\scripts\docker.ps1 up -d --wait mysql redis minio kafka qdrant
.\scripts\pnpm.ps1 --dir client install --frozen-lockfile
.\scripts\maven.ps1 -DskipTests package
.\scripts\dev.ps1
~~~

停止：

~~~powershell
.\scripts\stop-dev.ps1
~~~

## 模型与数据

上传视频后显式生成字幕/笔记，再建立问答索引。模型任务和在线问答可能产生第三方 API 费用，均有应用侧执行预算。启动不主动发起收费探测，已授权的后台任务仍按原队列规则处理。

仓库不包含本地视频、数据库数据或历史问答。可以上传自己有权使用的中文口述课程；也可以使用 docs/demo 原文和样片脚本准备合成语音演示数据。

MySQL 默认端口 3307，Redis 6379，MinIO 9000/9001，Kafka 9092，Qdrant 6333/6334；具体配置见 infra/compose.yml。初次 MinIO 镜像从固定源码构建，需要下载构建依赖。

本机启动和重启已经验证；其他操作系统、新机器和生产环境需要按其实际路径、工具及网络条件验证。

## 常见问题

| 现象 | 检查与处理 |
|---|---|
| IDEA 没有显示共享启动配置 | 打开整个仓库目录，启用 Shell scripts 插件；配置位于 `.run/` |
| 提示找不到 Docker 或无法连接引擎 | 打开 Docker Desktop，等待引擎就绪，再运行 Start |
| 提示找不到 JDK、Maven 或 Node | 检查 JAVA_HOME 与 PATH；更改系统环境后重新打开 IDEA；可在 `.env.local` 指定 COURSETRACE_JAVA_HOME |
| 项目移动后前端依赖失效 | 重新运行 Start；仍失败时执行 `scripts/pnpm.ps1 --dir client install --frozen-lockfile --force` |
| FFmpeg / ffprobe 路径不可用 | 运行安装脚本，并核对 `.env.local` 中两个媒体工具路径 |
| AI 接口鉴权失败或模型不可用 | 核对自己的 Key、区域地址和模型权限；修改本地配置后重新运行 Start |
| 新上传的视频不能直接问答 | 先生成字幕与笔记，再在课程问答中建立索引；等待相关阶段完成 |
| 页面打开但登录失效 | 后端重启会清空内存 Session，重新登录即可 |
| 已有数据卷启动后数据库鉴权失败 | 使用与原数据卷匹配的本地密码；先备份数据，不要直接重置卷 |

## 仓库截图与演示材料

`docs/assets` 中的截图来自实际运行页面，展示的是已处理的自写合成语音课程。仓库不包含截图对应的数据库、媒体文件和历史问答；新环境需要按实际流程准备数据。

## 分片续传与限流

选择视频后在 Worker 中分块计算 SHA-256，上传按钮支持暂停/继续。刷新后需要重新选择原文件；本地缓存只保存会话线索，已完成分片以服务器为准。上传默认 8 MiB/片、两个并发、500 MB 总上限、24 小时有效期。暂停可以关闭弹窗，继续后只传缺失片；合并阶段停止等待不等于取消服务器保存，可通过“确认保存结果”读取完成状态。

Redis 默认沿用 Compose 的本机 127.0.0.1:6379 服务。问答、字幕笔记和索引的新工作分别有用户/全局令牌桶，参数见 .env.example 的 RATE_*；REDIS_HOST/PORT/PASSWORD 用于连接已配置的 Redis。默认本地 Compose 没有启用 Redis 密码；外部服务按其实际认证配置填写。

429 会显示等待时间，草稿保留且不会自动反复提交。Redis 不可用时新收费工作返回 503；读取、播放与分片上传仍可使用。Redis 不纳入通用健康检查的强依赖，但 IDEA 启动器会显式等待其容器健康。
