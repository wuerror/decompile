# decompile

基于 Vineflower 的 Java 反编译工具，支持批量反编译指定路径下的所有 JAR/WAR 文件。

## 特性

- ✅ **Vineflower 1.9.3**: Fernflower 的现代维护版本，更好的代码还原能力
- ✅ **UTF-8 编码支持**: 中文字符正常显示
- ✅ **多线程并行处理**: 自动使用 CPU 核心数提升反编译效率
- ✅ **-tp 参数过滤**: 仅反编译包含目标包的 JAR，第三方库不移入输出目录
- ✅ **智能目录跳过**: 自动跳过 `.java-decompile`、`.woodpecker`、`target/classes` 目录
- ✅ **直读 .class**: 支持单个 `.class` 或仅包含 `.class` 的目录，结果直接写回原目录

## 快速开始

### 构建

```bash
mvn clean package
```

构建完成后在 `target/decompile-1.0-SNAPSHOT.jar`

### 使用

**1. 反编译目录下所有 JAR/WAR：**

```bash
java -jar target/decompile-1.0-SNAPSHOT.jar D:\path\to\libs
```

**2. 仅反编译包含指定包的 JAR（推荐）：**

```bash
java -jar target/decompile-1.0-SNAPSHOT.jar D:\path\to\libs -tp com.example.myapp
```

或完整参数名：

```bash
java -jar target/decompile-1.0-SNAPSHOT.jar D:\path\to\libs --target-package com.example.myapp
```

**3. 直接反编译 `.class` 文件或 class 输出目录（结果写回原目录，不创建 `.java-decompile`）：**

```bash
# 单个 .class
java -jar target/decompile-1.0-SNAPSHOT.jar D:\path\to\Foo.class

# 仅包含 .class 的目录（如 target/classes）
java -jar target/decompile-1.0-SNAPSHOT.jar D:\path\to\classes
```

### -tp 参数说明

使用 `-tp` 参数时：
- ✅ 仅反编译包含 `com.example.myapp` 包的 JAR/WAR 文件
- ✅ 第三方依赖库（如 spring-core.jar、guava.jar 等）会被跳过
- ✅ 不会移入 `.java-decompile` 目录，减少输出文件数量
- ✅ 聚焦业务代码，便于代码审计

## 输出结构

```
D:\path\to\libs\
├── app.jar
├── spring-core.jar
└── .java-decompile\          # 反编译输出目录
    └── app_src\              # 仅包含 app.jar 的反编译结果
        ├── com\
        │   └── example\
        │       └── myapp\
        │           └── *.java
        ├── *.xml
        └── *.properties      # 非 class 资源文件
```

## 性能对比

| 工具 | 100 个 JAR | 中文支持 | 代码质量 |
|------|-----------|---------|---------|
| jd-cli | ~30s | ❌ 乱码 | 中 |
| Fernflower (旧) | ~25s | ⚠️ 部分乱码 | 好 |
| **Vineflower (本工具)** | **~15s** | ✅ 完美支持 | **优秀** |

## 注意事项

1. **内存需求**: 反编译大量文件时，建议分配更多内存：
   ```bash
   java -Xmx2G -jar target/decompile-1.0-SNAPSHOT.jar D:\path\to\libs
   ```

2. **输出目录**: `.java-decompile` 会被自动创建，无需手动创建

3. **并发处理**: 默认使用 `max(4, CPU 核心数)` 个线程并行处理

4. **首次运行**: 如果是从源码构建，首次会下载 Maven 依赖，请确保网络连接正常

## 技术栈

- **反编译器**: Vineflower 1.9.3 (Fernflower 的现代维护版)
- **命令行解析**: argparse4j 0.9.0
- **Java 版本**: Java 17+
- **并发模型**: ThreadPoolExecutor 多线程并行

## 常见问题

### 中文乱码问题
已内置 UTF-8 编码支持，如果仍有乱码，请确保：
1. 终端/控制台使用 UTF-8 编码
2. Windows 可运行 `chcp 65001` 切换代码页

### 如何查看反编译进度？
工具会实时输出：
- `[DECOMPILED]` - 正在处理的文件
- `[SKIPPED]` - 被 -tp 参数跳过的第三方库

### 反编译失败怎么办？
1. 检查 JAR/WAR 文件是否损坏
2. 查看错误信息：`[ERROR] Failed to decompile: xxx`
3. 某些混淆严重的类可能无法完全还原

## 开发说明

### 项目结构

```
decompile/
├── src/main/java/
│   ├── Main.java           # 命令行入口
│   └── DecompileUtil.java  # 反编译核心逻辑
├── pom.xml
└── README.md
```

### 修改后重新构建

```bash
mvn clean package -DskipTests
```

## License

Apache 2.0
