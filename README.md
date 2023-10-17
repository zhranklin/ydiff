# ydiff
Yaml对比工具

## 安装
下载链接: [The Latest Release](https://github.com/zhranklin/ydiff/releases/latest)

根据所在平台选择版本, 如果没有对应平台的二进制, 可以选择noarch版本(依赖Java8)

## 使用方法
### 典型场景
#### 1. 对比普通yaml文件
```bash
ydiff file1.yaml file2.yaml
```

#### 2. 对比Kubernetes资源文件
```bash
ydiff --k8s file1.yaml file2.yaml
```

#### 3. 将Kubernetes资源文件与当前集群进行对比
```bash
ydiff --k8s k8s file1.yaml
```

或

```bash
cat file1.yaml | ydiff --k8s
```

### Cookbook
#### 1. 对比当前集群与helm release
```bash
helm get manifest <release-name> -n <namespace> | ydiff --k8s
```

#### 2. 对比当前集群与chart生成的yaml
```bash
helm template <chart-path> -n <namespace> | ydiff --k8s
```

#### 3. 对比时忽略Kubernetes自动生成的默认值
```bash
ydiff --k8s -R https://raw.githubusercontent.com/zhranklin/ydiff/master/builtin-rules ...
```

#### 4. 指定kube config
```bash
export KUBECONFIG=xxx
ydiff --k8s ...
```

或

```bash
ydiff --k8s -k 'kubectl --kubeconfig=xxx' ...
```

### 完整使用说明
```bash
ydiff -h
Yaml对比工具
Usage: ydiff [diff|kubectl] [options] <args>...

  -h, --help               显示帮助文档
  -v, --version            显示版本
  -l, --lang <language>    Choose language(zh/en)|选择语言(zh/en)

Command: diff [options] [source-file] [target-file]
(默认)运行YAML对比工具
  --k8s                    将输入当做Kubernetes资源处理。
  --neat                   使用kubectl neat
  --json-patch             输出kubectl patch命令(仅k8s模式)。
  --show-new               完整输出新增的YAML。
  --show-removed           完成输出删除的YAML。
  --only-id                对于所有的修改/删除/新增YAML, 只输出ID。
  --no-inline              按行显示差异
  --no-removed             不显示删除的资源
  --no-expand-text         不会自动将字符串展开成yaml进行对比
  -k, --kubectl-cmd <cmd>  指定kubectl命令
  -m, --multi-lines-around <lines>
                           跨行字符串中, 差异文本上下保留的行数。
  -r, --extra-rules <rule-text>
                           额外的过滤规则, 可多次指定(仅k8s模式)。
  -R, --extra-rule-file <file>
                           额外的过滤规则文件/URL, 可多次指定(仅k8s模式)。如-R https://raw.githubusercontent.com/zhranklin/ydiff/master/builtin-rules
  --dump <file>            备份的文件名, 设置后将备份导出源yaml(仅k8s模式)。
  source-file              来源文件名, 用k8s表示取自k8s集群, 默认为k8s。
  target-file              目标文件名, 默认为标准输入。

Command: kubectl [options]
kubectl命令行模拟, 详见: ydiff kubectl --help
  -h, --help               显示ydiff kubectl帮助文档
```

