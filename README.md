# ydiff
Yaml对比工具

## 安装
下载链接: [The Latest Release](https://github.com/zhranklin/ydiff/releases/latest)

根据所在平台选择版本, 如果没有对应平台的二进制, 可以选择noarch版本(依赖Java8)

## 典型场景
### 1. 对比普通yaml文件
```bash
ydiff file1.yaml file2.yaml
```

### 2. 对比Kubernetes资源文件
```bash
ydiff --k8s file1.yaml file2.yaml
```

### 3. 将Kubernetes资源文件与当前集群进行对比
```bash
ydiff --k8s k8s file1.yaml
```

或

```bash
cat file1.yaml | ydiff --k8s
```

## Cookbook
### 1. 对比当前集群与helm release
```bash
helm get manifest <release-name> -n <namespace> | ydiff --k8s
```

### 2. 对比当前集群与chart生成的yaml
```bash
helm template <chart-path> -n <namespace> | ydiff --k8s
```

### 3. 对比时忽略Kubernetes自动生成的默认值
```bash
ydiff --k8s -R https://raw.githubusercontent.com/zhranklin/ydiff/master/builtin-rules ...
```

### 4. 指定kube config
```bash
export KUBECONFIG=xxx
ydiff --k8s ...
```

或

```bash
ydiff --k8s -k 'kubectl --kubeconfig=xxx' ...
```

## 完整参数说明
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

## 过滤规则使用说明
### Rules简介
在与集群进行yaml对比时, 我们会遇到两个问题:

1. 我们的yaml文件并没有某个字段, 但是apply到集群后, k8s会自动加上一些值, 包括一些默认值、自动生成的uid等等, 给我们的对比结果带来一定困扰
2. 有很多复杂对象是以数组形式配置的, 一旦从中间插入, 就会导致错位, 对比出来的结果会非常难以辨认

为了解决这些问题, ydiff的过滤规则有两个用途:

1. 移除掉部分默认值、自动生成的值
2. 将特定的数组按照指定规则转换成map

示例可参考: <https://raw.githubusercontent.com/zhranklin/ydiff/master/builtin-rules>

### Rules
Rules由一些group组成:

```
<group1>
<group2>
<group3>
...
```

### Group
group由kind和一组规则组成:

```
<kind> {
  <rule1>
  <rule2>
  <rule3>
  ...
}
```

其中, kind可以是:

- '*', 代表所有的kind
- 某个k8s kind, 这里的kind是与资源的`.kind`字段直接进行匹配的, 不感知别名等信息
- 也可以通过','分隔表示匹配多种kind, 如'Deployment,Service'

### Rule

Rule由path和matcher组成, 由':'隔开:

```
  <path>: <matcher1>
```

path按照[Ant Matcher](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/util/AntPathMatcher.html)进行匹配json path:

- '?'代表一个字符
- '*'代表0或多个字符
- '**'代表0或多个目录

### Matcher
Matcher有多种:

- `always`: 不论值是什么, 总是移除该字段
- `exact(<my-value>)`: 当值是某个确切值时, 移除掉该字段, 当`<my-value>`为纯数字时表示number, `[]`表示空数组, `{}`表示空对象, 其他情况则当做字符串处理
- `ref(<path>)`: 当值与指定相对路径所对应的值相等时, 移除该字段
- `key(<path>)`: 用于数组, 将指定路径的字段作为key, 将该数组转换为map, 例如`/spec/template/spec/containers/*/env: key(/name)`用于将Deployment中的环境变量列表转换成map, 并且key是环境变量名

### 过滤规则Cookbook
#### 移除resourceVersion等自动生成的字段
```
* {
  /metadata/resourceVersion: always
  /metadata/generation: always
  /metadata/managedFields: always
  /metadata/selfLink: always
  /metadata/uid: always
}
```

#### 当imagePullPolicy字段的值为默认值`IfNotPresent`时, 移除
```
StatefulSet,Deployment,Job {
  /spec/template/spec/containers/*/imagePullPolicy: exact(IfNotPresent)
}
```

#### 将volumes转换为map, key为name, value为整个volume对象
```
StatefulSet,Deployment,Job {
  /spec/template/spec/volumes: key(/name)
}
```
