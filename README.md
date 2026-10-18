# YSMSync — Paper 26.1.2 版

Yes Steve Model（YSM）服务端同步插件，适配 **Paper 26.1.2**。

旧版：[https://github.com/memzgbl/ysmsync/ol](https://github.com/MEMZGBL/YSMSync/blob/main/oldver/YSMSync-GitHub1.0.0.zip)（面向 Paper/Purpur 1.19.4）。

---

## 重要：版本对应关系

| 组件 | 必须是 |
| --- | --- |
| 服务端 | **Paper 26.1.2**（`26.1.2.build.74-stable`） |
| 服务端 Java | **Java 25** |
| 客户端 Minecraft | **26.1.2** |
| 客户端模组 | **Yes Steve Model 26.1.2** |

>  YSM 客户端模组目前最高只支持到 Minecraft **26.1.2**，26.2 / 26.3 都没有可用的客户端版本。
> 所以请不要把服务端升到 26.2 或更高，否则玩家装了 YSM 也连不上。

客户端模组的协议通道是 `yes_steve_model:2_6_0`，与原 1.20.1 版本一致。

---

## 安装

1. 确认服务端是 Paper 26.1.2，并且用 Java 25 启动。
2. 把构建产物 `build/libs/YSMSync-1.0.2-paper26.1.2.jar` 复制到服务端的 `plugins/` 目录。
3. 启动服务器。日志里应出现：

   ```
   [YSMSync] All models has been loaded! Currently has N models loaded.
   [YSMSync] YSM web portal: http://0.0.0.0:25584, native sync recheck=30s
   ```

4. **不需要 ProtocolLib**（本移植版已改用原生 Netty 实现，老教程里要求装 ProtocolLib 的说法已失效）。

第一次启动后会生成：

```text
plugins/YSMSync/config.yml       插件配置
plugins/YSMSync/models/custom/   放 .ysm 模型文件（玩家可选用）
plugins/YSMSync/models/builtin/  内置模型
plugins/YSMSync/models/auth/     授权模型
plugins/YSMSync/caches/          模型缓存（自动生成）
plugins/YSMSync/player_data/     玩家数据
```

放好模型后执行 `/ysmsync reload` 让插件重新扫描。

> 不要同时加载其他 YSM 同步类插件，会导致数据目录或协议处理冲突。

---

## 命令

```text
/ysmsync reload                          重载模型
/ysmsync setmodel <玩家> <模型ID> [材质]   给玩家设置模型
```

需要权限 `ysmsync.commands.reloadmodels`（默认 OP）。

---

## Web 模型管理门户

默认监听 `0.0.0.0:25584`，浏览器打开 `http://服务器IP:25584/` 即可上传模型和图片。

**对外开放前请务必配置 HTTPS 反向代理并限制访问范围**，它默认没有 TLS。

接口文档见 `WEB_API.md`。

### 端口被占用怎么办（重要）

如果 25584 端口已被别的程序占用（或你同时开了两个服务端实例），插件会打印：

```
[YSMSync] 无法在 0.0.0.0:25584 启动 YSM Web 门户（Address already in use）。
          模型同步功能不受影响，如需网页管理请修改 config.yml 的 web-portal.port，
          或设置 web-portal.enabled=false 关闭该提示。
```

此时**模型同步照常工作**，只是网页管理不可用。想恢复网页管理，二选一：

```yaml
web-portal:
  port: 25585        # 换一个没被占用的端口
  # 或者干脆关掉
  # enabled: false
```

> 旧版本（v1.0.2 之前的构建）在端口被占用时会把整个插件禁用，
> 表现为玩家进服后模型完全不同步、`/ysmsync reload` 报
> `IllegalPluginAccessException`。本版已修复为只降级 Web 门户。

---

## 配置要点

`plugins/YSMSync/config.yml` 可调整模型同步限速、Web 门户端口等。改完执行 `/ysmsync reload`。

---

## 从源码构建

需要 **JDK 25**（paper-api 26.1.2 是 Java 25 字节码，JDK 21 会直接报错）和 Gradle 9.x。

```bash
# 从你的服务端目录复制编译所需的 NMS jar 与同版本 Netty
# 注意：文件若放在 Android 存储（/sdcard）上没有执行权限位，用 bash 调起
bash prepare-libs.sh /path/to/paper-26.1.2.jar

JAVA_HOME=/path/to/jdk-25 gradle build
```

产物：`build/libs/YSMSync-1.0.2-paper26.1.2.jar`

`libs/` 里已内置 `ysm-core-pure.jar`（从 YSM 模组剥离的纯数据逻辑，107 个类，零 Minecraft 引用）。

---

## 移植说明

改造点、技术细节与实测验证记录见 **`PORTING-26.1.2.md`**。

简要概括：剥离 YSM 模组依赖为独立 jar、去掉 ProtocolLib 改用原生 Netty 负载桥、
去掉 MorePaperLib 改用 Paper 原生调度器、适配 26.x 的 `Identifier` 与 Java 25 字节码。

---

## 已验证

在真实 Paper `26.1.2.build.74-stable` 上实测通过：插件加载、`.ysm` 模型解析
（crypto v2 与 v3 两种格式）、缓存生成、`/ysmsync reload`、Web 门户，
以及自定义负载的**双向**收发（用自写的最小协议客户端验证到
`Received handshake confirmed packet … Begin model synchronization` 与后续模型同步数据流）。

---

## 声明

该插件是基于 https://github.com/EilsapMC/Noir 开发的非官方衍生项目。
核心 YSM 桥接代码来源于 Noir，经过原作者与 AI 修改。
本项目与 CloudXmc、Noir 原作者及 YSM 官方没有合作关系。
本项目及其衍生代码按照 GNU GPL v3.0 发布。
